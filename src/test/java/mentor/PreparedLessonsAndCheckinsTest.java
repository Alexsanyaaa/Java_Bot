package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PreparedLessonsAndCheckinsTest {
    private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");
    private static final MentorState.Project JAVA = new MentorState.Project("t04b", "Java T04B",
            "PostgreSQL, регистрация и игра вдвоём", "ssh://git@example.test/private.git", LocalDate.of(2026, 12, 13), false, 2);

    @Test
    void resetsIntoFlexibleDaysAndAllowsOneSkipPerCalendarWeek() {
        var state = new MentorState();
        var planner = new Planner();
        Instant sunday = NOW.minusSeconds(86400);
        LocalDate monday = Planner.date(state, NOW);
        state.projects.add(JAVA);
        state.solved.add(1);
        state.windows.put(1, LocalTime.of(19, 0));
        state.strictRemaining = 3;
        planner.resetStudy(state, monday, sunday);
        assertThat(Planner.active(state, sunday)).isNull();
        assertThat(state.lessons).hasSize(7);
        assertThat(state.lessons.getFirst().id).isEqualTo(1);
        assertThat(state.lessons.getFirst().tasks).noneMatch(task -> Integer.valueOf(1).equals(task.algorithmId()));
        assertThat(state.projects.getFirst().completedSteps()).isZero();
        assertThat(state.projects.getFirst().deadline()).isEqualTo(JAVA.deadline());
        assertThat(state.windows).containsKey(1);
        assertThat(state.strictRemaining).isZero();

        var lesson = Planner.active(state, NOW);
        assertThat(lesson).isNotNull();
        assertThat(lesson.end()).isEqualTo(Planner.at(state, monday.plusDays(1), Planner.DAY_START));
        assertThat(planner.skipDay(state, monday, NOW)).contains("без штрафа");
        assertThat(planner.skipDay(state, monday.plusDays(1), NOW)).contains("уже использован");
        planner.resetStudy(state, monday, NOW);
        Instant tuesday = NOW.plusSeconds(86400);
        planner.evaluate(state, tuesday);
        assertThat(state.evaluatedDays).doesNotContainKey(monday);
        assertThat(Planner.active(state, tuesday)).isNotNull();
        assertThat(planner.skipDay(state, monday.plusDays(7), tuesday)).contains("без штрафа");
    }

    @Test
    void flexiblePlanIncludesTodayAndReplacesAlreadySolvedProblemsInPreparedLessons() {
        var json = JsonMapper.builder().build();
        var planner = new Planner();
        var state = new MentorState();
        var bot = new MentorBot(new StateStore(jdbc(), json), planner,
                new TelegramApi(json, "http://127.0.0.1:1", "", 0),
                new MentorModel(json, "http://127.0.0.1:1", "", ""), Clock.fixed(NOW, ZoneOffset.UTC), "", "", "");
        planner.resetStudy(state, Planner.date(state, NOW), NOW);
        var yesterday = state.lessons.getFirst();
        var today = state.lessons.get(1);
        int alreadySolved = today.tasks.get(1).algorithmId();
        state.solved.add(alreadySolved);
        for (int i = 0; i < yesterday.tasks.size(); i++) {
            yesterday.position++;
            yesterday.accepted++;
        }
        Instant tuesday = NOW.plusSeconds(86400);
        String plan = bot.respond(state, "/week", "", tuesday).text();
        assertThat(plan).startsWith("Свободный режим. Занимайся в любое время, учебный день — до 04:00.\n#2 · 2026-11-17")
                .contains("#8 · 2026-11-23").doesNotContain("#1 ·", "(повторение)", "Порядок:");
        assertThat(bot.respond(state, "/today", "", tuesday).text()).startsWith("Урок #2 · 2026-11-17");
        assertThat(state.lessons.stream().skip(1).map(lesson -> lesson.tasks.get(1).algorithmId()).toList())
                .doesNotContainNull().doesNotContain(alreadySolved).doesNotHaveDuplicates();
    }

    @Test
    void dailyRemindersFollowUnfinishedStepsAndRememberSentSlotsAcrossRestart() {
        var planner = new Planner();
        var json = JsonMapper.builder().build();
        var database = jdbc();
        var store = new StateStore(database, json);
        var day = LocalDate.of(2026, 11, 16);
        var morning = Instant.parse("2026-11-16T07:00:00Z");
        store.write(state -> {
            planner.resetStudy(state, day, morning);
            state.exactReminders = true;
            assertThat(planner.nextNotice(state, morning.minusSeconds(1))).isNull();
            var notice = planner.nextNotice(state, morning);
            assertThat(notice.text()).contains("Зачтено 0 из 4 шагов", "Шаг 1 · Теория");
            planner.markNotice(state, notice);
            return null;
        });
        var reopened = new StateStore(database, json);
        reopened.write(state -> {
            assertThat(planner.nextNotice(state, morning.plusSeconds(30))).isNull();
            var lesson = state.lessons.getFirst();
            lesson.started = true;
            lesson.position = 1;
            lesson.accepted = 1;
            var afternoon = morning.plusSeconds(4 * 3600);
            var notice = planner.nextNotice(state, afternoon);
            assertThat(notice.text()).contains("Зачтено 1 из 4 шагов", "Шаг 2 · Алгоритмы");
            planner.markNotice(state, notice);
            var evening = afternoon.plusSeconds(4 * 3600);
            lesson.pending = new MentorState.Attempt("Решение на проверке", evening.minusSeconds(5));
            assertThat(planner.nextNotice(state, evening)).isNull();
            lesson.pending = null;
            var last = evening.plusSeconds(2 * 3600 + 1800); // 20:30, the fourth reminder
            var lastNotice = planner.nextNotice(state, last);
            assertThat(lastNotice.flexibleSlot()).isEqualTo(4);
            planner.markNotice(state, lastNotice);
            assertThat(planner.nextNotice(state, last.plusSeconds(30))).isNull();
            lesson.position = 4;
            lesson.accepted = 4;
            assertThat(planner.nextNotice(state, last)).isNull();
            return null;
        });
    }

    @Test
    void dailyRemindersRespectPriorMorningNoticeAndPausedDays() {
        var planner = new Planner();
        var state = new MentorState();
        var morning = Instant.parse("2026-11-16T07:00:00Z");
        var day = Planner.date(state, morning);
        planner.resetStudy(state, day, morning);
        state.exactReminders = true;
        // A database from the previous version only has the morning flag.
        state.lessons.getFirst().startSent = true;
        assertThat(planner.nextNotice(state, morning)).isNull();
        var afternoon = morning.plusSeconds(4 * 3600);
        assertThat(planner.nextNotice(state, afternoon).flexibleSlot()).isEqualTo(2);
        assertThat(planner.nextNotice(state, afternoon.plusSeconds(3600))).isNull();
        planner.skipDay(state, day, afternoon);
        assertThat(planner.nextNotice(state, afternoon)).isNull();
        var nextMorning = morning.plusSeconds(86_400);
        planner.pause(state, day.plusDays(1), nextMorning);
        assertThat(planner.nextNotice(state, nextMorning)).isNull();
    }

    @Test
    void pauseStopsUnstartedFlexibleLessonAndShorteningReopensFutureLessons() {
        var state = new MentorState();
        var planner = new Planner();
        var monday = Planner.date(state, NOW);
        planner.resetStudy(state, monday, NOW);
        var today = state.lessons.getFirst();
        var tomorrow = state.lessons.get(1);
        var wednesday = state.lessons.get(2);
        planner.pause(state, monday.plusDays(2), NOW);
        assertThat(Planner.active(state, NOW)).isNull();
        assertThat(planner.nextNotice(state, NOW)).isNull();
        planner.pause(state, monday, NOW);
        assertThat(today.paused).isTrue();
        assertThat(tomorrow.paused).isFalse();
        assertThat(wednesday.paused).isFalse();
        var nextDay = NOW.plusSeconds(86_400);
        tomorrow.started = true;
        planner.pause(state, monday.plusDays(1), nextDay);
        assertThat(Planner.active(state, nextDay)).isSameAs(tomorrow);
    }

    @Test
    void preparesAndReadsLessonsBeforeStartWithoutCallingTheModel() {
        var json = JsonMapper.builder().build();
        var jdbc = jdbc();
        var store = new StateStore(jdbc, json);
        var planner = new Planner();
        store.write(state -> {
            state.windows.put(1, LocalTime.of(19, 0));
            planner.draft(state, NOW);
            planner.confirm(state, NOW);
            return null;
        });
        var restartedStore = new StateStore(jdbc, json);
        var bot = new MentorBot(restartedStore, planner, new TelegramApi(json, "http://127.0.0.1:1", "", 0),
                new MentorModel(json, "http://127.0.0.1:1", "", ""), Clock.fixed(NOW, ZoneOffset.UTC), "", "", "");
        var reply = restartedStore.write(state -> bot.respond(state, "/lesson", "1", NOW));
        assertThat(reply.text()).contains("Урок #1", "16.11 19:00", "https://metanit.com/java/tutorial/3.9.php",
                "Изучить:", "Граница чтения:", "✍️ Задание:", "Пришли:", "Two Sum");
        assertThat(restartedStore.read(state -> state.lessons.getFirst().started).booleanValue()).isFalse();
        assertThat(restartedStore.<Instant>read(state -> state.lessons.getFirst().start)).isEqualTo(NOW.plusSeconds(3600));
        var viaButton = restartedStore.write(state -> bot.respond(state,
                "/lesson:1:" + NOW.plusSeconds(3600).getEpochSecond(), "", NOW));
        assertThat(viaButton.text()).isEqualTo(reply.text());
        restartedStore.write(state -> {
            state.projects.add(JAVA);
            bot.respond(state, "/t04b", "2026-12-14", NOW);
            return null;
        });
        var project = restartedStore.read(state -> state.projects.getFirst());
        assertThat(project.deadline()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(project.completedSteps()).isEqualTo(2);
    }

    @Test
    void randomCheckinsPersistAndRespectDaysHoursPausesAndClosedProjects() {
        var json = JsonMapper.builder().build();
        var jdbc = jdbc();
        var store = new StateStore(jdbc, json);
        store.write(state -> {
            state.projects.add(JAVA);
            state.projects.add(new MentorState.Project("d01", "D01 Linux", "DevOps с Java", "", JAVA.deadline(), false, 0));
            state.pausedThrough = LocalDate.of(2026, 11, 17);
            for (int day = 1; day <= 7; day++) {
                state.windows.put(day, LocalTime.of(19, 0));
            }
            var planner = new Planner();
            planner.draft(state, NOW);
            planner.confirm(state, NOW);
            ProjectCheckins.schedule(state, NOW, new Random(42));
            return null;
        });
        var scheduled = store.read(state -> state.projectPings.stream().map(ping -> ping.scheduledAt).sorted().toList());
        assertThat(scheduled).hasSize(2);
        var restarted = new StateStore(jdbc, json);
        restarted.write(state -> { ProjectCheckins.schedule(state, NOW.plusSeconds(60), new Random(999)); return null; });
        assertThat(restarted.<List<Instant>>read(state -> state.projectPings.stream().map(ping -> ping.scheduledAt).sorted().toList()))
                .isEqualTo(scheduled);
        var state = restarted.read(item -> item);
        assertThat(state.projectPings).allMatch(ping -> ping.projectId.equals("t04b"));
        assertThat(scheduled.stream().map(time -> Planner.date(state, time)).distinct()).hasSize(2);
        for (var instant : scheduled) {
            assertThat(Planner.date(state, instant)).isAfter(state.pausedThrough);
            LocalTime time = instant.atZone(ZoneId.of(state.zone)).toLocalTime();
            assertThat(time).isAfterOrEqualTo(LocalTime.of(11, 0)).isBefore(LocalTime.of(18, 45));
        }
        var ping = ProjectCheckins.due(state, scheduled.getFirst());
        assertThat(ping).isNotNull();
        ping.sentAt = scheduled.getFirst();
        assertThat(ProjectCheckins.due(state, scheduled.getFirst().plusSeconds(10))).isNull();
        assertThat(ProjectCheckins.pending(state, scheduled.getFirst().plusSeconds(3600))).isSameAs(ping);
        assertThat(ProjectCheckins.pending(state, scheduled.getFirst().plusSeconds(86400))).isNull();
        state.projects.set(0, JAVA.close());
        assertThat(ProjectCheckins.due(state, scheduled.getLast())).isNull();
        assertThat(ProjectCheckins.pending(state, ping.sentAt)).isNull();
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static Map<String, Object> message(long id, String text) {
        return Map.of("update_id", id, "message", Map.of("from", Map.of("id", 123),
                "chat", Map.of("id", 123, "type", "private"), "text", text));
    }
}
