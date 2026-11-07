package com.example.testing01;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChatServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final Set<String> MESSAGE_TYPES = Set.of("TEXT", "IMAGE", "FILE", "AUDIO");
    private static ChatServer localServer;

    // Socket serveur TCP (orientée connexion) : elle écoute les demandes des clients sur un port.
    // ServerSocket sert uniquement à accepter des connexions TCP.
    private final ServerSocket serverSocket;
    private final ChatDatabase database;
    // Un traitement indépendant par connexion permet de servir plusieurs clients en parallèle.
    private final ExecutorService clientThreads = Executors.newCachedThreadPool();
    // Collections concurrentes partagées entre les traitements des différents clients.
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private boolean closed;

    public ChatServer(int port, String databasePath) throws IOException, SQLException {
        database = new ChatDatabase(databasePath);
        serverSocket = new ServerSocket();
        try {
            serverSocket.setReuseAddress(true);
            // Point d'écoute TCP du serveur : toutes les interfaces réseau, sur le port configuré\
            
            // (6000 par défaut). Le client se connecte à l'adresse IP du serveur et à ce port.
            serverSocket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            database.close();
            throw e;
        }
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    static synchronized void ensureLocalServer(String host, int port, String databasePath)
            throws IOException, SQLException {
        if (!isLocalHost(host)) {
            return;
        }

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        IOException socketFailure = null;
        SQLException databaseFailure = null;
        while (System.nanoTime() < deadline) {
            if (canConnect(host, port) || (localServer != null && localServer.running)) {
                return;
            }

            try {
                localServer = new ChatServer(port, databasePath);
                Runtime.getRuntime().addShutdownHook(
                        new Thread(localServer::close, "reda-local-server-shutdown"));
                localServer.start();
                return;
            } catch (IOException e) {
                socketFailure = e;
            } catch (SQLException e) {
                databaseFailure = e;
            }

            if (canConnect(host, port)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Démarrage du serveur local interrompu.", e);
            }
        }

        if (databaseFailure != null) {
            throw databaseFailure;
        }
        if (socketFailure != null) {
            throw socketFailure;
        }
        throw new IOException("Impossible de démarrer le serveur local sur le port " + port + ".");
    }

    private static boolean isLocalHost(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1");
    }

    private static boolean canConnect(String host, int port) {
        // Courte tentative de connexion TCP pour vérifier si un serveur écoute déjà à cette adresse.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void start() {
        if (running) {
            throw new IllegalStateException("Le serveur est déjà démarré.");
        }
        running = true;
        Thread acceptThread = new Thread(this::acceptClients, "reda-chat-accept");
        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    private void acceptClients() {
        while (running) {
            try {
                // accept() attend une connexion TCP ; chaque connexion acceptée devient une socket
                // dédiée et bidirectionnelle pour communiquer avec un seul client.
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                ClientHandler client = new ClientHandler(this, socket);
                clients.add(client);
                try {
                    // Délègue ce client à son propre traitement sans bloquer l'acceptation des suivants.
                    clientThreads.execute(client);
                } catch (RejectedExecutionException e) {
                    client.closeConnection();
                    clients.remove(client);
                    return;
                }
            } catch (SocketException e) {
                if (running) {
                    LOGGER.log(Level.SEVERE, "La socket du serveur a été fermée de façon inattendue.", e);
                }
                return;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Impossible d'accepter une connexion client.", e);
            }
        }
    }

    boolean login(ClientHandler client, String username) throws SQLException {
        database.addUser(username);
        // L'insertion atomique empêche deux connexions d'utiliser le même nom simultanément.
        if (onlineUsers.putIfAbsent(username, client) != null) {
            return false;
        }
        client.setUsername(username);
        client.sendLine("LOGIN_OK|" + username);
        broadcastUsers();
        LOGGER.info(() -> username + " est connecté.");
        return true;
    }

    void logout(ClientHandler client) {
        clients.remove(client);
        String username = client.getUsername();
        if (username != null && onlineUsers.remove(username, client) && running) {
            LOGGER.info(() -> username + " est déconnecté.");
            broadcastUsers();
        }
    }

    void handleMessage(ClientHandler sender, String line) throws SQLException, IOException {
        String[] parts = line.split("\\|", 4);
        if (parts.length != 4 || !parts[0].equals("MSG")) {
            sender.sendError("Format de message invalide.");
            return;
        }

        String receiver = parts[1];
        String type = parts[2];
        String encodedContent = parts[3];
        if (!MESSAGE_TYPES.contains(type)) {
            sender.sendError("Type de message non pris en charge.");
            return;
        }

        byte[] contentBytes;
        try {
            contentBytes = java.util.Base64.getUrlDecoder().decode(encodedContent);
        } catch (IllegalArgumentException e) {
            sender.sendError("Le contenu du message est invalide.");
            return;
        }
        if (contentBytes.length > MAX_MESSAGE_BYTES) {
            sender.sendError("Le message dépasse la taille maximale de 8 Mo.");
            return;
        }

        String username = sender.getUsername();
        if (receiver.equals("ALL")) {
            database.saveMessage(username, receiver, type, encodedContent);
            String timestamp = ChatDatabase.currentTimestamp();
            // Relaye le message à tous les clients connectés, puis le renvoie à son expéditeur.
            for (ClientHandler client : clients) {
                if (client != sender && client.getUsername() != null) {
                    client.sendMessage(username, receiver, type, encodedContent, timestamp);
                }
            }
            sender.sendMessage(username, receiver, type, encodedContent, timestamp);
            return;
        }

        if (!database.userExists(receiver) || receiver.equals(username)) {
            sender.sendError("Utilisateur destinataire introuvable.");
            return;
        }

        String timestamp = database.saveMessage(username, receiver, type, encodedContent);
        sender.sendMessage(username, receiver, type, encodedContent, timestamp);
        ClientHandler recipient = onlineUsers.get(receiver);
        if (recipient != null) {
            recipient.sendMessage(username, receiver, type, encodedContent, timestamp);
        }
    }

    void sendHistory(ClientHandler client, String receiver) throws SQLException, IOException {
        if (!database.userExists(receiver)) {
            client.sendError("Utilisateur introuvable.");
            return;
        }

        client.sendLine("HISTORY_BEGIN|" + receiver);
        for (ChatDatabase.HistoryMessage message : database.getConversation(client.getUsername(), receiver)) {
            client.sendLine("HISTORY|" + receiver + "|" + message.sender() + "|" + message.type()
                    + "|" + message.content() + "|" + message.timestamp());
        }
        client.sendLine("HISTORY_END|" + receiver);
    }

    private synchronized void broadcastUsers() {
        if (!running) {
            return;
        }
        try {
            String users = String.join(",", database.getUsers());
            String online = String.join(",", new TreeSet<>(onlineUsers.keySet()));
            String update = "USERS|" + users + "|" + online;
            for (ClientHandler client : clients) {
                if (client.getUsername() != null) {
                    client.sendLine(update);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Impossible de charger la liste des utilisateurs.", e);
        }
    }

    static boolean isValidUsername(String username) {
        return username != null && username.matches("[\\p{L}\\p{N}_ -]{1,24}") && !username.isBlank();
    }

    static boolean isValidMessageType(String type) {
        return MESSAGE_TYPES.contains(type);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Erreur lors de la fermeture du serveur.", e);
        }
        for (ClientHandler client : clients) {
            client.closeConnection();
        }
        clientThreads.shutdownNow();
        try {
            if (!clientThreads.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                LOGGER.warning("Certains traitements client ne se sont pas arrêtés dans le délai prévu.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            database.close();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Erreur lors de la fermeture de la base de données.", e);
        }
    }

    static synchronized void stopLocalServer() {
        if (localServer != null) {
            localServer.close();
            localServer = null;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("REDA_APP_PORT", "6000"));
        String databasePath = System.getenv().getOrDefault("REDA_APP_DB", "reda_app.db");
        ChatServer server = new ChatServer(port, databasePath);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "reda-chat-shutdown"));
        // Démarre le thread qui écoute les connexions TCP entrantes.
        server.start();
        System.out.println("Reda_App écoute sur le port " + server.getPort() + ".");
    }
}
