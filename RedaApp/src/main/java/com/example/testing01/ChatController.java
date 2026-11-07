package com.example.testing01;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Window;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatController {
    private static final Logger LOGGER = Logger.getLogger(ChatController.class.getName());
    private static final long MAX_ATTACHMENT_SIZE = 5L * 1024 * 1024;
    private static final int MAX_VOICE_MESSAGE_SIZE = 2 * 1024 * 1024;
    private static final AudioFormat VOICE_FORMAT = new AudioFormat(16_000, 16, 1, true, false);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> EMOJIS = List.of(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
            "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
            "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔",
            "😐", "😏", "😒", "🙄", "😬", "🤥", "😔", "😪", "🤤", "😴",
            "😷", "🤒", "🤕", "🤢", "🥳", "😭", "😢", "😡", "🤬", "👍",
            "👎", "👏", "🙌", "🙏", "🤝", "👋", "✋", "👌", "✌️", "🤞",
            "💪", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "💯",
            "🔥", "✨", "🎉", "🎊", "💬", "📱", "☕", "🍕", "⚽", "🌍",
            "🐱", "🐶", "🌹", "☀️", "🌙", "✅", "❌", "⭐", "🎵", "📷"
    );

    @FXML private ListView<String> userList;
    @FXML private VBox messageBox;
    @FXML private TextField inputField;
    @FXML private TextField searchField;
    @FXML private Label currentUserLabel;
    @FXML private Label statusLabel;
    @FXML private Label conversationLabel;
    @FXML private Button sendBtn;
    @FXML private Button emojiBtn;
    @FXML private Button imageBtn;
    @FXML private Button fileBtn;
    @FXML private Button audioBtn;
    @FXML private Button llmBtn;
    @FXML private ScrollPane messageScroll;
    @FXML private TabPane mainTabs;
    @FXML private Tab assistantTab;
    @FXML private VBox assistantBox;
    @FXML private TextField assistantInputField;
    @FXML private Button assistantSendBtn;
    @FXML private Label assistantStatusLabel;
    @FXML private ScrollPane assistantScroll;

    private final Set<String> allUsers = new HashSet<>();
    private final Set<String> onlineUsers = new HashSet<>();
    private final List<AssistantService.Turn> assistantHistory = new ArrayList<>();
    private final AssistantService assistant = new AssistantService();

    private ChatClient client;
    private String username;
    private String selectedUser;
    private boolean loginAccepted;
    private TargetDataLine recordingLine;
    private ByteArrayOutputStream recordingBuffer;
    private volatile boolean recording;
    private volatile boolean closing;

    @FXML
    public void initialize() {
        userList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setText(null);
                } else {
                    setText((onlineUsers.contains(user) ? "●  " : "○  ") + user);
                }
            }
        });

        userList.getSelectionModel().selectedItemProperty().addListener((observable, oldUser, newUser) -> {
            if (newUser != null) {
                openConversation(newUser);
            }
        });
        searchField.textProperty().addListener((observable, oldValue, newValue) -> showUsers(newValue));
        sendBtn.setOnAction(event -> sendText());
        inputField.setOnAction(event -> sendText());
        emojiBtn.setOnAction(event -> showEmojiPicker());
        imageBtn.setOnAction(event -> chooseAttachment("IMAGE"));
        fileBtn.setOnAction(event -> chooseAttachment("FILE"));
        audioBtn.setOnAction(event -> toggleVoiceRecording());
        llmBtn.setOnAction(event -> openAssistant());
        assistantSendBtn.setOnAction(event -> askAssistant());
        assistantInputField.setOnAction(event -> askAssistant());
    }

    public void start(String name) {
        username = name.strip();
        currentUserLabel.setText(username);
        String host = System.getenv().getOrDefault("REDA_APP_HOST", "localhost").strip();
        String portValue = System.getenv().getOrDefault("REDA_APP_PORT", "6000");
        try {
            int port = Integer.parseInt(portValue);
            if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
                ChatServer.ensureLocalServer(
                        host, port, System.getenv().getOrDefault("REDA_APP_DB", "reda_app.db"));
            }
            client = new ChatClient(host, port, this::receiveMessage, this::showDisconnect);
            client.login(username);
            statusLabel.setText("● Connexion...");
            statusLabel.setTextFill(Color.web("#DFAF2B"));
        } catch (IOException | java.sql.SQLException | NumberFormatException e) {
            statusLabel.setText("● Serveur indisponible");
            statusLabel.setTextFill(Color.web("#E53935"));
            showAlert(Alert.AlertType.WARNING, "Connexion impossible",
                    "Impossible de joindre le serveur " + host + ":" + portValue + ".\n" + e.getMessage());
        }
    }

    private void receiveMessage(String line) {
        Platform.runLater(() -> handleServerMessage(line));
    }

    private void showDisconnect(IOException error) {
        Platform.runLater(() -> {
            if (loginAccepted) {
                statusLabel.setText("● Déconnecté");
                statusLabel.setTextFill(Color.web("#E53935"));
            } else {
                statusLabel.setText("● Connexion refusée");
                statusLabel.setTextFill(Color.web("#E53935"));
                userList.getItems().clear();
            }
            LOGGER.log(Level.WARNING, "Connexion au serveur interrompue.", error);
        });
    }

    private void handleServerMessage(String line) {
        if (line.startsWith("LOGIN_OK|")) {
            loginAccepted = true;
            statusLabel.setText("● En ligne");
            statusLabel.setTextFill(Color.web("#25D366"));
        } else if (line.startsWith("USERS|")) {
            String[] parts = line.split("\\|", 3);
            allUsers.clear();
            onlineUsers.clear();
            if (parts.length > 1 && !parts[1].isBlank()) {
                allUsers.addAll(List.of(parts[1].split(",")));
            }
            if (parts.length > 2 && !parts[2].isBlank()) {
                onlineUsers.addAll(List.of(parts[2].split(",")));
            }
            allUsers.remove(username);
            showUsers(searchField.getText());
        } else if (line.startsWith("HISTORY_BEGIN|")) {
            String contact = line.substring("HISTORY_BEGIN|".length());
            if (contact.equals(selectedUser)) {
                messageBox.getChildren().clear();
            }
        } else if (line.startsWith("HISTORY|")) {
            showHistoryMessage(line);
        } else if (line.startsWith("FROM|")) {
            showReceivedMessage(line);
        } else if (line.startsWith("ERROR|")) {
            String encoded = line.substring("ERROR|".length());
            String message = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (!loginAccepted) {
                statusLabel.setText("● Nom déjà utilisé");
                statusLabel.setTextFill(Color.web("#E53935"));
                userList.getItems().clear();
            }
            showAlert(Alert.AlertType.WARNING, "Message du serveur", message);
        }
    }

    private void showHistoryMessage(String line) {
        String[] parts = line.split("\\|", 6);
        if (parts.length != 6 || !parts[1].equals(selectedUser)) {
            return;
        }
        String content = decodeContent(parts[4]);
        addBubble(parts[2], parts[3], content, parts[2].equals(username), formatTime(parts[5]));
    }

    private void showReceivedMessage(String line) {
        String[] parts = line.split("\\|", 6);
        if (parts.length != 6) {
            return;
        }
        String sender = parts[1];
        String receiver = parts[2];
        if (!receiver.equals("ALL") && !isCurrentConversation(sender, receiver)) {
            return;
        }
        String content = decodeContent(parts[4]);
        addBubble(sender, parts[3], content, sender.equals(username), formatTime(parts[5]));
    }

    private String decodeContent(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private boolean isCurrentConversation(String sender, String receiver) {
        return selectedUser != null
                && ((sender.equals(username) && receiver.equals(selectedUser))
                || (sender.equals(selectedUser) && receiver.equals(username)));
    }

    private void openConversation(String user) {
        selectedUser = user;
        conversationLabel.setText(user);
        messageBox.getChildren().clear();
        if (client == null) {
            return;
        }
        try {
            client.requestHistory(user);
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR, "Historique indisponible", e.getMessage());
        }
    }

    private void showUsers(String search) {
        String query = search == null ? "" : search.strip().toLowerCase();
        List<String> users = new ArrayList<>(allUsers);
        users.sort(Comparator.comparing((String user) -> !onlineUsers.contains(user))
                .thenComparing(String::compareToIgnoreCase));
        userList.getItems().setAll(users.stream()
                .filter(user -> user.toLowerCase().contains(query))
                .toList());
    }

    private void sendText() {
        String text = inputField.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        if (sendToSelectedUser("TEXT", text)) {
            inputField.clear();
        }
    }

    private boolean sendToSelectedUser(String type, String content) {
        if (client == null) {
            showAlert(Alert.AlertType.WARNING, "Serveur indisponible",
                    "Lancez l'application avec Start-RedaApp.bat puis réessayez.");
            return false;
        }
        if (selectedUser == null) {
            showAlert(Alert.AlertType.INFORMATION, "Aucune conversation",
                    "Sélectionnez un utilisateur avant d'envoyer un message.");
            return false;
        }
        try {
            client.sendMessage(selectedUser, type, content);
            return true;
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR, "Envoi impossible", e.getMessage());
            return false;
        }
    }

    private void chooseAttachment(String type) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choisir un fichier");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Tous les fichiers", "*.*"));

        Window owner = inputField.getScene().getWindow();
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        try {
            long size = Files.size(file.toPath());
            if (size > MAX_ATTACHMENT_SIZE) {
                showAlert(Alert.AlertType.WARNING, "Fichier trop volumineux",
                        "La taille maximale d'une pièce jointe est de 5 Mo.");
                return;
            }
            String content = file.getName() + "\n"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            sendToSelectedUser(type, content);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Impossible de lire la pièce jointe.", e);
            showAlert(Alert.AlertType.ERROR, "Lecture impossible", e.getMessage());
        }
    }

    private void showEmojiPicker() {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        FlowPane emojis = new FlowPane();
        emojis.setHgap(4);
        emojis.setVgap(4);
        emojis.setPadding(new Insets(10));
        emojis.setPrefWrapLength(300);
        emojis.setStyle("-fx-background-color:white;-fx-background-radius:10;"
                + "-fx-border-color:#D5D5D5;-fx-border-radius:10;");

        for (String emoji : EMOJIS) {
            Button choice = new Button(emoji);
            choice.setStyle("-fx-background-color:transparent;-fx-font-size:20;-fx-padding:5;");
            choice.setOnAction(event -> {
                inputField.appendText(emoji);
                popup.hide();
                inputField.requestFocus();
            });
            emojis.getChildren().add(choice);
        }

        popup.getContent().add(emojis);
        Bounds bounds = emojiBtn.localToScreen(emojiBtn.getBoundsInLocal());
        if (bounds != null) {
            popup.show(emojiBtn, bounds.getMinX(), bounds.getMinY() - 230);
        }
    }

    private void toggleVoiceRecording() {
        if (recording) {
            stopVoiceRecording();
            return;
        }
        if (selectedUser == null) {
            showAlert(Alert.AlertType.INFORMATION, "Aucune discussion",
                    "Sélectionnez un utilisateur avant d'enregistrer un message vocal.");
            return;
        }
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, VOICE_FORMAT);
            recordingLine = (TargetDataLine) AudioSystem.getLine(info);
            recordingLine.open(VOICE_FORMAT);
            recordingBuffer = new ByteArrayOutputStream();
            recording = true;
            userList.setDisable(true);
            audioBtn.setText("⏹ Arrêter");
            audioBtn.setStyle("-fx-background-color:#E53935;-fx-text-fill:white;"
                    + "-fx-background-radius:16;-fx-padding:7 12;");

            TargetDataLine line = recordingLine;
            ByteArrayOutputStream buffer = recordingBuffer;
            Thread recorder = new Thread(() -> captureVoiceMessage(line, buffer),
                    "reda-voice-recorder");
            recorder.setDaemon(true);
            recorder.start();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            recording = false;
            showAlert(Alert.AlertType.ERROR, "Microphone indisponible",
                    "Impossible d'accéder au microphone. Vérifiez le microphone et ses autorisations.\n"
                            + e.getMessage());
        }
    }

    private void captureVoiceMessage(TargetDataLine line, ByteArrayOutputStream buffer) {
        byte[] chunk = new byte[4096];
        boolean tooLong = false;
        try {
            line.start();
            while (recording) {
                int count = line.read(chunk, 0, chunk.length);
                if (count > 0) {
                    synchronized (buffer) {
                        if (buffer.size() + count > MAX_VOICE_MESSAGE_SIZE) {
                            tooLong = true;
                            recording = false;
                        } else {
                            buffer.write(chunk, 0, count);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            if (recording) {
                LOGGER.log(Level.WARNING, "Erreur de lecture du microphone.", e);
            }
        } finally {
            closeRecordingLine(line);
            byte[] rawAudio;
            synchronized (buffer) {
                rawAudio = buffer.toByteArray();
            }
            byte[] wav = encodeWav(rawAudio);
            boolean reachedLimit = tooLong;
            Platform.runLater(() -> finishVoiceRecording(wav, reachedLimit));
        }
    }

    private byte[] encodeWav(byte[] rawAudio) {
        if (rawAudio.length == 0) {
            return new byte[0];
        }
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(rawAudio), VOICE_FORMAT,
                rawAudio.length / VOICE_FORMAT.getFrameSize());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
            return output.toByteArray();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Impossible de convertir l'enregistrement en WAV.", e);
            return new byte[0];
        }
    }

    private void stopVoiceRecording() {
        recording = false;
        TargetDataLine line = recordingLine;
        if (line != null) {
            closeRecordingLine(line);
        }
    }

    private void closeRecordingLine(TargetDataLine line) {
        synchronized (line) {
            if (line.isOpen()) {
                line.stop();
                line.close();
            }
        }
    }

    private void finishVoiceRecording(byte[] wav, boolean tooLong) {
        recordingLine = null;
        recordingBuffer = null;
        userList.setDisable(false);
        audioBtn.setText("🎙️ Enregistrer");
        audioBtn.setStyle("-fx-background-color:white;-fx-background-radius:16;-fx-padding:7 12;");
        if (closing) {
            return;
        }
        if (tooLong) {
            showAlert(Alert.AlertType.WARNING, "Message vocal trop long",
                    "L'enregistrement a atteint la limite de taille. Le message vocal a été arrêté.");
        }
        if (wav.length == 0) {
            return;
        }
        String content = "message-vocal.wav\n" + Base64.getEncoder().encodeToString(wav);
        sendToSelectedUser("AUDIO", content);
    }

    private void addBubble(String sender, String type, String content, boolean isMine, String time) {
        HBox row = new HBox();
        row.setAlignment(isMine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox bubble = new VBox(5);
        bubble.setMaxWidth(360);
        bubble.setPadding(new Insets(8, 12, 8, 12));
        bubble.setStyle(isMine
                ? "-fx-background-color:#D7F8C6;-fx-background-radius:12;"
                : "-fx-background-color:white;-fx-background-radius:12;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.12),4,0,0,1);");

        Label senderLabel = new Label(sender);
        senderLabel.setStyle("-fx-font-size:11;-fx-text-fill:#075E54;-fx-font-weight:bold;");
        bubble.getChildren().add(senderLabel);

        if (type.equals("IMAGE")) {
            addImage(bubble, content);
        } else if (type.equals("FILE") || type.equals("AUDIO")) {
            addDownloadableFile(bubble, type, content);
        } else {
            Label text = new Label(content);
            text.setWrapText(true);
            bubble.getChildren().add(text);
        }

        Label timeLabel = new Label(time);
        timeLabel.setStyle("-fx-font-size:10;-fx-text-fill:#888;");
        bubble.getChildren().add(timeLabel);
        row.getChildren().add(bubble);
        messageBox.getChildren().add(row);
        messageScroll.setVvalue(1);
    }

    private void addImage(VBox bubble, String content) {
        try {
            byte[] bytes = readAttachment(content).bytes();
            ImageView image = new ImageView(new Image(new ByteArrayInputStream(bytes)));
            image.setFitWidth(280);
            image.setPreserveRatio(true);
            bubble.getChildren().add(image);
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.log(Level.WARNING, "Impossible d'afficher l'image reçue.", e);
            bubble.getChildren().add(new Label("[Image illisible]"));
        }
    }

    private void addDownloadableFile(VBox bubble, String type, String content) {
        try {
            Attachment attachment = readAttachment(content);
            Label name = new Label((type.equals("AUDIO") ? "🎵 " : "📄 ") + attachment.name());
            bubble.getChildren().add(name);
            if (type.equals("AUDIO")) {
                Button play = new Button("▶ Écouter");
                play.setOnAction(event -> playAudio(attachment));
                bubble.getChildren().add(play);
            }
            Button download = new Button("Enregistrer");
            download.setOnAction(event -> saveAttachment(attachment));
            bubble.getChildren().add(download);
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.log(Level.WARNING, "Impossible de lire la pièce jointe reçue.", e);
            bubble.getChildren().add(new Label("[Fichier illisible]"));
        }
    }

    private Attachment readAttachment(String content) throws IOException {
        int separator = content.indexOf('\n');
        if (separator <= 0) {
            throw new IOException("Nom ou contenu de pièce jointe manquant.");
        }
        String fileName = Path.of(content.substring(0, separator)).getFileName().toString();
        byte[] bytes = Base64.getDecoder().decode(content.substring(separator + 1));
        return new Attachment(fileName, bytes);
    }

    private void playAudio(Attachment attachment) {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(
                new ByteArrayInputStream(attachment.bytes()))) {
            Clip clip = AudioSystem.getClip();
            clip.open(input);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            clip.start();
        } catch (IOException | UnsupportedAudioFileException | LineUnavailableException
                 | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Impossible de lire le fichier audio.", e);
            showAlert(Alert.AlertType.WARNING, "Lecture audio impossible",
                    "Ce fichier audio n'est pas compatible avec le lecteur intégré. "
                            + "Enregistrez-le et ouvrez-le avec un lecteur audio.");
        }
    }

    private void saveAttachment(Attachment attachment) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(attachment.name());
        File target = chooser.showSaveDialog(inputField.getScene().getWindow());
        if (target == null) {
            return;
        }
        try {
            Files.write(target.toPath(), attachment.bytes());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Impossible d'enregistrer la pièce jointe.", e);
            showAlert(Alert.AlertType.ERROR, "Enregistrement impossible", e.getMessage());
        }
    }

    private void openAssistant() {
        mainTabs.getSelectionModel().select(assistantTab);
        assistantInputField.requestFocus();
    }

    private void askAssistant() {
        String question = assistantInputField.getText().strip();
        if (question.isEmpty()) {
            return;
        }
        assistantInputField.clear();
        assistantSendBtn.setDisable(true);
        addAssistantBubble("Vous", question, "#DCF8C6");
        Label waiting = new Label("L'assistant prépare une réponse...");
        waiting.setWrapText(true);
        waiting.setStyle("-fx-padding:10;-fx-background-color:white;-fx-background-radius:12;");
        assistantBox.getChildren().add(waiting);
        assistantScroll.setVvalue(1);

        List<AssistantService.Turn> context = List.copyOf(assistantHistory);
        assistant.ask(question, context).whenComplete((answer, error) -> Platform.runLater(() -> {
            assistantBox.getChildren().remove(waiting);
            assistantSendBtn.setDisable(false);
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                assistantStatusLabel.setText("Erreur de l'assistant");
                LOGGER.log(Level.WARNING, "La demande à l'assistant a échoué.", cause);
                addAssistantBubble("Assistant", "Erreur : " + cause.getMessage(), "#FFF0F0");
                return;
            }

            assistantHistory.add(new AssistantService.Turn("Utilisateur", question));
            assistantHistory.add(new AssistantService.Turn("Assistant", answer.text()));
            assistantStatusLabel.setText(answer.engine());
            addAssistantBubble("Assistant · " + answer.engine(), answer.text(), "white");
        }));
    }

    private void addAssistantBubble(String author, String text, String background) {
        VBox bubble = new VBox(5);
        bubble.setMaxWidth(620);
        bubble.setPadding(new Insets(10, 14, 10, 14));
        bubble.setStyle("-fx-background-color:" + background + ";-fx-background-radius:12;");
        Label authorLabel = new Label(author);
        authorLabel.setStyle("-fx-font-weight:bold;-fx-text-fill:#075E54;-fx-font-size:11;");
        TextArea message = new TextArea(text);
        message.setEditable(false);
        message.setWrapText(true);
        message.setFocusTraversable(false);
        message.setPrefRowCount(Math.max(1, Math.min(7, (text.length() / 70) + 1)));
        message.setStyle("-fx-background-color:transparent;-fx-control-inner-background:"
                + background + ";-fx-padding:0;");
        bubble.getChildren().addAll(authorLabel, message);
        HBox row = new HBox(bubble);
        row.setAlignment(author.equals("Vous") ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        assistantBox.getChildren().add(row);
        assistantScroll.setVvalue(1);
    }

    private String formatTime(String timestamp) {
        try {
            return LocalDateTime.parse(timestamp.replace(' ', 'T')).format(DISPLAY_TIME);
        } catch (DateTimeParseException e) {
            return timestamp;
        }
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.show();
    }

    public void close() {
        closing = true;
        if (recording) {
            stopVoiceRecording();
        }
        if (client != null) {
            client.close();
        }
    }

    private record Attachment(String name, byte[] bytes) {}
}
