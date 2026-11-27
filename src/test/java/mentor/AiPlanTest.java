package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

class AiPlanTest {
    private static final Instant NOV_26 = Instant.parse("2026-11-26T12:00:00Z");

    @Test
    void repairsAlreadyMasteredHashMapAndThreeIdenticalSqlLessonsWithoutTouchingProgress() {
        var planner = new Planner();
        var state = new MentorState();
        planner.resetStudy(state, LocalDate.of(2026, 11, 26), NOV_26);
        var first = state.lessons.getFirst();
        var old = new MentorState.Lesson();
        old.id = 100;
        old.start = first.start.minusSeconds(864_000);
        old.flexibleEnd = old.start.plusSeconds(86_400);
        old.started = true;
        old.position = 4;
        old.accepted = 4;
        old.tasks = List.copyOf(first.tasks);
        state.lessons.add(old);
        // Theory was passed in #1 even if the skill table lacks a recent check.
        var sqlDays = state.lessons.subList(4, 7);
        for (var lesson : sqlDays) setTopic(state, lesson, "sql");
        var pending = state.lessons.get(1);
        pending.pending = new MentorState.Attempt("Уже отправлено", NOV_26);
        var pendingCard = List.copyOf(pending.tasks);
        var algorithms = state.lessons.subList(0, 7).stream().map(lesson -> lesson.tasks.get(1)).toList();

        planner.evaluate(state, NOV_26);

        assertThat(first.tasks.getFirst().topic()).isNotEqualTo("collections");
        assertThat(state.lessons.subList(0, 7)).extracting(lesson -> lesson.tasks.getFirst().topic()).doesNotHaveDuplicates();
        assertThat(sqlDays).extracting(lesson -> lesson.tasks.getLast().prompt()).doesNotHaveDuplicates();
        assertThat(state.lessons.subList(0, 7)).extracting(lesson -> lesson.tasks.get(1)).containsExactlyElementsOf(algorithms);
        assertThat(old.accepted).isEqualTo(4);
        assertThat(old.tasks.getFirst().topic()).isEqualTo("collections");
        assertThat(pending.tasks).isEqualTo(pendingCard);
        assertThat(pending.pending).isNotNull();

        for (int day = 1; day <= 9; day++) {
            Instant now = NOV_26.plusSeconds(86_400L * day);
            planner.evaluate(state, now);
            LocalDate date = Planner.date(state, now);
            assertThat(state.lessons.stream().filter(lesson -> !Planner.date(state, lesson.start).isBefore(date)))
                    .hasSize(7).extracting(lesson -> lesson.tasks.getFirst().topic()).doesNotHaveDuplicates();
        }
    }

    @Test
    void fallsBackToNemotronOnAnInvalidGeminiPlanAndDiscardsResultsIfAStudentStarted() throws Exception {
        var planner = new Planner();
        var state = new MentorState();
        planner.resetStudy(state, LocalDate.of(2026, 11, 26), NOV_26);
        var request = planner.planRequest(state, NOV_26);
        var choices = request.candidates().reversed().stream().map(Planner.PlanTopic::id)
                .limit(request.slots().size()).toList();
        var calls = new CopyOnWriteArrayList<String>();
        var json = JsonMapper.builder().build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = json.readTree(exchange.getRequestBody());
            String model = body.path("model").asText();
            calls.add(model);
            String plan = model.startsWith("gemini") ? """
                    {"topics":["sql","sql","sql","sql","sql","sql"]}
                    """ : json.writeValueAsString(Map.of("topics", choices));
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", plan)))));
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var model = new MentorModel(json, base, "", "fake-openrouter");
            TestFields.set(model, "geminiBase", base);
            TestFields.set(model, "geminiKey", "fake-gemini");
            var topics = model.plan(request);
            assertThat(calls).containsExactly("gemini-3.6-flash", "nvidia/nemotron-3.5-super-vl:free");
            assertThat(topics).isEqualTo(choices);

            var firstFuture = Planner.byId(state, request.slots().getFirst().id());
            firstFuture.started = true;
            assertThat(planner.applyPlan(state, NOV_26, request, topics)).isFalse();
            firstFuture.started = false;
            assertThat(planner.applyPlan(state, NOV_26, request, topics)).isTrue();
            assertThat(firstFuture.tasks.getFirst().topic()).isEqualTo(choices.getFirst());
            assertThat(firstFuture.tasks.getLast().topic()).isEqualTo("practice-" + choices.getFirst());
            assertThat(firstFuture.tasks.getFirst().resource()).startsWith(StudyMaterials.readings(choices.getFirst()).getFirst().title());
            assertThat(planner.planRequest(state, NOV_26).slots()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static void setTopic(MentorState state, MentorState.Lesson lesson, String topic) {
        lesson.tasks = LearningCatalog.day(state, Planner.date(state, lesson.start), topic, lesson.tasks.get(1));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MENTOR_LIVE_PLAN", matches = "1")
    void liveModelBuildsAValidPlanAfterTheFoundations() throws Exception {
        var planner = new Planner();
        var state = new MentorState();
        state.knownTopics.addAll(List.of("collections", "di", "mvc", "jpa", "interfaces", "atomicity", "transactions"));
        state.flexible = true;
        planner.evaluate(state, NOV_26);
        var request = planner.planRequest(state, NOV_26);
        assertThat(request.slots()).hasSize(6);
        var model = new MentorModel(JsonMapper.builder().build(), System.getenv("LLM_BASE_URL"), "",
                System.getenv().getOrDefault("LLM_API_KEY", ""));
        TestFields.set(model, "geminiKey", System.getenv().getOrDefault("GEMINI_API_KEY", ""));
        var topics = model.plan(request);
        assertThat(planner.applyPlan(state, NOV_26, request, topics)).isTrue();
        System.out.println("Темы: " + topics);
    }
}
