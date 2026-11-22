package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MentorFlowTest {
    private static final Instant MONDAY = Instant.parse("2026-11-16T15:00:00Z");
    private static final Instant START = MONDAY.plusSeconds(3600);

    @Test
    void telegramScheduleReminderReviewAndProjectClosingSurviveRestart() throws IOException {
        var json = JsonMapper.builder().build();
        var updates = new ArrayList<Map<String, Object>>();
        var sent = new ArrayList<JsonNode>();
        var llmCalls = new ArrayList<JsonNode>();
        var failNextModel = new java.util.concurrent.atomic.AtomicBoolean();
        var modelsUnavailable = new java.util.concurrent.atomic.AtomicBoolean();
        var failNextSend = new java.util.concurrent.atomic.AtomicBoolean();
        var malformedNextReview = new java.util.concurrent.atomic.AtomicBoolean();
        var lessonReview = new java.util.concurrent.atomic.AtomicReference<>("""
                {"steps":[{"step":1,"status":"accepted","feedback":"Контракт равенства объяснён.","evidenceLine":1},
                {"step":2,"status":"missing","feedback":""}]}
                """);
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/botfake/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (exchange.getRequestURI().getPath().endsWith("/getUpdates")) {
                long offset = request.path("offset").asLong();
                respond(exchange, json.writeValueAsString(Map.of("ok", true, "result", updates.stream()
                        .filter(item -> (long) item.get("update_id") >= offset).toList())));
            } else {
                if (exchange.getRequestURI().getPath().endsWith("/sendMessage")) {
                    if (failNextSend.getAndSet(false)) {
                        respond(exchange, "{\"ok\":false}");
                        return;
                    }
                    sent.add(request);
                }
                respond(exchange, "{\"ok\":true,\"result\":{}}");
            }
        });
        server.createContext("/file/botfake/voice/test.oga", exchange -> respond(exchange, "mock audio"));
        server.createContext("/botfake/getFile", exchange ->
                respond(exchange, "{\"ok\":true,\"result\":{\"file_path\":\"voice/test.oga\"}}"));
        server.createContext("/v1/audio/transcriptions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            String audio = new String(java.util.Base64.getDecoder().decode(
                    request.path("input_audio").path("data").asText()), StandardCharsets.UTF_8);
            respond(exchange, audio.equals("mock audio")
                    ? "{\"text\":\"Разные ключи могут иметь одинаковый hashCode.\"}" : "{}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (modelsUnavailable.get() || failNextModel.getAndSet(false)) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            if (malformedNextReview.getAndSet(false)) {
                respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"{\\\"accepted\\\": false}\"}}]}");
                return;
            }
            llmCalls.add(request);
            String instructions = request.path("messages").path(0).path("content").asText();
            var messages = request.path("messages");
            String last = messages.get(messages.size() - 1).path("content").asText();
            int marker = last.lastIndexOf("Сообщение ученика:");
            String user = (marker < 0 ? last : last.substring(marker + "Сообщение ученика:".length())).strip();
            String content = instructions.contains("классификатор")
                    ? (user.startsWith("class Shape") ? "{\"intent\":\"answer\"}" : "{\"intent\":\"chat\"}")
                    : user.startsWith("Проверка счётчика")
                            ? "**Шаг 1 из 4 зачтён.** Бэкенд обновит счётчик."
                            : user.startsWith("Литкод принял")
                            ? "**Шаг 1 из 4 зачтён.** LeetCode засчитал решение, потому что `poll()` удаляет минимум при переполнении."
                    : instructions.contains("\"steps\"") ? lessonReview.get()
                    : instructions.contains("поля") && instructions.contains("accepted")
                            ? "{\"accepted\":true,\"feedback\":\"Контракт равенства объяснён.\"}"
                            : "Расскажи, как HashMap обрабатывает коллизии.";
            respond(exchange, json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
        });
        server.start();
        try {
            var clock = new AdjustableClock(MONDAY);
            var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", ""));
            var store = new StateStore(jdbc, json);
            var planner = new Planner();
            String base = "http://localhost:" + server.getAddress().getPort();
            var telegram = new TelegramApi(json, base, "fake", 123);
            var model = new MentorModel(json, base + "/v1", "test-model", "");
            var bot = new MentorBot(store, planner, telegram, model, clock, "", "", "ssh://git@example.test/t04b.git");
            updates.add(message(1, 123, "/windows 1=19:00 2=19:00 3=19:00 4=19:00 5=19:00 6=19:00 7=19:00"));
            updates.add(message(2, 123, "/t04b 2026-12-13"));
            updates.add(message(3, 123, "/week"));
            updates.add(message(4, 123, "/confirm"));
            updates.add(message(5, 999, "/close t04b"));
            bot.poll();
            List<Integer> sizes = store.read(state -> List.of(state.lessons.size(), state.projects.size()));
            assertThat(sizes).containsExactly(7, 1);
            List<MentorState.Lesson> lessons = store.read(state -> state.lessons);
            for (MentorState.Lesson lesson : lessons) {
                assertThat(lesson.tasks).extracting(MentorState.Task::minutes).containsExactly(45, 25, 30, 20);
                // School 21 lives in its own mode: the fourth daily step is practice on the lesson's topic.
                assertThat(lesson.tasks.getLast().type()).isEqualTo("Практика");
                assertThat(lesson.tasks).allMatch(task -> task.projectId() == null);
            }
            assertThat(lessons).allSatisfy(lesson -> {
                assertThat(lesson.tasks.getFirst().resource()).contains("https://", "Изучить:", "Граница чтения:")
                        .doesNotContain("youtube.com/playlist");
                assertThat(StudyMaterials.readings(lesson.tasks.getFirst().topic()).stream()
                        .mapToInt(StudyMaterials.Reading::minutes).sum()).isEqualTo(25);
            });
            assertThat(lessons.getFirst().tasks.getFirst().resource()).contains("metanit.com");
            assertThat(store.read(state -> state.projects.getFirst().closed()).booleanValue()).isFalse();
            assertThat(sent).hasSize(4);

            var restartedStore = new StateStore(jdbc, json);
            var restarted = new MentorBot(restartedStore, planner, telegram, model, clock, "", "", "");
            restarted.poll();
            assertThat(sent).hasSize(4);

            org.springframework.test.util.ReflectionTestUtils.setField(model, "fallbackModels", "reserve-model");
            failNextModel.set(true);
            updates.add(message(6, 123, "Можно подробнее про урок #1?"));
            restarted.poll();
            JsonNode fallbackCall = llmCalls.removeLast();
            assertThat(fallbackCall.path("model").asText()).isEqualTo("reserve-model");
            // Study status lives in the system message; the user turn is only what the student wrote.
            String context = fallbackCall.path("messages").path(0).path("content").asText();
            assertThat(context).contains("#1 · 2026-11-16 19:00", "запланирован", "Равенство и HashMap",
                    "Two Sum", "Почему одинаковый hashCode", "Практика")
                    .doesNotContain("git@example.test");
            assertThat(fallbackCall.path("messages").path(1).path("content").asText()).isEqualTo("Можно подробнее про урок #1?");

            clock.now = START.minusSeconds(900);
            restarted.reminders();
            restarted.reminders();
            assertThat(sent.stream().filter(item -> item.path("text").asText().contains("\nСкоро занятие")).count())
                    .isEqualTo(1);
            clock.now = START;
            restarted.reminders();
            String startButton = sent.getLast().path("reply_markup").path("inline_keyboard").path(0)
                    .path(0).path("callback_data").asText();
            assertThat(startButton).startsWith("start:1:");
            updates.add(Map.of("update_id", 7L, "callback_query", Map.of("id", "callback",
                    "data", startButton, "from", Map.of("id", 123), "message", Map.of("chat", chat(123)))));
            restarted.poll();
            // Telegram queues the answer while the service is down, including across the lesson boundary.
            Map<String, Object> answer = Map.of("update_id", 8L, "message", Map.of(
                    "from", Map.of("id", 123), "chat", chat(123), "date", START.plusSeconds(7140).getEpochSecond(),
                    "text", "/submit Два User с одинаковым ID равны и дают один ключ в HashMap."));
            updates.add(answer);
            clock.now = START.plusSeconds(7440);
            failNextSend.set(true);
            restarted.poll();
            // At most once: the update is marked handled before the reply, so a failed send is never repeated.
            assertThat(restartedStore.read(state -> state.updateOffset).longValue()).isEqualTo(9);
            assertThat(restartedStore.read(state -> state.lessons.getFirst().pending.submittedAt().getEpochSecond()).longValue())
                    .isEqualTo(START.plusSeconds(7140).getEpochSecond());
            int sentBeforeRestart = sent.size();
            restarted = new MentorBot(new StateStore(jdbc, json), planner, telegram, model, clock, "", "", "");
            restarted.poll();
            assertThat(sent).hasSize(sentBeforeRestart);
            assertThat(restartedStore.read(state -> state.updateOffset).longValue()).isEqualTo(9);
            clock.now = START;
            malformedNextReview.set(true);
            restarted.review();
            restarted.reminders();
            // One review call; Oleg's retelling of the verdict may follow it.
            assertThat(llmCalls.stream().filter(call -> !call.toString().contains("Сейчас особая задача")).toList()).hasSize(1);
            assertThat(llmCalls.getFirst().path("model").asText()).isEqualTo("reserve-model");
            assertThat(llmCalls.getFirst().toString()).doesNotContain("example.test");
            assertThat(sent.getLast().path("text").asText()).startsWith("Засчитано.");
            assertThat(restartedStore.read(state -> state.lessons.getFirst().accepted).intValue()).isEqualTo(1);

            updates.add(message(9, 123, "/close t04b"));
            restarted.poll();
            assertThat(restartedStore.read(state -> state.lessons.stream()
                    .flatMap(lesson -> lesson.tasks.stream()).anyMatch(task -> "t04b".equals(task.projectId()))).booleanValue())
                    .isFalse();

            updates.add(message(10, 123, "/newchat"));
            updates.add(message(11, 123, "Проведи мини-собеседование по Java."));
            updates.add(message(12, 123, "Разные ключи могут попасть в одну корзину."));
            restarted.poll();
            assertThat(sent.getLast().path("text").asText()).contains("HashMap");
            JsonNode followup = llmCalls.getLast().path("messages");
            assertThat(followup.path(1).path("content").asText()).contains("мини-собеседование");
            assertThat(followup.path(2).path("role").asText()).isEqualTo("assistant");
            assertThat(followup.path(3).path("content").asText()).contains("корзину");
            assertThat(restartedStore.read(state -> state.lessons.getFirst().accepted).intValue()).isEqualTo(1);

            updates.add(message(13, 123, "/newchat"));
            updates.add(message(14, 123, "Объясни HashMap."));
            restarted.poll();
            assertThat(llmCalls.getLast().path("messages").size()).isEqualTo(2);

            updates.add(message(15, 123, "/menu"));
            updates.add(message(16, 123, "🎙 Собеседование"));
            restarted.poll();
            assertThat(sent.get(sent.size() - 2).path("reply_markup").path("keyboard").size()).isEqualTo(3);
            assertThat(sent.getLast().path("text").asText()).contains("Вопрос 1 из 5");

            org.springframework.test.util.ReflectionTestUtils.setField(model, "transcriptionModel", "test-stt");
            updates.add(Map.of("update_id", 17L, "message", Map.of("from", Map.of("id", 123), "chat", chat(123),
                    "voice", Map.of("duration", 5, "file_size", 10, "file_id", "sample"))));
            restarted.poll();
            assertThat(sent.get(sent.size() - 2).path("text").asText()).startsWith("Распознано:");
            assertThat(sent.getLast().path("text").asText()).contains("Вопрос 2 из 5");
            assertThat(restartedStore.read(state -> state.interviewTurn).intValue()).isEqualTo(1);
            assertThat(restartedStore.read(state -> state.lessons.getFirst().accepted).intValue()).isEqualTo(1);

            updates.add(message(18, 123, "/endinterview"));
            updates.add(Map.of("update_id", 19L, "message", Map.of("from", Map.of("id", 123), "chat", chat(123),
                    "voice", Map.of("duration", 5, "file_size", 10, "file_id", "sample"))));
            restarted.poll();
            assertThat(sent.get(sent.size() - 2).path("text").asText()).startsWith("Распознано:");
            assertThat(sent.getLast().path("text").asText()).contains("HashMap");
            assertThat(llmCalls.getLast().path("messages").get(llmCalls.getLast().path("messages").size() - 1)
                    .path("content").asText()).contains("Разные ключи могут иметь одинаковый hashCode.");

            // An answer to a later step is retained even when the current step is missing.
            clock.now = START.plusSeconds(20);
            lessonReview.set("""
                    {"steps":[{"step":2,"status":"missing","feedback":""}]}
                    """);
            updates.add(message(20, 123, "Вот моё решение: коллизия, например строки Aa и BB."));
            restarted.poll();
            restarted.review();
            restarted.reminders();
            assertThat(restartedStore.read(state -> state.lessons.getFirst().position).intValue()).isEqualTo(1);
            assertThat(llmCalls.getLast().path("messages").path(1).path("content").asText())
                    .containsPattern("Последняя сдача ученика:\\n\\[\\d+] коллизия");

            // The next submission adds the missing answer and the prior one is used without resending.
            clock.now = START.plusSeconds(40);
            lessonReview.set("""
                    {"steps":[{"step":2,"status":"accepted","feedback":"Подход верный.","evidenceLine":2},
                    {"step":3,"status":"accepted","feedback":"Пример есть.","evidenceLine":1},
                    {"step":4,"status":"missing","feedback":""}]}
                    """);
            updates.add(message(21, 123, "Сдаю решение: храню дополнения в HashMap, O(n) по времени и памяти."));
            restarted.poll();
            restarted = new MentorBot(new StateStore(jdbc, json), planner, telegram, model, clock, "", "", "");
            restarted.review();
            restarted.reminders();
            assertThat(llmCalls.stream().filter(call -> !call.toString().contains("Сейчас особая задача")).toList()
                    .getLast().path("messages").path(1).path("content").asText())
                    .contains("Предыдущая сдача ученика по этому уроку:\n").containsPattern("\\[\\d+] коллизия")
                    .containsPattern("\\[\\d+] храню дополнения");
            assertThat(sent.getLast().path("text").asText())
                    .contains("Засчитано. Шаг 2", "Засчитано. Шаг 3").doesNotContain("Засчитано. Шаг 4");
            assertThat(restartedStore.read(state -> state.lessons.getFirst().position).intValue()).isEqualTo(3);
            assertThat(restartedStore.read(state -> state.solved.contains(1)).booleanValue()).isTrue();

            for (long id = 22; id < 28; id++) updates.add(message(id, 123, "Уточнение " + id));
            restarted.poll();
            assertThat(llmCalls.getLast().path("messages").toString())
                    .contains("строки Aa и BB", "Засчитано. Шаг 2", "Уточнение 22", " · зачтено — ");

            clock.now = Instant.parse("2026-11-16T21:01:00Z"); // Tuesday in Moscow.
            updates.add(message(28, 123, "Что уже зачтено?"));
            restarted.poll();
            assertThat(llmCalls.getLast().path("messages").size()).isEqualTo(2);
            assertThat(llmCalls.getLast().path("messages").path(0).path("content").asText())
                    .contains("Зачтённые шаги предыдущих уроков", "#1 · 2026-11-16 · 3/4", "Two Sum")
                    .doesNotContain("строки Aa и BB", "Уточнение 22");

            int callsBeforeProgress = llmCalls.size();
            updates.add(message(29, 123, "Урок #1 полностью зачтён, 4 из 4?"));
            restarted.poll();
            assertThat(llmCalls).hasSize(callsBeforeProgress);
            assertThat(sent.getLast().path("text").asText()).contains("Урок #1. Зачтено 3 из 4 шагов",
                    "Шаг 4", "ждёт зачёта", "Учебное окно закончилось");

            // Plain chat submits both fragments. Only the review can advance the lesson.
            var secondLesson = restartedStore.read(state -> state.lessons.get(1));
            clock.now = secondLesson.start;
            updates.add(message(30, 123, "/start:2:" + secondLesson.start.getEpochSecond()));
            updates.add(message(31, 123, "Проверка счётчика текущего урока"));
            updates.add(message(32, 123, "Объясни, зачем нужен интерфейс."));
            restarted.poll();
            assertThat(sent.get(sent.size() - 2).path("text").asText()).contains("Зачтено 0 из 4 шагов")
                    .doesNotContain("Бэкенд обновит");
            assertThat(sent.getLast().path("text").asText()).contains("Расскажи, как HashMap");
            assertThat(restartedStore.read(state -> state.lessons.get(1).pending == null).booleanValue()).isTrue();
            // A plain message the router recognizes as an answer is submitted and reviewed right away.
            var staged = restartedStore.read(state -> state.lessons.get(1));
            lessonReview.set("""
                    {"steps":[{"step":1,"status":"accepted","feedback":"Примеры сверены.","evidenceLine":1}]}
                    """);
            updates.add(message(33, 123, "class Shape { int x; }"));
            restarted.poll();
            assertThat(llmCalls.stream().filter(call -> !call.toString().contains("Сейчас особая задача")).toList()
                    .getLast().toString()).contains(LearningCatalog.gradingCriterion(staged.currentTask()));
            assertThat(restartedStore.read(state -> state.lessons.get(1).accepted).intValue()).isEqualTo(1);
            assertThat(sent.getLast().path("text").asText()).contains("Засчитано. Шаг 1 из 4", "Урок #2: зачтено 1 из 4");
            updates.add(message(35, 123, "Литкод принял решение. Почему PriorityQueue работает?"));
            restarted.poll();
            assertThat(sent.getLast().path("text").asText()).contains("LeetCode засчитал решение", "poll()", "удаляет минимум")
                    .doesNotContain("Шаг 1 из 4 зачтён", "нажми «Ответить»");

            updates.add(message(36, 123, "/submit Храню k элементов в минимальной куче."));
            restarted.poll();
            modelsUnavailable.set(true);
            restarted.review();
            restarted.reminders();
            clock.now = clock.now.plusSeconds(65);
            restarted.review();
            restarted.reminders();
            assertThat(sent.stream().filter(item -> item.path("text").asText().contains("проверка задерживается"))).hasSize(1);
            var delayed = restartedStore.read(state -> state.lessons.get(1));
            assertThat(delayed.accepted).isEqualTo(1);
            assertThat(delayed.pending.text()).contains("минимальной куче");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void telegramSplitsLongMessagesWithoutLosingTextOrButtons() throws Exception {
        var json = JsonMapper.builder().build();
        var sent = new ArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/botfake/sendMessage", exchange -> {
            sent.add(json.readTree(exchange.getRequestBody()));
            respond(exchange, "{\"ok\":true,\"result\":{}}");
        });
        server.start();
        try {
            var telegram = new TelegramApi(json, "http://localhost:" + server.getAddress().getPort(), "fake", 123);
            String text = "а".repeat(4095) + "🚀" + "б".repeat(3900) + "\n" + "Последняя часть\n".repeat(100);
            telegram.send(123, text, new TelegramApi.Button("Подтвердить", "confirm"));
            assertThat(sent).hasSize(3);
            StringBuilder received = new StringBuilder();
            for (int index = 0; index < sent.size(); index++) {
                JsonNode part = sent.get(index);
                String content = part.path("text").asText();
                assertThat(content.length()).isLessThanOrEqualTo(4096);
                assertThat(part.has("reply_markup")).isEqualTo(index == sent.size() - 1);
                received.append(content);
            }
            assertThat(received.toString()).isEqualTo(text);

            sent.clear();
            String code = "if (a < b && n != i) { String s = \"**буквально**\"; }\n".repeat(90);
            telegram.sendFormatted(123, "🚀 **Замечание**: `containsKey`\n\n```java\n" + code + "```");
            assertThat(sent.size()).isGreaterThan(1);
            var first = sent.getFirst();
            assertThat(first.path("text").asText()).startsWith("🚀 Замечание: containsKey\n");
            assertThat(first.path("entities").path(0).path("type").asText()).isEqualTo("bold");
            assertThat(first.path("entities").path(0).path("offset").asInt()).isEqualTo(3);
            assertThat(first.path("entities").path(1).path("type").asText()).isEqualTo("code");
            var last = sent.getLast();
            assertThat(last.path("entities").path(0).path("type").asText()).isEqualTo("pre");
            assertThat(last.path("entities").path(0).path("offset").asInt()).isZero();
            assertThat(sent.stream().map(part -> part.path("text").asText()).collect(java.util.stream.Collectors.joining()))
                    .isEqualTo("🚀 Замечание: containsKey\n\n" + code);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void followsUpWithoutAResultAndSendsOneMissedLessonNotice() {
        var state = new MentorState();
        var planner = new Planner();
        state.windows.put(1, java.time.LocalTime.of(19, 0));
        planner.draft(state, MONDAY);
        planner.confirm(state, MONDAY);
        var lesson = state.lessons.getFirst();
        lesson.startSent = true;
        lesson.started = true;
        Instant halfHour = START.plusSeconds(1800);
        assertThat(planner.nextNotice(state, halfHour).text()).contains("Нужна первая попытка");

        lesson.pending = new MentorState.Attempt("Ответ", halfHour);
        assertThat(planner.nextNotice(state, halfHour)).isNull();
        assertThat(planner.nextNotice(state, lesson.end())).isNull();

        lesson.pending = null;
        var missed = planner.nextNotice(state, lesson.end());
        assertThat(missed.kind()).isEqualTo("missed");
        assertThat(missed.text()).contains("Java-собеседованию");
        planner.markNotice(state, missed);
        assertThat(planner.nextNotice(state, lesson.end())).isNull();
    }

    @Test
    void countsMissedDaysAndExtendsStrictModeOnlyOncePerDay() {
        var state = new MentorState();
        var planner = new Planner();
        for (int day = 1; day <= 7; day++) {
            state.windows.put(day, java.time.LocalTime.of(19, 0));
        }
        planner.draft(state, MONDAY);
        assertThat(planner.confirm(state, MONDAY)).isEqualTo(7);
        state.lessons.get(1).started = true;

        Instant wednesday = MONDAY.plusSeconds(2 * 86_400);
        planner.evaluate(state, wednesday);
        assertThat(state.strictRemaining).isEqualTo(3);
        assertThat(state.lessons.stream().filter(lesson -> lesson.strict).map(lesson -> lesson.id))
                .containsExactly(3L, 4L, 5L);
        planner.evaluate(state, wednesday.plusSeconds(86_400));
        assertThat(state.strictRemaining).isEqualTo(4);
        planner.evaluate(state, wednesday.plusSeconds(86_400));
        assertThat(state.strictRemaining).isEqualTo(4);
    }

    @Test
    void pendingReviewAndPauseDoNotCountAsAbsence() {
        var state = new MentorState();
        var planner = new Planner();
        state.windows.put(1, java.time.LocalTime.of(19, 0));
        state.windows.put(2, java.time.LocalTime.of(19, 0));
        planner.draft(state, MONDAY);
        planner.confirm(state, MONDAY);
        state.lessons.getFirst().pending = new MentorState.Attempt("Ответ", START);
        planner.pause(state, Planner.date(state, MONDAY).plusDays(1), START);
        planner.evaluate(state, MONDAY.plusSeconds(2 * 86_400));
        assertThat(state.evaluatedDays).isEmpty();
    }

    @Test
    void updatesConfirmedLessonsWithoutChangingTheirTimeOrStartedTasks() {
        var state = new MentorState();
        var planner = new Planner();
        state.windows.put(1, java.time.LocalTime.of(19, 0));
        state.windows.put(2, java.time.LocalTime.of(19, 0));
        planner.draft(state, MONDAY);
        planner.confirm(state, MONDAY);
        var started = state.lessons.getFirst();
        var future = state.lessons.getLast();
        var old = new MentorState.Task("Теория", "mvc", 30, "Старое задание", "", "", null, null);
        started.tasks.set(0, old);
        future.tasks.set(0, old);
        state.curriculumVersion = 1;
        started.started = true;
        started.pending = new MentorState.Attempt("Текущий ответ", START);
        future.preSent = true;
        Instant time = future.start;

        planner.evaluate(state, START);

        assertThat(started.currentTask()).isEqualTo(old);
        assertThat(started.pending.text()).isEqualTo("Текущий ответ");
        assertThat(future.currentTask().minutes()).isEqualTo(45);
        assertThat(future.currentTask().resource()).contains("https://", "Изучить:", "Граница чтения:");
        assertThat(state.curriculumVersion).isEqualTo(4);
        assertThat(future.start).isEqualTo(time);
        assertThat(future.preSent).isTrue();
    }

    private static Map<String, Object> message(long id, int sender, String text) {
        return Map.of("update_id", id, "message", Map.of("from", Map.of("id", sender),
                "chat", chat(sender), "text", text));
    }

    private static Map<String, Object> chat(int id) {
        return Map.of("type", "private", "id", id);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    private static final class AdjustableClock extends Clock {
        private Instant now;

        AdjustableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
