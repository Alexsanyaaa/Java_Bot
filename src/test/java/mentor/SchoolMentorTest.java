package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One bot, two mentors: daily interview prep and School 21 (T04B) behind the «🏫 Школа 21» button. */
class SchoolMentorTest {
    private static final Instant EVENING = Instant.parse("2026-11-19T18:00:00Z"); // 21:00 Moscow
    private static final MentorState.Project T04B = new MentorState.Project(LearningCatalog.T04B, "Крестики-нолики T04B",
            LearningCatalog.T04B_DESCRIPTION, "", LocalDate.of(2026, 12, 14), false, 2);

    @Test
    void dailyLessonsHavePracticeAndSchoolModeTracksTheDeadline() {
        var state = new MentorState();
        var planner = new Planner();
        planner.resetStudy(state, Planner.date(state, EVENING), EVENING);
        state.projects.add(T04B);
        planner.refreshProjects(state, EVENING);
        assertThat(state.lessons).allSatisfy(lesson -> {
            assertThat(lesson.tasks.getLast().type()).isEqualTo("Практика");
            assertThat(lesson.tasks.getLast().prompt()).contains("Пришли:");
            assertThat(lesson.tasks).allMatch(task -> task.projectId() == null);
        });

        var bot = new MentorBot(new StateStore(jdbc(), JsonMapper.builder().build()), planner,
                new TelegramApi(JsonMapper.builder().build(), "http://127.0.0.1:1", "", 0),
                new MentorModel(JsonMapper.builder().build(), "http://127.0.0.1:1", "", ""),
                Clock.fixed(EVENING, ZoneOffset.UTC), "", "", "");
        var school = bot.respond(state, "/school", "", EVENING);
        assertThat(state.schoolMode).isTrue();
        assertThat(school.text()).contains("Сделано 2 из 17", "До дедлайна (2026-12-14) — 25 дн.", "Шаг 3:", "PostgreSQL",
                "Нужный темп: шаг каждые 2 дня.").doesNotContain("..");
        assertThat(school.buttons()).extracting(TelegramApi.Button::text).containsExactly("📚 Собес");

        // Even plan from the first visit: ten days later with no progress the student is behind.
        Instant later = EVENING.plusSeconds(10 * 86_400);
        String nudge = SchoolMentor.nudge(state, later);
        assertThat(nudge).contains("отстаёшь", "из 17");
        state.schoolNudgeDay = Planner.date(state, later);
        assertThat(SchoolMentor.nudge(state, later)).isNull();
        assertThat(SchoolMentor.nudge(state, EVENING.minusSeconds(4 * 3600))).isNull(); // 17:00, too early

        bot.respond(state, "/daily", "", EVENING);
        assertThat(state.schoolMode).isFalse();
    }

    @Test
    void schoolModeChatsAboutT04BAndCreditsReportedSteps() throws Exception {
        var json = JsonMapper.builder().build();
        var updates = new CopyOnWriteArrayList<Map<String, Object>>();
        var sent = new CopyOnWriteArrayList<JsonNode>();
        var calls = new CopyOnWriteArrayList<JsonNode>();
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
            respond(exchange, "{\"ok\":true,\"result\":{\"message_id\":1}}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            calls.add(request);
            String system = request.path("messages").path(0).path("content").asText();
            var messages = request.path("messages");
            String last = messages.get(messages.size() - 1).path("content").asText();
            String content = system.contains("классификатор")
                    ? "{\"intent\":\"" + (last.contains("Подключил PostgreSQL") ? "answer" : "question") + "\"}"
                    : system.contains("отчёт ученика о шаге")
                    ? "{\"accepted\":true,\"feedback\":\"Подключение подтверждено логом старта.\"}"
                    : "Хранилище в T03 — ConcurrentHashMap, его и меняем на PostgreSQL.";
            respond(exchange, json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            var store = new StateStore(new Database(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""), json);
            var planner = new Planner();
            store.write(state -> {
                planner.resetStudy(state, Planner.date(state, EVENING), EVENING);
                state.projects.add(T04B);
                state.recallDay = Planner.date(state, EVENING);
                return null;
            });
            var bot = new MentorBot(store, planner, new TelegramApi(json, base, "fake", 123),
                    new MentorModel(json, base + "/v1", "test-model", ""), Clock.fixed(EVENING, ZoneOffset.UTC), "", "", "");

            updates.add(message(1, "🏫 Школа 21"));
            updates.add(message(2, "А где в T03 хранились игры?"));
            bot.poll();
            assertThat(store.read(state -> state.schoolMode).booleanValue()).isTrue();
            JsonNode chat = calls.getLast();
            assertThat(chat.path("messages").path(0).path("content").asText())
                    .contains("школьный ментор", "Условие T04B", "ConcurrentHashMap", "Сделано 2 из 17")
                    .doesNotContain("Сейчас ты дневной ментор");
            assertThat(sent.getLast().path("text").asText()).contains("ConcurrentHashMap");

            updates.add(message(3, "Подключил PostgreSQL через application.properties, приложение стартует, в логе HikariPool connected"));
            bot.poll();
            assertThat(store.read(state -> state.projects.getFirst().completedSteps()).intValue()).isEqualTo(3);
            assertThat(sent.getLast().path("text").asText()).contains("Засчитано. Шаг 3 из 17", "Сделано 3 из 17", "Шаг 4:");
            assertThat(store.read(state -> state.lessons.getFirst().accepted).intValue()).isZero();

            updates.add(message(4, "📚 Собес"));
            bot.poll();
            assertThat(store.read(state -> state.schoolMode).booleanValue()).isFalse();
            assertThat(sent.getLast().path("text").asText()).startsWith("Вернулись к собесу.");
        } finally {
            server.stop(0);
        }
    }

    private static Database jdbc() {
        return new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static Map<String, Object> message(long id, String text) {
        return Map.of("update_id", id, "message", Map.of("from", Map.of("id", 123),
                "chat", Map.of("type", "private", "id", 123), "text", text));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
