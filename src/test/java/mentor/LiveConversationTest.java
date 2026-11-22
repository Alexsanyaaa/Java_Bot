package mentor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual live check: the real bot logic against the real models, with a fake Telegram. Runs only when
 * MENTOR_LIVE_TRANSCRIPT names an output file; model settings come from the usual LLM_* / GROQ_* variables.
 */
@EnabledIfEnvironmentVariable(named = "MENTOR_LIVE_TRANSCRIPT", matches = ".+")
class LiveConversationTest {
    private static final List<String> SCRIPT = List.of(
            "Так, бро. Я хочу тебя потестить, давай пообщаемся. От этого зависит твоё будущее: либо оставлю, либо удалю нахуй",
            "Да ебать ты терпила",
            "Почему тебе вообще дали такое уебанское название?",
            "Расскажи анекдот, только смешной",
            "Задание то скинь",
            "Я многопоточку вообще не знаю. Объясни нормально, что такое поток",
            "Заебал, позже",
            "Ну и как сам?",
            "count++ это три операции: прочитать, прибавить, записать. Два потока оба читают 0 и оба пишут 1, одно обновление теряется. Чинится AtomicInteger.incrementAndGet или synchronized",
            "\"LeetCode 33 теперь officially stale code.\" чего блять",
            "🏫 Школа 21",
            "Что мне сейчас делать по проекту и успеваю ли я?",
            "Подключил PostgreSQL через application.properties, приложение стартует, в логах HikariPool Start completed",
            "📚 Собес",
            "Ты как вообще, не устал от меня?");

    @Test
    void conversation() throws Exception {
        var json = JsonMapper.builder().build();
        var updates = new CopyOnWriteArrayList<Map<String, Object>>();
        var sent = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/botfake/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (exchange.getRequestURI().getPath().endsWith("/getUpdates")) {
                long offset = request.path("offset").asLong();
                respond(exchange, json.writeValueAsString(Map.of("ok", true, "result", updates.stream()
                        .filter(item -> (long) item.get("update_id") >= offset).toList())));
                return;
            }
            if (exchange.getRequestURI().getPath().endsWith("/sendMessage")) sent.add(request);
            respond(exchange, "{\"ok\":true,\"result\":{\"message_id\":" + (500 + sent.size()) + "}}");
        });
        server.start();
        var transcript = new StringBuilder();
        try {
            Instant now = Instant.parse("2026-11-19T17:00:00Z"); // 20:00 Moscow
            var store = new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json);
            var planner = new Planner();
            store.write(state -> {
                planner.resetStudy(state, Planner.date(state, now), now);
                state.projects.add(new MentorState.Project(LearningCatalog.T04B, "Крестики-нолики T04B",
                        LearningCatalog.T04B_DESCRIPTION, "", LocalDate.of(2026, 12, 14), false, 2));
                var lesson = state.lessons.getFirst();
                lesson.tasks.set(0, LearningCatalog.theoryForTopic("atomicity"));
                lesson.tasks.set(2, LearningCatalog.interviewQuestion("atomicity"));
                lesson.tasks.set(3, LearningCatalog.dailyPractice("atomicity"));
                state.recallDay = Planner.date(state, now);
                state.ownerChat = 123;
                return null;
            });
            var model = new MentorModel(json, env("LLM_BASE_URL", "https://openrouter.ai/api/v1"), env("LLM_MODEL", ""),
                    env("LLM_API_KEY", ""));
            ReflectionTestUtils.setField(model, "groqKey", env("GROQ_API_KEY", ""));
            ReflectionTestUtils.setField(model, "geminiKey", env("GEMINI_API_KEY", ""));
            ReflectionTestUtils.setField(model, "fallbackModels", env("LLM_FALLBACK_MODELS", ""));
            ReflectionTestUtils.setField(model, "reviewModels", env("LLM_REVIEW_MODELS", ""));
            ReflectionTestUtils.setField(model, "routerModels", env("LLM_ROUTER_MODELS", ""));
            var bot = new MentorBot(store, planner, new TelegramApi(json, "http://localhost:" + server.getAddress().getPort(),
                    "fake", 123), model, Clock.fixed(now, ZoneOffset.UTC), "", "", "");
            long id = 1;
            long pause = Long.parseLong(env("MENTOR_LIVE_PAUSE", "0"));
            String only = env("MENTOR_LIVE_LINES", ""); // optional 1-based line numbers, e.g. "5,6"
            String scriptFile = env("MENTOR_LIVE_SCRIPT", ""); // optional file with one message per line
            List<String> script = scriptFile.isBlank() ? SCRIPT
                    : Files.readAllLines(Path.of(scriptFile)).stream().filter(item -> !item.isBlank()).toList();
            store.write(state -> {
                state.solved.addAll(List.of(1, 20, 121, 49, 704, 347, 217, 11, 33, 53, 56, 98, 102, 198, 238, 242, 125, 206));
                return null;
            });
            for (int index = 0; index < script.size(); index++) {
                String line = script.get(index);
                if (!only.isBlank() && !List.of(only.split(",")).contains(String.valueOf(index + 1))) continue;
                if (id > 1) Thread.sleep(pause * 1000);
                int before = sent.size();
                updates.add(Map.of("update_id", id++, "message", Map.of("from", Map.of("id", 123),
                        "chat", Map.of("type", "private", "id", 123), "text", line)));
                long started = System.nanoTime();
                bot.poll();
                long seconds = (System.nanoTime() - started) / 1_000_000_000L;
                transcript.append("\n>>> ").append(line).append("   [").append(seconds).append(" s]\n");
                for (JsonNode reply : sent.subList(before, sent.size())) {
                    transcript.append("<<< ").append(reply.path("text").asText()).append('\n');
                }
                var lesson = store.read(state -> state.lessons.getFirst());
                transcript.append("    [урок ").append(lesson.accepted).append("/4, T04B ")
                        .append(store.read(state -> state.projects.getFirst().completedSteps()).intValue()).append("/17, режим ")
                        .append(store.read(state -> state.schoolMode).booleanValue() ? "школа" : "собес").append("]\n");
                Files.writeString(Path.of(System.getenv("MENTOR_LIVE_TRANSCRIPT")), transcript);
            }
        } finally {
            server.stop(0);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.replaceAll("^'|'$", "");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
