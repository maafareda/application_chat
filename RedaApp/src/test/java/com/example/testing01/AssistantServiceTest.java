package com.example.testing01;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantServiceTest {
    @Test
    void offlineAssistantExplainsHowToRecordVoiceMessages() {
        String answer = AssistantService.localAnswer("Comment envoyer un message vocal ?");

        assertTrue(answer.contains("Arrêter"));
        assertTrue(answer.contains("destinataire"));
    }

    @Test
    void offlineAssistantExplainsHowToUseEmojis() {
        String answer = AssistantService.localAnswer("Où trouver les emojis ?");

        assertTrue(answer.contains("bouton"));
        assertTrue(answer.contains("emoji"));
    }

    @Test
    void assistantFallsBackWhenBothLocalServicesAreUnavailable() throws Exception {
        AssistantService service = new AssistantService(
                URI.create("http://127.0.0.1:1/api/generate"),
                URI.create("http://127.0.0.1:1/ask"));

        AssistantService.Answer answer = service.ask(
                "Comment envoyer un message vocal ?", List.of())
                .get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);

        assertTrue(answer.engine().contains("hors ligne"));
        assertTrue(answer.text().contains("Arrêter"));
    }
}
