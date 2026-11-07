package com.example.testing01;
//identifie l’utilisateur, traite ses commandes et lui transmet les réponses.
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ClientHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

    private final ChatServer server;
    // Socket TCP acceptée par ChatServer : canal bidirectionnel réservé à cette connexion cliente.
    private final Socket socket;
    // Flux texte UTF-8 transportés par TCP ; chaque commande/réponse occupe une ligne.
    private final BufferedReader input;
    private final PrintWriter output;
    private volatile String username;

    ClientHandler(ChatServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        // Récupère les flux de la socket établie ; le protocole applicatif utilise des lignes texte.
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Override
    public void run() {
        try {
            // La première ligne attendue sur la connexion TCP identifie l'utilisateur.
            String login = input.readLine();
            if (login == null || !login.startsWith("LOGIN|")) {
                sendError("Connexion invalide.");
                return;
            }

            String requestedUsername = login.substring("LOGIN|".length()).strip();
            if (!ChatServer.isValidUsername(requestedUsername)) {
                sendError("Choisissez un nom de 1 à 24 caractères : lettres, chiffres, espaces, tirets ou _.");
                return;
            }
            if (!server.login(this, requestedUsername)) {
                sendError("Ce nom d'utilisateur est déjà connecté. Choisissez un autre nom.");
                return;
            }

            String line;
            // Lit les commandes reçues jusqu'à la fermeture de la connexion par le client.
            while ((line = input.readLine()) != null) {
                if (line.startsWith("MSG|")) {
                    server.handleMessage(this, line);
                } else if (line.startsWith("HISTORY|")) {
                    server.sendHistory(this, line.substring("HISTORY|".length()).strip());
                } else {
                    sendError("Commande inconnue.");
                }
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                LOGGER.log(Level.FINE, "Connexion client interrompue.", e);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Erreur de base de données pour " + username + ".", e);
            sendError("Erreur lors de l'accès à l'historique des messages.");
        } finally {
            server.logout(this);
            closeConnection();
        }
    }

    String getUsername() {
        return username;
    }

    void setUsername(String username) {
        this.username = username;
    }

    synchronized void sendMessage(String sender, String receiver, String type, String content, String timestamp) {
        sendLine("FROM|" + sender + "|" + receiver + "|" + type + "|" + content + "|" + timestamp);
    }

    synchronized void sendError(String message) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(message.getBytes(StandardCharsets.UTF_8));
        sendLine("ERROR|" + encoded);
    }

    synchronized void sendLine(String line) {
        output.println(line);
        if (output.checkError()) {
            LOGGER.warning("Impossible d'envoyer un message à " + username + ".");
        }
    }

    void closeConnection() {
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Erreur lors de la fermeture d'une connexion client.", e);
        }
    }
}
