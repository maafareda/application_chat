package com.example.testing01;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class ChatDatabase implements AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Connection connection;

    ChatDatabase(String databasePath) throws SQLException, java.io.IOException {
        if (!databasePath.equals(":memory:")) {
            Path file = Path.of(databasePath).toAbsolutePath();
            Files.createDirectories(file.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        } else {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        sender TEXT NOT NULL,
                        receiver TEXT NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    synchronized void addUser(String username) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("INSERT OR IGNORE INTO users(username) VALUES (?)")) {
            statement.setString(1, username);
            statement.executeUpdate();
        }
    }

    synchronized boolean userExists(String username) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT 1 FROM users WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    synchronized List<String> getUsers() throws SQLException {
        List<String> users = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT username FROM users ORDER BY username COLLATE NOCASE")) {
            while (result.next()) {
                users.add(result.getString("username"));
            }
        }
        return users;
    }

    synchronized String saveMessage(String sender, String receiver, String type, String content) throws SQLException {
        String timestamp = currentTimestamp();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO messages(sender, receiver, type, content, ts) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, sender);
            statement.setString(2, receiver);
            statement.setString(3, type);
            statement.setString(4, content);
            statement.setString(5, timestamp);
            statement.executeUpdate();
        }
        return timestamp;
    }

    synchronized List<HistoryMessage> getConversation(String user, String contact) throws SQLException {
        List<HistoryMessage> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sender, type, content, ts
                FROM messages
                WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?)
                ORDER BY id
                """)) {
            statement.setString(1, user);
            statement.setString(2, contact);
            statement.setString(3, contact);
            statement.setString(4, user);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(new HistoryMessage(
                            result.getString("sender"),
                            result.getString("type"),
                            result.getString("content"),
                            result.getString("ts")));
                }
            }
        }
        return messages;
    }

    static String currentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    record HistoryMessage(String sender, String type, String content, String timestamp) {}
}
