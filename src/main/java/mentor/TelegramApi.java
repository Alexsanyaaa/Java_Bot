package mentor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class TelegramApi {
    record Button(String text, String callback_data) {}

    static final int MAX_VOICE_BYTES = 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private final String fileBase;
    private static final int MESSAGE_LIMIT = 4096;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper json;
    private final String base;
    private final boolean enabled;
    final long ownerId;

    TelegramApi(ObjectMapper json, String base, String token, long ownerId) {
        if (!token.isBlank() && ownerId <= 0) {
            throw new IllegalArgumentException("Укажи TELEGRAM_OWNER_ID, чтобы бот отвечал только владельцу");
        }
        this.json = json;
        this.base = base.replaceAll("/+$", "") + "/bot" + token + "/";
        this.fileBase = base.replaceAll("/+$", "") + "/file/bot" + token + "/";
        this.enabled = !token.isBlank();
        this.ownerId = ownerId;
    }

    boolean enabled() {
        return enabled;
    }

    JsonNode updates(long offset) throws IOException, InterruptedException {
        return request("getUpdates", Map.of("offset", offset, "timeout", 20,
                "allowed_updates", List.of("message", "callback_query", "message_reaction")));
    }

    /** Shows «печатает…» in the chat for about five seconds. */
    void typing(long chat) throws IOException, InterruptedException {
        request("sendChatAction", Map.of("chat_id", chat, "action", "typing"));
    }

    void answerCallback(String callback) throws IOException, InterruptedException {
        request("answerCallbackQuery", Map.of("callback_query_id", callback));
    }

    void react(long chat, long messageId, String emoji) throws IOException, InterruptedException {
        if (messageId <= 0 || !MentorModel.REACTION_EMOJI.contains(emoji)) return;
        var body = Map.of("chat_id", chat, "message_id", messageId,
                "reaction", List.of(Map.of("type", "emoji", "emoji", emoji)), "is_big", false);
        request("setMessageReaction", "application/json",
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)), Duration.ofSeconds(4));
    }

    void menu(long chat, String text) throws IOException, InterruptedException {
        request("sendMessage", Map.of("chat_id", chat, "text", text, "reply_markup", Map.of(
                "keyboard", List.of(List.of("📚 Урок", "🗓 План"), List.of("🎙 Собеседование", "🏫 Школа 21"),
                        List.of("📊 Прогресс", "⚙️ Настройки")),
                "resize_keyboard", true, "is_persistent", true)));
    }

    void ask(long chat, String text, String placeholder) throws IOException, InterruptedException {
        request("sendMessage", Map.of("chat_id", chat, "text", text,
                "reply_markup", Map.of("force_reply", true, "input_field_placeholder", placeholder)));
    }

    byte[] voice(String fileId) throws IOException, InterruptedException {
        return download(fileId, MAX_VOICE_BYTES);
    }

    byte[] image(String fileId) throws IOException, InterruptedException {
        return download(fileId, MAX_IMAGE_BYTES);
    }

    private byte[] download(String fileId, int limit) throws IOException, InterruptedException {
        var file = request("getFile", Map.of("file_id", fileId));
        String path = file.path("file_path").asText();
        if (!path.matches("[A-Za-z0-9_./-]+") || path.contains("..") || path.startsWith("/")) {
            throw new IOException("Некорректный путь к вложению");
        }
        var download = HttpRequest.newBuilder(URI.create(fileBase + path)).timeout(Duration.ofSeconds(20)).GET().build();
        var response = client.send(download, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Telegram не отдал вложение");
            }
            byte[] data = stream.readNBytes(limit + 1);
            if (data.length == 0 || data.length > limit) {
                throw new IOException("Размер вложения превышает лимит");
            }
            return data;
        }
    }

    /** Told about every sent message (id, text), so a later reaction can be matched to what it was put on. */
    private volatile java.util.function.BiConsumer<Long, String> sentListener = (id, text) -> {};

    void onSent(java.util.function.BiConsumer<Long, String> listener) {
        this.sentListener = listener;
    }

    synchronized long send(long chat, String text, Button... buttons) throws IOException, InterruptedException {
        return sendText(chat, new TelegramText(text, List.of()), buttons);
    }

    synchronized long sendFormatted(long chat, String text, Button... buttons) throws IOException, InterruptedException {
        return sendText(chat, TelegramText.format(text), buttons);
    }

    private long sendText(long chat, TelegramText message, Button... buttons) throws IOException, InterruptedException {
        String text = message.text();
        int start = 0;
        while (text.length() - start > MESSAGE_LIMIT) {
            int end = start + MESSAGE_LIMIT;
            if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            int newline = text.lastIndexOf('\n', end - 1);
            if (newline >= end - 500) {
                end = newline + 1;
            }
            sendPart(chat, text.substring(start, end), message.entitiesIn(start, end));
            start = end;
        }
        return sendPart(chat, text.substring(start), message.entitiesIn(start, text.length()), buttons);
    }

    private long sendPart(long chat, String text, List<TelegramText.Entity> entities, Button... buttons) throws IOException, InterruptedException {
        var body = new java.util.HashMap<String, Object>();
        body.put("chat_id", chat);
        body.put("text", text);
        if (!entities.isEmpty()) body.put("entities", entities);
        if (buttons.length > 0) {
            body.put("reply_markup", Map.of("inline_keyboard", java.util.stream.IntStream.range(0, (buttons.length + 1) / 2)
                    .mapToObj(row -> List.of(java.util.Arrays.copyOfRange(buttons, row * 2, Math.min(row * 2 + 2, buttons.length))))
                    .toList()));
        }
        long id = request("sendMessage", body).path("message_id").asLong();
        try {
            sentListener.accept(id, text);
        } catch (RuntimeException ignored) {
            // Remembering the text is a nicety for reactions; it must never break sending.
        }
        return id;
    }

    void document(long chat, Path path) throws IOException, InterruptedException {
        String boundary = UUID.randomUUID().toString();
        String begin = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                + chat + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\""
                + path.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_")
                + "\"\r\nContent-Type: " + (path.toString().endsWith(".pdf") ? "application/pdf" : "text/plain")
                + "\r\n\r\n";
        request("sendDocument", "multipart/form-data; boundary=" + boundary, HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(begin), HttpRequest.BodyPublishers.ofFile(path),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")));
    }

    private JsonNode request(String method, Object body) throws IOException, InterruptedException {
        return request(method, "application/json", HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }

    private JsonNode request(String method, String contentType, HttpRequest.BodyPublisher body)
            throws IOException, InterruptedException {
        return request(method, contentType, body, Duration.ofSeconds(32));
    }

    private JsonNode request(String method, String contentType, HttpRequest.BodyPublisher body, Duration timeout)
            throws IOException, InterruptedException {
        try {
            var request = HttpRequest.newBuilder(URI.create(base + method)).timeout(timeout)
                    .header("Content-Type", contentType).POST(body).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() != 200 || !result.path("ok").asBoolean()) {
                throw new IOException("Telegram " + method + ": HTTP " + response.statusCode());
            }
            return result.path("result");
        } catch (IllegalArgumentException failure) {
            throw new IOException("Некорректная конфигурация Telegram");
        }
    }
}
