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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 2026-11-24: «шаги можно выполнять независимо друг от друга, а не от 1 к 4». */
class IndependentStepsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final Instant EVENING = Instant.parse("2026-11-24T17:00:00Z"); // 20:00 Moscow

    @Test
    void legacySequentialLessonsKeepTheirProgress() {
        var lesson = new MentorState.Lesson();
        lesson.tasks = new java.util.ArrayList<>(List.of(task("Теория"), task("Алгоритмы"), task("Собеседование"), task("Практика")));
        lesson.position = 2;
        lesson.accepted = 2;
        assertThat(lesson.openSteps()).containsExactly(2, 3);
        assertThat(lesson.done).containsExactly(0, 1);
        assertThat(lesson.stepDone(1)).isTrue();
        assertThat(lesson.stepDone(2)).isFalse();
    }

    @Test
    void stepWishesAreRecognised() {
        for (String text : List.of("шаг 3", "давай шаг 3", "3 шаг", "3-й шаг", "перейду к шагу 3", "го на шаг 3", "Шаг 3!"))
            assertThat(MentorBot.STEP_WISH.matcher(text.toLowerCase()).matches()).as(text).isTrue();
        for (String text : List.of("на шаге 3 я застрял, помоги", "шаг 3 какой-то мутный", "сделал 3 задачи"))
            assertThat(MentorBot.STEP_WISH.matcher(text).matches()).as(text).isFalse();
    }

    @Test
    void anyStepCanBeTakenAndCreditedOutOfOrder() throws Exception {
        try (var f = new Fixture()) {
            // Switch to step 3 by plain text: the lesson shows step 3 and offers the others.
            f.message(1, "давай шаг 3");
            f.bot.poll();
            assertThat(f.lesson(lesson -> lesson.position).intValue()).isEqualTo(2);
            String shown = f.sent.getLast().path("text").asText();
            assertThat(shown).contains("шаг 3 из 4", "Шаги можно делать в любом порядке", "1 «Теория»", "2 «Алгоритмы»", "4 «Практика»");
            assertThat(f.sent.getLast().path("reply_markup").toString()).contains("→ Шаг 1", "step 1", "→ Шаг 4");

            // An answer to step 3 while step 1 is untouched: step 3 is credited, steps 1, 2 and 4 stay open.
            f.review.set("{\"steps\":[{\"step\":3,\"status\":\"accepted\",\"feedback\":\"Верно.\",\"evidenceLine\":1},"
                    + "{\"step\":1,\"status\":\"missing\",\"feedback\":\"\"}]}");
            f.message(2, "Почему одинаковый hashCode не значит равенство: коллизии, equals решает");
            f.bot.poll();
            assertThat(f.<List<Integer>>lesson(lesson -> List.copyOf(lesson.done))).containsExactly(2);
            assertThat(f.lesson(lesson -> lesson.accepted).intValue()).isEqualTo(1);
            assertThat(f.<List<Integer>>lesson(lesson -> lesson.openSteps())).containsExactly(3, 0, 1);
            assertThat(f.sent.stream().map(item -> item.path("text").asText()).filter(text -> text.contains("Засчитано. Шаг 3")))
                    .hasSize(1);

            // One message may close two steps at once, in any order.
            f.review.set("{\"steps\":[{\"step\":4,\"status\":\"accepted\",\"feedback\":\"Ок.\",\"evidenceLine\":1},"
                    + "{\"step\":1,\"status\":\"accepted\",\"feedback\":\"Ок.\",\"evidenceLine\":2}]}");
            f.message(3, "Практика: вот код\nТеория: вот ответ");
            f.bot.poll();
            assertThat(f.<List<Integer>>lesson(lesson -> List.copyOf(lesson.done))).containsExactlyInAnyOrder(2, 3, 0);
            assertThat(f.lesson(lesson -> lesson.position).intValue()).isEqualTo(1); // only step 2 is left
            assertThat(MentorContext.snapshot(f.store.read(s -> s), "скинь задание", EVENING))
                    .contains("ТЕКУЩИЙ ШАГ, его и давай на «скинь задание»: урок #1, шаг 2 из 4");

            // A done step cannot be reopened by switching to it.
            f.message(4, "шаг 3");
            f.bot.poll();
            assertThat(f.sent.getLast().path("text").asText()).contains("Шаг 3 уже зачтён", "Открытые: 2 «Алгоритмы»");
        }
    }

    private static MentorState.Task task(String type) {
        return new MentorState.Task(type, "collections", 30, type + " задание", "критерий", "", null, null);
    }

    private static class Fixture implements AutoCloseable {
        final JsonMapper json = JsonMapper.builder().build();
        final List<Map<String, Object>> updates = new CopyOnWriteArrayList<>();
        final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        final AtomicReference<String> review = new AtomicReference<>("{\"steps\":[]}");
        final HttpServer server;
        final StateStore store;
        final MentorBot bot;

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
                    String system = body.path("messages").path(0).path("content").asText();
                    String answer = system.contains("классификатор") ? "{\"intent\":\"answer\"}"
                            : system.contains("Ты проверяешь сдачу ученика по шагам") ? review.get() : "Олег пересказал вердикт.";
                    response = Map.of("choices", List.of(Map.of("message", Map.of("content", answer))));
                } else {
                    if (path.endsWith("/sendMessage")) sent.add(body);
                    response = Map.of("ok", true, "result", Map.of("message_id", 900 + sent.size()));
                }
                byte[] bytes = json.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            });
            server.start();
            String base = "http://localhost:" + server.getAddress().getPort();
            store = new StateStore(new Database("jdbc:h2:mem:" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", ""), json);
            var planner = new Planner();
            store.write(state -> {
                planner.resetStudy(state, DAY, EVENING);
                state.recallDay = DAY; // no question of the day in this scenario
                state.lessons.getFirst().started = true;
                return null;
            });
            bot = new MentorBot(store, planner, new TelegramApi(json, base, "fake", 123),
                    new MentorModel(json, base + "/v1", "test-model", ""), Clock.fixed(EVENING, ZoneOffset.UTC), "", "", "");
        }

        <T> T lesson(java.util.function.Function<MentorState.Lesson, T> read) {
            return store.read(state -> read.apply(state.lessons.getFirst()));
        }

        void message(long update, String text) {
            updates.add(Map.of("update_id", update, "message", Map.of("message_id", 100 + update, "from", Map.of("id", 123),
                    "chat", Map.of("id", 123, "type", "private"), "text", text, "date", EVENING.getEpochSecond() - 1)));
        }

        public void close() { server.stop(0); }
    }
}
