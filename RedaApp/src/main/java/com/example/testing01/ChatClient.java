package com.example.testing01;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChatClient implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ChatClient.class.getName());

    // Socket client TCP
    private final Socket socket;
    private final BufferedReader input;
    private final PrintWriter output;
    private final Consumer<String> messageListener;
    private final Consumer<IOException> disconnectListener;
    private volatile boolean closed;

    public ChatClient(String host, int port, Consumer<String> messageListener,
                      Consumer<IOException> disconnectListener) throws IOException {
        this.messageListener = messageListener;
        this.disconnectListener = disconnectListener;
        socket = new Socket();
        try {
            //adresse (host)              port               ,         un délai de 5 secondes.
            socket.connect(new InetSocketAddress(host, port), 5_000);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        // Désactive l'algorithme Nagle pour envoyer rapidement les petits messages de discussion.
        socket.setTcpNoDelay(true);


        // Flux texte UTF-8 sur la socket TCP ; les messages sont délimités par des fins de ligne.
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    public void login(String username) throws IOException {
        // Protocole applicatif texte au-dessus de TCP : LOGIN|nom, puis lecture des réponses du serveur.
        sendLine("LOGIN|" + username);
        Thread listener = new Thread(this::readMessages, "reda-chat-listener-" + username);
        listener.setDaemon(true);
        listener.start();
    }

    public void sendMessage(String receiver, String type, String content) throws IOException {
        // Le contenu est encodé en Base64 URL-safe pour tenir sur une ligne ; cet encodage n'est pas du chiffrement.
        String encodedContent = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        sendLine("MSG|" + receiver + "|" + type + "|" + encodedContent);
    }

    public void requestHistory(String username) throws IOException {
        sendLine("HISTORY|" + username);
    }

    private synchronized void sendLine(String line) throws IOException {
        if (closed) {
            throw new IOException("La connexion au serveur est fermée.");
        }
        // Envoie une ligne complète dans le flux TCP ; PrintWriter ajoute la fin de ligne et vide le flux.
        output.println(line);
        if (output.checkError()) {
            throw new IOException("Impossible d'envoyer le message au serveur.");
        }
    }

    private void readMessages() {
        try {
            String line;
            while (!closed && (line = input.readLine()) != null) {
                messageListener.accept(line);
            }
            if (!closed) {
                disconnectListener.accept(new IOException("Le serveur a fermé la connexion."));
            }
        } catch (IOException e) {
            if (!closed) {
                disconnectListener.accept(e);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Erreur lors du traitement d'un message reçu.", e);
            disconnectListener.accept(new IOException("Erreur lors du traitement d'un message reçu.", e));
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Erreur lors de la fermeture du client.", e);
        }
    }
}
