package com.example.testing01;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void twoClientsCanExchangeAndReadMessages() throws Exception {
        try (ChatServer server = new ChatServer(0, temporaryDirectory.resolve("chat.db").toString())) {
            server.start();
            try (TestClient alice = new TestClient(server.getPort());
                 TestClient bob = new TestClient(server.getPort())) {
                alice.send("LOGIN|Alice");
                assertEquals("LOGIN_OK|Alice", alice.readLine());
                assertTrue(alice.readLine().startsWith("USERS|Alice|Alice"));

                bob.send("LOGIN|Bob");
                assertEquals("LOGIN_OK|Bob", bob.readLine());
                assertTrue(bob.readLine().startsWith("USERS|Alice,Bob|Alice,Bob"));
                assertTrue(alice.readLine().startsWith("USERS|Alice,Bob|Alice,Bob"));

                String text = "Bonjour Bob";
                String encoded = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
                alice.send("MSG|Bob|TEXT|" + encoded);
                assertTrue(alice.readLine().startsWith("FROM|Alice|Bob|TEXT|" + encoded + "|"));
                assertTrue(bob.readLine().startsWith("FROM|Alice|Bob|TEXT|" + encoded + "|"));

                bob.send("HISTORY|Alice");
                assertEquals("HISTORY_BEGIN|Alice", bob.readLine());
                assertTrue(bob.readLine().startsWith("HISTORY|Alice|Alice|TEXT|" + encoded + "|"));
                assertEquals("HISTORY_END|Alice", bob.readLine());
            }
        }
    }

    @Test
    void twoClientsCanFindEachOtherWhenTheFirstClientStartsTheLocalServer() throws Exception {
        try (java.net.ServerSocket portFinder = new java.net.ServerSocket(0)) {
            int port = portFinder.getLocalPort();
            portFinder.close();

            try {
                ChatServer.ensureLocalServer(
                        "localhost", port, temporaryDirectory.resolve("auto-start.db").toString());
                try (TestClient alice = new TestClient(port);
                     TestClient bob = new TestClient(port)) {
                    alice.send("LOGIN|Alice");
                    assertEquals("LOGIN_OK|Alice", alice.readLine());
                    assertTrue(alice.readLine().startsWith("USERS|Alice|Alice"));

                    bob.send("LOGIN|Bob");
                    assertEquals("LOGIN_OK|Bob", bob.readLine());
                    assertTrue(bob.readLine().startsWith("USERS|Alice,Bob|Alice,Bob"));
                    assertTrue(alice.readLine().startsWith("USERS|Alice,Bob|Alice,Bob"));
                }
            } finally {
                ChatServer.stopLocalServer();
            }
        }
    }

    private static final class TestClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader input;
        private final PrintWriter output;

        private TestClient(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        private void send(String line) {
            output.println(line);
            if (output.checkError()) {
                throw new AssertionError("Impossible d'envoyer la commande au serveur.");
            }
        }

        private String readLine() throws Exception {
            String line = input.readLine();
            if (line == null) {
                throw new AssertionError("Le serveur a fermé la connexion avant la réponse attendue.");
            }
            return line;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
