package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the new planner over a copy of the production database before a deploy.
 * Only runs when MENTOR_DRYRUN_DB points at a copy (path without .mv.db); never at the live file.
 */
@EnabledIfEnvironmentVariable(named = "MENTOR_DRYRUN_DB", matches = ".+")
class ProductionCopyDryRunTest {
    @Test
    void newVersionKeepsProgressOfTheProductionCopy() throws Exception {
        var json = JsonMapper.builder().build();
        var jdbc = new Database(
                "jdbc:h2:file:" + System.getenv("MENTOR_DRYRUN_DB"), "sa", "");
        var store = new StateStore(jdbc, json);
        var planner = new Planner();
        Instant now = Instant.now();
        String before = store.read(ProductionCopyDryRunTest::summary);
        store.write(state -> {
            planner.evaluate(state, now);
            return null;
        });
        String after = store.read(ProductionCopyDryRunTest::summary);
        var bot = new MentorBot(store, planner, new TelegramApi(json, "http://127.0.0.1:1", "", 0),
                new MentorModel(json, "http://127.0.0.1:1", "", ""), Clock.fixed(now, ZoneOffset.UTC), "", "", "");
        String today = store.write(state -> bot.respond(state, "/today", "", now).text());
        var reminders = new StringBuilder();
        for (var slot : Planner.DAILY_REMINDERS) {
            Instant at = Planner.reminderAt(store.read(state -> state), Planner.date(store.read(state -> state), now),
                    slot.plusMinutes(5));
            var notice = store.read(state -> planner.nextNotice(state, at));
            reminders.append(slot).append(" → ").append(notice == null ? "—" : notice.text()).append("\n\n");
        }
        Files.writeString(Path.of(System.getenv("MENTOR_DRYRUN_DB") + "-report.txt"),
                "BEFORE\n" + before + "\nAFTER\n" + after + "\n/today\n" + today + "\n\nREMINDERS\n" + reminders);
        assertThat(after.lines().filter(line -> line.startsWith("#")).map(line -> line.replaceAll(" carried=.*", ""))
                .collect(Collectors.toList()))
                .containsAll(before.lines().filter(line -> line.startsWith("#") && line.contains("accepted="))
                        .map(line -> line.replaceAll(" carried=.*", "")).filter(line -> !line.contains("end="))
                        .collect(Collectors.toList()));
    }

    private static String summary(MentorState state) {
        var text = new StringBuilder();
        text.append("flexible=").append(state.flexible).append(" strict=").append(state.strictRemaining)
                .append(" solved=").append(state.solved.size()).append(" skills=").append(state.skills.keySet())
                .append(" curriculum=").append(state.curriculumVersion).append('\n');
        state.projects.forEach(project -> text.append("project ").append(project.id()).append(" deadline=")
                .append(project.deadline()).append(" steps=").append(project.completedSteps())
                .append(" closed=").append(project.closed()).append('\n'));
        state.lessons.forEach(lesson -> text.append('#').append(lesson.id).append(' ')
                .append(Planner.date(state, lesson.start)).append(" accepted=").append(lesson.accepted)
                .append('/').append(lesson.tasks.size()).append(" started=").append(lesson.started)
                .append(" pending=").append(lesson.pending != null).append(" carried=").append(lesson.carried)
                .append(" strict=").append(lesson.strict).append(" theory=").append(lesson.tasks.getFirst().topic())
                .append('\n'));
        text.append("conversation=").append(state.conversation.size()).append(" evaluatedDays=")
                .append(state.evaluatedDays).append('\n');
        return text.toString();
    }
}
