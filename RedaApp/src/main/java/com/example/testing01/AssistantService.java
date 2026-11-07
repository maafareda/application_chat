package com.example.testing01;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AssistantService {
    private static final Logger LOGGER = Logger.getLogger(AssistantService.class.getName());
    private static final String SYSTEM_PROMPT = "Tu es l'assistant de Reda_App. "
            + "Réponds en français, clairement, sur l'utilisation de cette application de discussion.";

    private final HttpClient httpClient;
    private final URI ollamaEndpoint;
    private final URI localApiEndpoint;

    public AssistantService() {
        String host = System.getenv().getOrDefault("REDA_APP_HOST", "localhost").strip();
        ollamaEndpoint = URI.create(System.getenv().getOrDefault(
                "REDA_APP_OLLAMA_URL", "http://" + host + ":11434/api/generate"));
        localApiEndpoint = URI.create(System.getenv().getOrDefault(
                "REDA_APP_AI_URL", "http://" + host + ":5000/ask"));
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    AssistantService(URI ollamaEndpoint, URI localApiEndpoint) {
        this.ollamaEndpoint = ollamaEndpoint;
        this.localApiEndpoint = localApiEndpoint;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .build();
    }

    public CompletableFuture<Answer> ask(String question, List<Turn> history) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return askOllama(question, history);
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOGGER.log(Level.INFO, "Ollama indisponible; essai de l'API Reda_App.", e);
            }

            try {
                return askLocalApi(question);
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOGGER.log(Level.INFO, "API LLM indisponible; réponse locale utilisée.", e);
            }

            return new Answer(localAnswer(question), "Assistant Reda_App (hors ligne)");
        });
    }

    static String localAnswer(String question) {
        String text = question.toLowerCase(Locale.ROOT);
        if (text.contains("bonjour") || text.contains("salut") || text.contains("hello")) {
            return "Bonjour ! Je peux vous aider avec les discussions, les messages, les emojis et les messages vocaux.";
        }
        if (text.contains("audio") || text.contains("vocal") || text.contains("micro")) {
            return "Dans une discussion, appuyez sur « Enregistrer » pour démarrer un message vocal, "
                    + "puis sur « Arrêter » pour l'envoyer. Le destinataire peut ensuite l'écouter.";
        }
        if (text.contains("emoji") || text.contains("émotic")) {
            return "Dans une discussion, appuyez sur le bouton 😊 pour choisir un emoji et l'ajouter à votre message.";
        }
        if (text.contains("image") || text.contains("fichier") || text.contains("photo")) {
            return "Choisissez une discussion puis utilisez le bouton Image ou Fichier. "
                    + "Les pièces jointes sont limitées à 5 Mo.";
        }
        if (text.contains("connexion") || text.contains("serveur") || text.contains("connect")) {
            return "Lancez Reda_App avec le fichier Start-RedaApp.bat. Il démarre le serveur une seule fois "
                    + "et vous pouvez relancer le même fichier pour ouvrir un autre utilisateur.";
        }
        if (text.contains("message") || text.contains("discussion") || text.contains("whatsapp")) {
            return "Sélectionnez un utilisateur dans la liste pour ouvrir sa discussion. "
                    + "Les messages et leur historique sont transmis par TCP et enregistrés par le serveur.";
        }
        return "Je fonctionne en mode hors ligne. Demandez-moi de l'aide sur les messages, les discussions, "
                + "les emojis, les fichiers ou les messages vocaux. Pour un vrai modèle LLM, installez Ollama "
                + "et lancez « ollama pull llama3.2:1b ».";
    }

    private Answer askOllama(String question, List<Turn> history)
            throws IOException, InterruptedException {
        String model = System.getenv().getOrDefault("REDA_APP_OLLAMA_MODEL", "llama3.2:1b");
        String prompt = buildPrompt(question, history);
        String json = "{\"model\":\"" + escapeJson(model)
                + "\",\"system\":\"" + escapeJson(SYSTEM_PROMPT)
                + "\",\"prompt\":\"" + escapeJson(prompt)
                + "\",\"stream\":false,\"options\":{\"temperature\":0.3}}";

        HttpRequest request = HttpRequest.newBuilder(ollamaEndpoint)
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Ollama a répondu avec le code HTTP " + response.statusCode() + ".");
        }
        String answer = jsonString(response.body(), "response");
        if (answer.isBlank()) {
            throw new IOException("Ollama a renvoyé une réponse vide.");
        }
        return new Answer(answer, "Ollama — " + model);
    }

    private Answer askLocalApi(String question) throws IOException, InterruptedException {
        String body = "q=" + URLEncoder.encode(question, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(localApiEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("L'API Reda_App a répondu avec le code HTTP " + response.statusCode() + ".");
        }
        if (response.body().isBlank()) {
            throw new IOException("L'API Reda_App a renvoyé une réponse vide.");
        }
        return new Answer(response.body(), "API Reda_App");
    }

    private String buildPrompt(String question, List<Turn> history) {
        StringBuilder prompt = new StringBuilder();
        int firstTurn = Math.max(0, history.size() - 10);
        for (int index = firstTurn; index < history.size(); index++) {
            Turn turn = history.get(index);
            prompt.append(turn.speaker()).append(": ").append(turn.message()).append('\n');
        }
        prompt.append("Utilisateur: ").append(question).append("\nAssistant:");
        return prompt.toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String jsonString(String json, String key) throws IOException {
        String marker = "\"" + key + "\"";
        int index = json.indexOf(marker);
        if (index < 0) {
            throw new IOException("La réponse JSON ne contient pas le champ " + key + ".");
        }
        index = json.indexOf(':', index + marker.length());
        if (index < 0) {
            throw new IOException("La réponse JSON ne contient pas de valeur pour " + key + ".");
        }
        index++;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        if (index >= json.length() || json.charAt(index++) != '"') {
            throw new IOException("Le champ " + key + " de la réponse JSON est invalide.");
        }

        StringBuilder value = new StringBuilder();
        while (index < json.length()) {
            char character = json.charAt(index++);
            if (character == '"') {
                return value.toString();
            }
            if (character != '\\') {
                value.append(character);
                continue;
            }
            if (index >= json.length()) {
                throw new IOException("La réponse JSON contient une séquence incomplète.");
            }
            char escaped = json.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (index + 4 > json.length()) {
                        throw new IOException("La réponse JSON contient un caractère incomplet.");
                    }
                    try {
                        value.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                    } catch (NumberFormatException e) {
                        throw new IOException("La réponse JSON contient un caractère invalide.", e);
                    }
                    index += 4;
                }
                default -> throw new IOException("La réponse JSON contient un échappement invalide.");
            }
        }
        throw new IOException("La chaîne JSON est incomplète.");
    }

    public record Answer(String text, String engine) {}

    public record Turn(String speaker, String message) {}
}
