package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TelegramReactionsTest {
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    @Test
    void reactsToTheIncomingMessageWithoutAnotherModelCallAndRemembersCooldown() throws Exception {
        try (var f = new Fixture()) {
            f.output.set("[[react:🔥]] Вот это темп, продолжай.");
            f.message(1, 501, "Сегодня хорошо поработал", Map.of("reply_to_message", Map.of("message_id", 999)));
            f.bot.poll();
            assertThat(f.reactions).hasSize(1);
            var reaction = f.reactions.getFirst();
            assertThat(reaction.path("chat_id").asLong()).isEqualTo(123);
            assertThat(reaction.path("message_id").asLong()).isEqualTo(501); // not the message being replied to
            assertThat(reaction.path("reaction").size()).isEqualTo(1);
            assertThat(reaction.path("reaction").path(0).path("type").asText()).isEqualTo("emoji");
            assertThat(reaction.path("reaction").path(0).path("emoji").asText()).isEqualTo("🔥");
            assertThat(reaction.path("is_big").asBoolean()).isFalse();
            assertThat(f.sent.getLast().path("text").asText()).isEqualTo("Вот это темп, продолжай.");
            assertThat(f.store.<String>read(s -> s.conversation.getLast().answer())).doesNotContain("react:");
            assertThat(f.modelCalls).hasSize(1);
            assertThat(f.store.<Instant>read(s -> s.lastReactionAt)).isEqualTo(NOW);

            f.restart(NOW.plusSeconds(30));
            f.message(2, 502, "И ещё немного", Map.of());
            f.bot.poll();
            assertThat(f.reactions).hasSize(1); // persisted across a new bot and StateStore
            f.restart(NOW.plusSeconds(121));
            f.message(3, 503, "А теперь ещё", Map.of());
            f.bot.poll();
            assertThat(f.reactions).hasSize(2);
            assertThat(f.reactions.getLast().path("message_id").asLong()).isEqualTo(503);
            assertThat(f.modelCalls).hasSize(3);
        }
    }

    @Test
    void missingOrUnsupportedReactionsStayOptionalAndFailuresNeverLoseTheReply() throws Exception {
        try (var f = new Fixture()) {
            f.output.set("Просто поговорим.");
            f.message(1, 101, "Ну как ты?", Map.of());
            f.bot.poll();
            assertThat(f.reactions).isEmpty();
            f.output.set("[[react:💸]] Продолжаем.");
            f.message(2, 102, "Ещё вопрос", Map.of());
            f.bot.poll();
            assertThat(f.reactions).isEmpty();
            assertThat(f.sent.getLast().path("text").asText()).isEqualTo("Продолжаем.");

            f.rejectReaction.set(true);
            f.output.set("[[react:👍]] Я здесь.");
            f.message(3, 103, "Ты на месте?", Map.of());
            f.bot.poll();
            assertThat(f.sent.getLast().path("text").asText()).isEqualTo("Я здесь.");
            assertThat(f.store.<Instant>read(s -> s.lastReactionAt)).isNull();
            assertThat(f.store.read(s -> s.updateOffset).longValue()).isEqualTo(4);
            f.rejectReaction.set(false);
            f.message(4, 104, "Хорошо", Map.of());
            f.bot.poll();
            assertThat(f.reactions).hasSize(2);
            assertThat(f.store.<Instant>read(s -> s.lastReactionAt)).isEqualTo(NOW);
            f.bot.poll();
            assertThat(f.reactions).hasSize(2); // no retry of already handled messages
        }
    }

    @Test
    void schoolChatAlsoReactsAndJsonReportsParseReactionWithoutLeakingMetadata() throws Exception {
        try (var f = new Fixture()) {
            f.store.write(s -> {
                s.projects.add(new MentorState.Project(LearningCatalog.T04B, "T04B", "", "",
                        LocalDate.of(2026, 12, 14), false, 0));
                s.schoolMode = true;
                s.schoolModeDay = Planner.date(s, NOW);
                return null;
            });
            f.output.set("[[react:🤝]] Давай разберём следующий вопрос.");
            f.message(1, 201, "Как дела?", Map.of());
            f.bot.poll();
            assertThat(f.reactions).hasSize(1);
            assertThat(f.reactions.getFirst().path("message_id").asLong()).isEqualTo(201);
            f.output.set("{\"projectReport\":true,\"assessment\":\"adequate\",\"answer\":\"Хороший результат.\",\"reaction\":\"👏\"}");
            var result = f.model.talk("Настроил БД", List.of(), "заметки", "Проверка проекта", true);
            assertThat(result.projectReport()).isTrue();
            assertThat(result.reaction()).isEqualTo("👏");
            assertThat(result.answer()).isEqualTo("Хороший результат.");
        }
    }

    @Test
    void buttonsAndForeignMessagesNeverReceiveReactions() throws Exception {
        try (var f = new Fixture()) {
            f.output.set("[[react:🔥]] Привет.");
            f.updates.add(Map.of("update_id", 1L, "message", Map.of("message_id", 1,
                    "from", Map.of("id", 999), "chat", Map.of("id", 999, "type", "private"), "text", "Привет")));
            f.updates.add(Map.of("update_id", 2L, "callback_query", Map.of("id", "test", "data", "menu",
                    "from", Map.of("id", 123), "message", Map.of("message_id", 2,
                            "chat", Map.of("id", 123, "type", "private")))));
            f.bot.poll();
            assertThat(f.reactions).isEmpty();
            assertThat(f.modelCalls).isEmpty();
        }
    }

    /** 2026-11-24: «пусть видит мои реакции и может на них реагировать». */
    @Test
    void olegSeesTheOwnersReactionsAndMayAnswerOrStaySilent() throws Exception {
        try (var f = new Fixture()) {
            f.output.set("Ну давай, спроси что-нибудь про HashMap.");
            f.message(1, 101, "Привет", Map.of());
            f.bot.poll();
            long olegMessage = f.store.read(s -> s.sentMessages.keySet().iterator().next());
            assertThat(f.store.<String>read(s -> s.sentMessages.get(olegMessage))).contains("HashMap");

            // 🤡 on Oleg's message: the model is told what it was put on and answers.
            f.output.set("Клоуна мне? Сам ты цирк, марш учить equals.");
            f.reaction(2, olegMessage, List.of("🤡"), List.of());
            f.bot.poll();
            String told = f.modelCalls.getLast().path("messages").toString();
            assertThat(told).contains("реакция: ученик поставил 🤡", "спроси что-нибудь про HashMap", "[[молчу]]");
            assertThat(f.sent.getLast().path("text").asText()).isEqualTo("Клоуна мне? Сам ты цирк, марш учить equals.");
            assertThat(f.store.<String>read(s -> s.conversation.getLast().question())).startsWith("(реакция 🤡 на сообщение «");

            // Within 90 seconds another reaction is only remembered, no model call and no reply.
            int calls = f.modelCalls.size();
            int sentBefore = f.sent.size();
            f.reaction(3, olegMessage, List.of("🤡", "🔥"), List.of("🤡"));
            f.bot.poll();
            assertThat(f.modelCalls).hasSize(calls);
            assertThat(f.sent).hasSize(sentBefore);
            assertThat(f.store.<String>read(s -> s.conversation.getLast().question())).startsWith("(реакция 🔥");

            // Later: the model may choose silence.
            f.restart(NOW.plusSeconds(200));
            f.output.set("[[молчу]]");
            f.reaction(4, olegMessage, List.of("👍"), List.of());
            f.bot.poll();
            assertThat(f.modelCalls).hasSize(calls + 1);
            assertThat(f.sent).hasSize(sentBefore);

            // A removed reaction and a stranger's reaction are ignored.
            f.reaction(5, olegMessage, List.of(), List.of("👍"));
            f.updates.add(Map.of("update_id", 6L, "message_reaction", Map.of("chat", Map.of("id", 999, "type", "private"),
                    "message_id", olegMessage, "user", Map.of("id", 999), "date", 1,
                    "old_reaction", List.of(), "new_reaction", List.of(Map.of("type", "emoji", "emoji", "🔥")))));
            f.bot.poll();
            assertThat(f.modelCalls).hasSize(calls + 1);
            assertThat(f.sent).hasSize(sentBefore);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MENTOR_LIVE_REACTIONS", matches = "1")
    void liveModelChoosesReactionAlongsideItsAnswer() throws Exception {
        var json = JsonMapper.builder().build();
        var model = new MentorModel(json, System.getenv("LLM_BASE_URL"), System.getenv("LLM_MODEL"),
                System.getenv().getOrDefault("LLM_API_KEY", ""));
        ReflectionTestUtils.setField(model, "geminiKey", System.getenv("GEMINI_API_KEY"));
        var reply = model.talk("Я наконец-то сам починил гонку: воспроизвёл её тестом, добавил synchronized, "
                + "тест теперь стабильно проходит. Можешь отреагировать на сообщение подходящей реакцией?",
                List.of(), "Обычный разговор. Зачёт заданий не меняется.", "", false);
        assertThat(reply.answer()).isNotBlank().doesNotContain("[[react:");
        assertThat(reply.reaction()).isIn(MentorModel.REACTION_EMOJI);
        System.out.println("Выбор модели: " + reply.reaction() + " Ответ: " + reply.answer());
    }

    private static class Fixture implements AutoCloseable {
        final JsonMapper json = JsonMapper.builder().build();
        final List<Map<String, Object>> updates = new CopyOnWriteArrayList<>();
        final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        final List<JsonNode> reactions = new CopyOnWriteArrayList<>();
        final List<JsonNode> modelCalls = new CopyOnWriteArrayList<>();
        final AtomicReference<String> output = new AtomicReference<>("Привет.");
        final AtomicBoolean rejectReaction = new AtomicBoolean();
        final HttpServer server;
        final JdbcTemplate jdbc;
        final MentorModel model;
        final TelegramApi telegram;
        StateStore store;
        MentorBot bot;

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                JsonNode body = json.readTree(exchange.getRequestBody());
                String path = exchange.getRequestURI().getPath();
                Object response;
                if (path.endsWith("/getUpdates")) {
                    response = Map.of("ok", true, "result", updates.stream()
                            .filter(u -> (long) u.get("update_id") >= body.path("offset").asLong()).toList());
                } else if (path.endsWith("/chat/completions")) {
                    modelCalls.add(body);
                    String system = body.path("messages").path(0).path("content").asText();
                    String answer = system.contains("классификатор") ? "{\"intent\":\"chat\"}" : output.get();
                    response = Map.of("choices", List.of(Map.of("message", Map.of("content", answer))));
                } else {
                    if (path.endsWith("/sendMessage")) sent.add(body);
                    if (path.endsWith("/setMessageReaction")) reactions.add(body);
                    response = Map.of("ok", !path.endsWith("/setMessageReaction") || !rejectReaction.get(),
                            "result", Map.of("message_id", 900 + sent.size()));
                }
                byte[] bytes = json.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            });
            server.start();
            String base = "http://localhost:" + server.getAddress().getPort();
            jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", ""));
            model = new MentorModel(json, base + "/v1", "test-model", "");
            telegram = new TelegramApi(json, base, "fake", 123);
            restart(NOW);
        }

        void restart(Instant now) {
            store = new StateStore(jdbc, json);
            bot = new MentorBot(store, new Planner(), telegram, model, Clock.fixed(now, ZoneOffset.UTC), "", "", "");
        }

        void message(long update, long messageId, String text, Map<String, Object> extra) {
            var message = new java.util.HashMap<String, Object>(extra);
            message.putAll(Map.of("message_id", messageId, "from", Map.of("id", 123),
                    "chat", Map.of("id", 123, "type", "private"), "text", text));
            updates.add(Map.of("update_id", update, "message", message));
        }

        void reaction(long update, long messageId, List<String> now, List<String> before) {
            updates.add(Map.of("update_id", update, "message_reaction", Map.of(
                    "chat", Map.of("id", 123, "type", "private"), "message_id", messageId, "user", Map.of("id", 123),
                    "date", 1, "old_reaction", before.stream().map(e -> Map.of("type", "emoji", "emoji", e)).toList(),
                    "new_reaction", now.stream().map(e -> Map.of("type", "emoji", "emoji", e)).toList())));
        }

        public void close() { server.stop(0); }
    }
}
