package com.example.testing01;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.util.Optional;

public class RedaApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        Optional<String> username = getParameters().getRaw().stream()
                .filter(name -> !name.isBlank())
                .findFirst();
        if (username.isEmpty()) {
            username = Optional.ofNullable(System.getenv("REDA_APP_USERNAME"))
                    .filter(name -> !name.isBlank());
        }
        if (username.isEmpty()) {
            TextInputDialog loginDialog = new TextInputDialog();
            loginDialog.setTitle("Reda_App");
            loginDialog.setHeaderText("Bienvenue sur Reda_App");
            loginDialog.setContentText("Entrez votre nom d'utilisateur :");
            username = loginDialog.showAndWait();
        }
        if (username.isEmpty() || username.get().isBlank()) {
            stage.close();
            return;
        }

        FXMLLoader loader = new FXMLLoader(
                RedaApp.class.getResource("/com/example/testing01/reda_app.fxml"));
        BorderPane root = loader.load();
        ChatController controller = loader.getController();
        controller.start(username.get());

        stage.setTitle("Reda_App — " + username.get().strip());
        stage.setScene(new Scene(root, 1100, 700));
        stage.setMinWidth(650);
        stage.setMinHeight(450);
        stage.setOnCloseRequest(event -> controller.close());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
