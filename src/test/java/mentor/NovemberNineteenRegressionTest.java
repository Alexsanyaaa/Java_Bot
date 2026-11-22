package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What went wrong in the real chat on 2026-11-19. */
class NovemberNineteenRegressionTest {
    private static final Instant AFTERNOON = Instant.parse("2026-11-19T14:12:00Z"); // 17:12 Moscow

    @Test
    void chatCannotFakeAcceptanceOrALessonCard() {
        String fake = """
                Accepted. 0 ms / 100 % — на собесе так и скажешь: бинарный поиск по отсортированной половине.
                Ты только что сдал шаг 2 (Алгоритмы) — LeetCode 33 принят. Молодец.
                Собеседование · 30 мин · шаг 3 из 4
                Как сдашь — шаг 4 зачтём и перейдём к уроку #5.
                Рекурсия даёт O(log n) памяти на стек.""";
        String clean = MentorBot.withoutChatCredit(fake);
        assertThat(clean).doesNotContain("Accepted", "принят", "· 30 мин · шаг 3 из 4", "зачтём")
                .contains("Рекурсия даёт O(log n) памяти");
        assertThat(MentorBot.withoutChatCredit("LeetCode засчитал решение, потому что poll() удаляет минимум."))
                .contains("LeetCode засчитал решение");
    }

    @Test
    void giveMeATaskOffersUnsolvedProblemsAndInterviewHasThreeQuestions() {
        var state = new MentorState();
        state.solved.addAll(List.of(1, 20, 121, 49, 704, 347, 217, 11, 33, 53, 56));
        var unsolved = LearningCatalog.unsolvedProblems(state, 5);
        assertThat(unsolved).hasSize(5).noneMatch(item -> item.startsWith("1. ") || item.startsWith("20. "));
        assertThat(String.join("\n", unsolved)).contains("https://leetcode.com/problems/");
        new Planner().resetStudy(state, Planner.date(state, AFTERNOON), AFTERNOON);
        state.solved.addAll(List.of(1, 20));
        String notes = MentorContext.snapshot(state, "дай простую задачу с литкода", AFTERNOON);
        assertThat(notes).contains("Номера: 1, 11, 20", "нерешённые").doesNotContain("Two Sum —");

        state.skills.put("collections", new MentorState.Skill(true, Planner.date(state, AFTERNOON)));
        state.skills.put("interfaces", new MentorState.Skill(false, Planner.date(state, AFTERNOON)));
        var interview = LearningCatalog.interview(state, Planner.date(state, AFTERNOON), "atomicity");
        assertThat(interview.prompt()).startsWith("1) ").contains("\n2) ", "\n3) ");
        assertThat(LearningCatalog.card(interview)).startsWith("Мини-собес, три вопроса:");
    }

    @Test
    void warmUpProblemsAreMarkedSolvedFromChat() {
        var state = new MentorState();
        new Planner().resetStudy(state, Planner.date(state, AFTERNOON), AFTERNOON);
        assertThat(LearningCatalog.linkedProblem("держи: https://leetcode.com/problems/merge-two-sorted-lists/")).isEqualTo(21);
        state.lastOfferedProblem = 21;
        assertThat(MentorBot.solvedWarmup(state, "решил, accepted", null)).isEqualTo(21);
        assertThat(MentorBot.solvedWarmup(state, "Merge Two Sorted Lists готово", null)).isEqualTo(21);
        assertThat(MentorBot.solvedWarmup(state, "решил задачу 146", null)).isEqualTo(146);
        assertThat(MentorBot.solvedWarmup(state, "ну и как дела?", null)).isNull();
        state.solved.add(21);
        assertThat(MentorBot.solvedWarmup(state, "решил", null)).isNull(); // already marked
        // Code for the open lesson algorithm goes to the lesson review, not to the warm-up.
        var lesson = state.lessons.getFirst();
        lesson.position = 1;
        state.lastOfferedProblem = 206;
        assertThat(MentorBot.solvedWarmup(state, "```java\nreturn -1;\n```\nAccepted", lesson)).isNull();
        assertThat(MentorBot.solvedWarmup(state, "решил " + lesson.currentTask().algorithmId(), lesson)).isNull();
    }

    @Test
    void quotedBotTextIsNotAStatusQuestion() {
        var state = new MentorState();
        new Planner().resetStudy(state, Planner.date(state, AFTERNOON), AFTERNOON);
        assertThat(MentorContext.progressReply(state,
                "Ты еблан ? \"Ты нажал «Начинаю» 40 минут назад. Урок #1. Зачтено 1 из 4 шагов.\"", AFTERNOON)).isNull();
        assertThat(MentorContext.progressReply(state, "Урок #1 зачтён?", AFTERNOON)).contains("Урок #1");
    }

    @Test
    void codeIsNeverSwallowedAndRecallVerdictsCanBeAppealed() throws Exception {
        var json = JsonMapper.builder().build();
        var updates = new CopyOnWriteArrayList<Map<String, Object>>();
        var sent = new CopyOnWriteArrayList<JsonNode>();
        var chatCalls = new CopyOnWriteArrayList<JsonNode>();
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
            respond(exchange, "{\"ok\":true,\"result\":{\"message_id\":" + (700 + sent.size()) + "}}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            String system = request.path("messages").path(0).path("content").asText();
            var messages = request.path("messages");
            String last = messages.get(messages.size() - 1).path("content").asText();
            String content;
            if (system.contains("классификатор")) {
                String student = last.substring(last.lastIndexOf("Сообщение ученика:"));
                content = student.contains("hashCode") || student.contains("я же написал") ? "{\"intent\":\"recall\"}"
                        : "{\"intent\":\"answer\"}";
            } else if (system.contains("\"steps\"")) {
                var first = java.util.regex.Pattern.compile("Шаг (\\d+) \\(").matcher(last);
                content = "{\"steps\":[{\"step\":" + (first.find() ? first.group(1) : "1") + ",\"status\":\"missing\",\"feedback\":\"\"}]}";
            } else if (system.contains("вопрос с собеседования")) {
                content = last.contains("Уточнение ученика после вердикта")
                        ? "{\"accepted\":true,\"feedback\":\"С уточнением всё сходится.\"}"
                        : "{\"accepted\":false,\"feedback\":\"Пример кривой.\"}";
            } else {
                chatCalls.add(request);
                content = "Accepted. Шаг 2 принят, переходим к собесу.";
            }
            respond(exchange, json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            var store = new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json);
            var planner = new Planner();
            store.write(state -> {
                planner.resetStudy(state, Planner.date(state, AFTERNOON), AFTERNOON);
                var lesson = state.lessons.getFirst();
                lesson.started = true;
                lesson.position = 1;
                lesson.accepted = 1;
                state.recallDay = Planner.date(state, AFTERNOON);
                state.recallTopic = "collections";
                state.recallMessageId = 42;
                return null;
            });
            var bot = new MentorBot(store, planner, new TelegramApi(json, base, "fake", 123),
                    new MentorModel(json, base + "/v1", "test-model", ""), Clock.fixed(AFTERNOON, ZoneOffset.UTC), "", "", "");

            // A real code submission the reviewer misjudges as "missing": the verdict is shown, the chat never fakes it.
            updates.add(message(1, "```java\npublic int search(int[] nums, int target) {\n  return -1;\n}\n```\nВремя лог эН"));
            bot.poll();
            assertThat(chatCalls).isEmpty();
            assertThat(sent.getLast().path("text").asText()).contains("Пока нет ответа на шаг 2").doesNotContain("Accepted");
            assertThat(store.read(state -> state.lessons.getFirst().accepted).intValue()).isEqualTo(1);

            // Recall: first answer rejected, the student objects, the clarification is reviewed with the answer.
            updates.add(message(2, "Разные объекты могут иметь одинаковый hashCode, пример User: hash по id, equals по id и тфьу"));
            bot.poll();
            assertThat(sent.getLast().path("text").asText()).startsWith("Вопрос дня — не дотянул").contains("перепроверю");
            assertThat(store.read(state -> state.recallAnswered).booleanValue()).isFalse();
            updates.add(message(3, "Ты еблан, я же написал: hash на id, equals на id и name"));
            bot.poll();
            assertThat(sent.getLast().path("text").asText()).startsWith("Вопрос дня — засчитано");
            assertThat(store.read(state -> state.recallAnswered && state.recallAttempt.isEmpty()).booleanValue()).isTrue();
        } finally {
            server.stop(0);
        }
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
