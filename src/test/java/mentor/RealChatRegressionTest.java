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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Scenarios taken from the real conversation where the old bot got confused. */
class RealChatRegressionTest {
    private static final Instant EVENING = Instant.parse("2026-11-18T17:00:00Z"); // 20:00 in Moscow
    private static final String LEGACY_THEORY = "Исключения.\nПорядок: 25 минут на указанные разделы, затем 20 минут на задание."
            + "\nПосле чтения: Покажи, в каком порядке выполнятся try, catch и finally при ошибке.";
    private static final String MENTOR_TASK = "Задание есть — оно в конце блока теории:\n"
            + "> После чтения: Покажи, в каком порядке выполнятся try, catch и finally при ошибке.";

    @Test
    void understandsShortAnswersAndNeverClaimsCreditItself() throws Exception {
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
            respond(exchange, "{\"ok\":true,\"result\":{\"message_id\":" + (100 + sent.size()) + "}}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            calls.add(request);
            String system = request.path("messages").path(0).path("content").asText();
            var messages = request.path("messages");
            String last = messages.get(messages.size() - 1).path("content").asText();
            int marker = last.lastIndexOf("Сообщение ученика:");
            String user = (marker < 0 ? last : last.substring(marker + "Сообщение ученика:".length())).strip();
            String content;
            if (system.contains("классификатор")) {
                if (user.startsWith("сломай")) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                content = "{\"intent\":\"" + (user.startsWith("в таком") || user.contains("officially") ? "answer"
                        : user.startsWith("Задание") || user.startsWith("вот код") || user.contains("всё ок?") ? "question"
                        : "chat") + "\"}";
            } else if (system.contains("Сейчас особая задача")) {
                content = "Надо же, порядок выучил. На собесе добьют вопросом, что будет с return внутри finally.";
            } else if (system.contains("\"steps\"") && last.contains("officially")) {
                var first = java.util.regex.Pattern.compile("Шаг (\\d+) \\(").matcher(last);
                content = "{\"steps\":[{\"step\":" + (first.find() ? first.group(1) : "1")
                        + ",\"status\":\"missing\",\"feedback\":\"\"}]}";
            } else if (system.contains("\"steps\"")) {
                content = """
                        {"steps":[{"step":1,"status":"accepted","feedback":"Да, try до исключения, потом catch, потом finally.","evidenceLine":1},
                        {"step":2,"status":"missing","feedback":""}]}
                        """;
            } else if (user.contains("всё ок?")) {
                content = "Урок №2 полностью пройден, 4/4. Счётчик обновится на следующем пуллинге. "
                        + "Interface.super.method() вызывает default-реализацию конкретного интерфейса.";
            } else if (user.contains("корейский")) {
                content = "Сорри, 알아 залетело лишнее слово. Checked проверяет компилятор, 正式 unchecked — нет.";
            } else {
                content = "Задание: прочитай раздел и пришли порядок try, catch и finally при ошибке.";
            }
            respond(exchange, json.writeValueAsString(Map.of("model", "fake-model",
                    "choices", List.of(Map.of("message", Map.of("content", content))))));
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            var store = new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json);
            var planner = new Planner();
            store.write(state -> {
                planner.resetStudy(state, Planner.date(state, EVENING), EVENING);
                var lesson = state.lessons.getFirst();
                var theory = lesson.tasks.getFirst();
                lesson.tasks.set(0, new MentorState.Task("Теория", "exceptions", 45, LEGACY_THEORY, "",
                        theory.resource(), null, null));
                state.recallDay = Planner.date(state, EVENING); // no recall question in this scenario
                state.curriculumVersion = 4; // keep the old card text from the real conversation
                DayMemory.remember(state, EVENING.minusSeconds(60), "ты прислал только теорию но не задание", MENTOR_TASK, null);
                return null;
            });
            var model = new MentorModel(json, base + "/v1", "test-model", "");
            var bot = new MentorBot(store, planner, new TelegramApi(json, base, "fake", 123), model,
                    Clock.fixed(EVENING, ZoneOffset.UTC), "", "", "");

            // "Скинь задание" is a question: the chat answers with the task from the database, nothing is submitted.
            updates.add(message(1, "Задание то скинь, долбаеб"));
            bot.poll();
            JsonNode chat = calls.getLast();
            assertThat(chat.path("messages").path(0).path("content").asText())
                    .contains("Покажи, в каком порядке выполнятся try, catch и finally");
            assertThat(lastContent(chat)).isEqualTo("Задание то скинь, долбаеб"); // the user turn is only the student's words
            assertThat(store.read(state -> state.lessons.getFirst().pending == null && state.lessons.getFirst().accepted == 0).booleanValue())
                    .isTrue();

            // A short slangy answer is understood in context, submitted without pressing «Начать» and reviewed at once.
            updates.add(message(2, "в таком блять и выполняется"));
            bot.poll();
            int reviewAt = calls.size() - 1;
            while (!calls.get(reviewAt).path("messages").path(0).path("content").asText().contains("\"steps\"")) reviewAt--;
            JsonNode review = calls.get(reviewAt);
            // After the review Oleg retells the verdict; the verdict line itself is the bot's.
            assertThat(calls.getLast().path("messages").path(0).path("content").asText()).contains("ЗАЧТЕНО");
            assertThat(lastContent(review)).contains("Последняя реплика ментора перед сдачей",
                    "пришли порядок try, catch и finally", "«в таком порядке»", "(сообщение из чата) Задание то скинь")
                    .containsPattern("Последняя сдача ученика:\\n\\[\\d+] в таком блять и выполняется");
            assertThat(lastContent(calls.get(reviewAt - 1))).contains("Открытые шаги урока", "- Алгоритмы:");
            assertThat(texts(sent)).noneMatch(text -> text.startsWith("Принял как ответ"));
            assertThat(sent.getLast().path("text").asText()).contains("Засчитано. Шаг 1 из 4", "Урок #1: зачтено 1 из 4",
                    "Надо же, порядок выучил");
            assertThat(store.read(state -> state.lessons.getFirst().accepted).intValue()).isEqualTo(1);
            assertThat(store.read(state -> Planner.todaySteps(state, EVENING)).intValue()).isEqualTo(1);

            // 2026-11-18 22:00: a quote of the bot plus a log was taken for an answer and got «Пока нет ответа».
            // Now a guessed answer that the review finds empty is answered as ordinary chat.
            updates.add(message(3, "\"LeetCode 33 теперь officially stale code.\" чего блять\nBUILD SUCCESSFUL in 9s"));
            bot.poll();
            assertThat(sent.getLast().path("text").asText()).doesNotContain("Пока нет ответа").startsWith("Задание:");
            assertThat(store.read(state -> state.lessons.getFirst().pending == null && state.lessons.getFirst().accepted == 1)
                    .booleanValue()).isTrue();
            assertThat(store.read(state -> state.conversation.stream()
                    .filter(item -> item.question().contains("officially")).count()).longValue()).isEqualTo(1);

            // The chat model claims the lesson is complete. Only the database decides: still 2 of 4.
            store.write(state -> {
                var lesson = state.lessons.getFirst();
                lesson.position = 2;
                lesson.accepted = 2;
                return null;
            });
            updates.add(message(13, "Я же скинул про Interface.super.method() и лог gradle, всё ок?"));
            bot.poll();
            String claim = sent.getLast().path("text").asText();
            assertThat(claim).contains("Interface.super.method()").doesNotContain("4/4", "пройден", "пуллинге", "Счётчик");
            assertThat(store.read(state -> state.lessons.getFirst().accepted).intValue()).isEqualTo(2);
            assertThat(store.read(state -> state.lessons.getFirst().pending == null).booleanValue()).isTrue();

            // Someone else's code sent for a review is a question, not a submission.
            int before = calls.size();
            updates.add(message(14, "вот код одногруппника, разбери: try { a(); } finally { b(); }"));
            bot.poll();
            assertThat(calls.subList(before, calls.size())).noneMatch(call ->
                    call.path("messages").path(0).path("content").asText().contains("\"steps\""));
            assertThat(store.read(state -> state.lessons.getFirst().pending == null && state.lessons.getFirst().accepted == 2).booleanValue())
                    .isTrue();

            // The user points at a foreign word: history reaches the model with correct roles, the reply is cleaned.
            store.write(state -> {
                DayMemory.remember(state, EVENING, "объясни checked vs unchecked",
                        "Checked проверяет компилятор, 알아 например IOException.", null);
                return null;
            });
            updates.add(message(15, "ты че там за корейский вставил?"));
            bot.poll();
            JsonNode korean = calls.getLast();
            var history = korean.path("messages");
            assertThat(history.get(history.size() - 2).path("role").asText()).isEqualTo("assistant");
            assertThat(history.get(history.size() - 2).path("content").asText()).contains("알아");
            assertThat(sent.getLast().path("text").asText()).contains("Сорри").doesNotContain("알아", "正式");

            // If the router is down, the message still gets an answer plus a hint how to submit explicitly.
            updates.add(message(16, "сломай роутер, ну пожалуйста"));
            bot.poll();
            assertThat(sent.getLast().path("text").asText()).contains("Если это был ответ на задание");
            assertThat(store.read(state -> state.lessons.getFirst().accepted).intValue()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unfinishedDayIsAnOptionalTailAndMissedDayTurnsOnStrictHours() {
        var planner = new Planner();
        var state = new MentorState();
        Instant monday = Instant.parse("2026-11-16T07:00:00Z"); // 10:00 Moscow
        planner.resetStudy(state, Planner.date(state, monday), monday);
        state.exactReminders = true;
        var first = state.lessons.getFirst();
        first.started = true;
        first.position = 1;
        first.accepted = 1;
        state.stepsByDay.put(Planner.date(state, monday), 2);

        Instant tuesdayNight = Instant.parse("2026-11-17T01:00:30Z"); // 04:00:30 Tuesday in Moscow, when the study day rolls over
        planner.evaluate(state, tuesdayNight);
        assertThat(first.carried).isTrue();
        // Monday's unfinished lesson stays open as an optional tail; Tuesday's lesson comes first (2026-11-23).
        assertThat(Planner.active(state, tuesdayNight)).isSameAs(state.lessons.get(1));
        assertThat(Planner.tail(state, tuesdayNight)).isSameAs(first);
        assertThat(Planner.streak(state, tuesdayNight)).isEqualTo(1);

        Instant tuesdayMorning = Instant.parse("2026-11-17T07:05:00Z");
        var notice = planner.nextNotice(state, tuesdayMorning);
        assertThat(notice.text()).contains("Урок #2", "Зачтено 0 из 4", "Шаг 1").doesNotContain("долг", "Урок #1");

        // Tuesday's lesson is never touched: it is carried once, then counted as a missed day.
        Instant thursday = Instant.parse("2026-11-19T01:00:30Z");
        planner.evaluate(state, Instant.parse("2026-11-18T01:00:30Z"));
        planner.evaluate(state, thursday);
        var tuesday = state.lessons.get(1);
        assertThat(tuesday.carried).isTrue();
        assertThat(state.evaluatedDays.get(Planner.date(state, tuesday.start))).isTrue();
        assertThat(state.strictRemaining).isEqualTo(2);
        var strict = state.lessons.stream().filter(lesson -> lesson.strict).toList();
        assertThat(strict).hasSize(2);
        assertThat(Planner.focusStart(state, strict.getFirst()).atZone(java.time.ZoneId.of(state.zone)).getHour())
                .isEqualTo(19);
        assertThat(Planner.streak(state, thursday)).isZero();
    }

    @Test
    void theoryAnsweredInPlainChatStillReachesTheReviewOfALaterStep() {
        var state = new MentorState();
        Instant at = EVENING;
        DayMemory.remember(state, at.minusSeconds(3600), "try до ошибки - catch - finally, блять",
                "Верно. Следующий шаг — алгоритмы", null);
        DayMemory.remember(state, at.minusSeconds(1800), "/today", "Урок #3", null);
        var attempt = new MentorState.Attempt("public int maxArea(int[] height) { ... }\nСДАЮ ЗАДАЧУ", at);
        DayMemory.remember(state, at, attempt.text(), "", 3L);
        var earlier = DayMemory.earlierSubmissions(state, 3, attempt);
        assertThat(earlier).containsExactly("(сообщение из чата) try до ошибки - catch - finally, блять");
    }

    @Test
    void remindersMoveWithinHalfAnHourAndButtonsCreateFollowUps() {
        var state = new MentorState();
        var planner = new Planner();
        for (int day = 0; day < 30; day++) {
            var times = Planner.reminderTimes(state, java.time.LocalDate.of(2026, 11, 1).plusDays(day));
            for (int i = 0; i < times.size(); i++) {
                long shift = java.time.Duration.between(Planner.DAILY_REMINDERS.get(i), times.get(i)).toMinutes();
                assertThat(shift).isBetween(-30L, 50L);
                if (i > 0) assertThat(times.get(i)).isAfter(times.get(i - 1));
            }
            assertThat(times.getLast()).isBefore(java.time.LocalTime.of(23, 59));
            assertThat(times.getFirst()).isBetween(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(10, 30));
        }

        var json = JsonMapper.builder().build();
        planner.resetStudy(state, Planner.date(state, EVENING), EVENING);
        var bot = new MentorBot(new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json), planner,
                new TelegramApi(json, "http://127.0.0.1:1", "", 0), new MentorModel(json, "http://127.0.0.1:1", "", ""),
                Clock.fixed(EVENING, ZoneOffset.UTC), "", "", "");
        var start = bot.respond(state, "/startnow", "", EVENING);
        assertThat(start.text()).contains("Засёк 40 минут", "шаг 1 из 4", "✍️");
        assertThat(state.commitAt).isEqualTo(EVENING.plusSeconds(2400));
        assertThat(state.lessons.getFirst().started).isTrue();
        assertThat(bot.respond(state, "/snooze", "", EVENING).text()).contains("через час");
        assertThat(state.snoozeUntil).isEqualTo(EVENING.plusSeconds(3600));
    }

    /** Telegram hung for hours on 2026-11-18 and later delivered every retried message: dozens of copies. */
    @Test
    void hangingTelegramNeverMultipliesMessages() throws Exception {
        var json = JsonMapper.builder().build();
        var sendAttempts = new java.util.concurrent.atomic.AtomicInteger();
        var updatesServed = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/botfake/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/getUpdates")) {
                var result = updatesServed.getAndSet(true) ? List.of() : List.of(message(1, "/today"));
                respond(exchange, json.writeValueAsString(Map.of("ok", true, "result", result)));
                return;
            }
            sendAttempts.incrementAndGet();
            exchange.sendResponseHeaders(502, -1); // Telegram accepted nothing we can confirm
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            var store = new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json);
            var planner = new Planner();
            Instant evening = Instant.parse("2026-11-18T18:40:00Z"); // 21:40 Moscow, a reminder is due
            store.write(state -> {
                planner.resetStudy(state, Planner.date(state, evening), evening);
                state.exactReminders = true;
                state.ownerChat = 123;
                state.recallDay = Planner.date(state, evening);
                state.feedbackQueue.add("Засчитано. Шаг 1 из 4");
                return null;
            });
            var bot = new MentorBot(store, planner, new TelegramApi(json, base, "fake", 123),
                    new MentorModel(json, base + "/v1", "", ""), Clock.fixed(evening, ZoneOffset.UTC), "", "", "");
            for (int tick = 0; tick < 10; tick++) {
                bot.poll();
                bot.reminders();
            }
            // One reply to /today, one reminder, one review verdict — each attempted once despite failures.
            assertThat(sendAttempts.get()).isEqualTo(3);
            assertThat(store.read(state -> state.updateOffset).longValue()).isEqualTo(2);
            assertThat(store.read(state -> state.feedbackQueue.isEmpty()).booleanValue()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cleansForeignScriptAndTrimsLongChatsForGroq() {
        assertThat(MentorModel.cleanText("<think>план</think>Сорри, 알아 залетело 正式 слово")).isEqualTo("Сорри, залетело слово");
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", "s"));
        for (int i = 0; i < 10; i++) {
            messages.add(Map.of("role", "user", "content", "в".repeat(3000)));
            messages.add(Map.of("role", "assistant", "content", "о".repeat(3000)));
        }
        messages.add(Map.of("role", "user", "content", "последнее"));
        var fitted = MentorModel.fit(messages, 14_000);
        assertThat(fitted.getFirst().get("role")).isEqualTo("system");
        assertThat(fitted.get(1).get("role")).isEqualTo("user");
        assertThat(fitted.getLast().get("content")).isEqualTo("последнее");
        assertThat(fitted.stream().mapToInt(item -> item.get("content").length()).sum()).isLessThanOrEqualTo(14_000);
    }

    private static String lastContent(JsonNode call) {
        var messages = call.path("messages");
        return messages.get(messages.size() - 1).path("content").asText();
    }

    private static List<String> texts(List<JsonNode> sent) {
        return sent.stream().map(item -> item.path("text").asText()).toList();
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
