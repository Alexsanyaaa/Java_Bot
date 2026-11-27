package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The start-up without Spring (2026-11-27): settings from the environment, a file database, the --check self-test. */
class PlainStartupTest {
    @TempDir
    Path dir;

    @Test
    void stateSurvivesARestartOnAFileDatabase() {
        var json = JsonMapper.builder().build();
        var env = new Env(Map.of("MENTOR_DB_URL", "jdbc:h2:file:" + dir.resolve("mentor") + ";CACHE_SIZE=2048"));
        try (var db = MentorApplication.database(env)) {
            new StateStore(db, json).write(state -> {
                state.ownerChat = 42;
                state.updateOffset = 7;
                return null;
            });
        }
        assertThat(Files.exists(dir.resolve("mentor.mv.db"))).isTrue();
        try (var db = MentorApplication.database(env)) {
            var store = new StateStore(db, json);
            assertThat(store.<Long>read(state -> state.ownerChat).longValue()).isEqualTo(42L);
            assertThat(store.<Long>read(state -> state.updateOffset).longValue()).isEqualTo(7L);
            assertThat(MentorApplication.check(db, json, "")).isZero();
        }
    }

    @Test
    void checkPassesOnAnEmptyDatabaseToo() {
        var env = new Env(Map.of("MENTOR_DB_URL", "jdbc:h2:file:" + dir.resolve("empty")));
        try (var db = MentorApplication.database(env)) {
            assertThat(MentorApplication.check(db, JsonMapper.builder().build(), "")).isZero();
        }
    }

    @Test
    void checkReadsLegacyLessonsWithoutChangingTheBackup() {
        var json = JsonMapper.builder().build();
        try (var db = new Database("jdbc:h2:mem:legacyStartup", "sa", "")) {
            var store = new StateStore(db, json);
            var now = Instant.parse("2026-11-27T12:00:00Z");
            store.write(state -> {
                new Planner().resetStudy(state, Planner.date(state, now), now);
                var lesson = state.lessons.getFirst();
                lesson.accepted = 1;
                lesson.position = 1;
                lesson.done.add(0);
                return null;
            });
            var payload = json.readTree(store.payload());
            for (var lesson : payload.path("lessons")) {
                ((ObjectNode) lesson).remove("done");
                ((ObjectNode) lesson).remove("aiPlanned");
            }
            var legacy = json.writeValueAsString(payload);
            db.update("UPDATE mentor_state SET payload = ? WHERE id = 1", legacy);

            assertThat(MentorApplication.check(db, json, "")).isZero();
            assertThat(store.payload()).isEqualTo(legacy);
            assertThat(store.<List<Integer>>read(state -> state.lessons.getFirst().openSteps()))
                    .containsExactly(1, 2, 3);

            var broken = ((ObjectNode) payload).put("unknownProgress", 42);
            db.update("UPDATE mentor_state SET payload = ? WHERE id = 1", json.writeValueAsString(broken));
            assertThat(MentorApplication.check(db, json, "")).isEqualTo(1);
        }
    }

    @Test
    void settingsComeFromTheSameEnvironmentVariablesAsBefore() {
        var json = JsonMapper.builder().build();
        assertThat(MentorModel.fromEnv(json, new Env(Map.of())).enabled()).isFalse();
        assertThat(MentorModel.fromEnv(json, new Env(Map.of())).planningEnabled()).isFalse();
        var model = MentorModel.fromEnv(json, new Env(Map.of("LLM_MODEL", "nvidia/nemotron-3-ultra-550b-a55b:free",
                "GEMINI_API_KEY", "fake", "LLM_API_KEY", "fake")));
        assertThat(model.enabled()).isTrue();
        assertThat(model.planningEnabled()).isTrue();
        assertThat(new Env(Map.of("TELEGRAM_OWNER_ID", " 123 ")).number("TELEGRAM_OWNER_ID", 0)).isEqualTo(123L);
        assertThat(new Env(Map.of()).number("TELEGRAM_OWNER_ID", 0)).isZero();
        assertThat(MentorApplication.database(new Env(Map.of())).toString()).isNotNull();
    }
}
