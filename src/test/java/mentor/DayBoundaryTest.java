package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** In free mode the study day runs until 04:00 Moscow time, not midnight (requested on 2026-11-21). */
class DayBoundaryTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    private static final Instant EVENING = Instant.parse("2026-11-21T18:00:00Z"); // 21:00 Moscow
    private static final Instant TWO_AM = Instant.parse("2026-11-21T23:00:00Z"); // 02:00 Moscow
    private static final Instant FOUR_AM = Instant.parse("2026-11-22T01:00:00Z"); // 04:00 Moscow

    @Test
    void lessonStaysOpenUntilFourInTheMorning() {
        var json = JsonMapper.builder().build();
        var store = new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json);
        var planner = new Planner();
        var bot = new MentorBot(store, planner, new TelegramApi(json, "", "", 0),
                new MentorModel(json, "http://127.0.0.1:1", "test", ""), Clock.fixed(EVENING, ZoneOffset.UTC), "", "", "");
        var state = new MentorState();
        planner.resetStudy(state, DAY, EVENING);
        var lesson = state.lessons.getFirst();
        lesson.started = true;
        lesson.accepted = 1;
        lesson.position = 1;
        DayMemory.remember(state, EVENING, "Вечерний ответ", "Разбор", lesson.id);

        for (var point : List.of("2026-11-21T21:00:00Z", "2026-11-21T23:00:00Z", "2026-11-22T00:59:59Z")) {
            var now = Instant.parse(point);
            planner.evaluate(state, now);
            assertThat(Planner.date(state, now)).as(point).isEqualTo(DAY);
            assertThat(Planner.active(state, now)).as(point).isSameAs(lesson);
            assertThat(lesson.carried).as(point).isFalse();
            assertThat(state.evaluatedDays).as(point).doesNotContainKey(DAY);
            assertThat(DayMemory.dialogue(state, now)).as(point).isNotEmpty();
            assertThat(bot.respond(state, "/week", "", now).text()).as(point).contains("#1 · 2026-11-21");
        }
        assertThat(MentorContext.snapshot(state, "сколько осталось?", TWO_AM))
                .contains("кончается в 04:00", "Дедлайн урока #1: 22.11 04:00, осталось 2 ч 0 мин");

        bot.respond(state, "/submit", "Вот продолжение решения алгоритма после полуночи", TWO_AM);
        assertThat(lesson.pending).isNotNull();
        assertThat(lesson.pending.text()).contains("после полуночи");
        assertThat(state.lessons.get(1).pending).isNull();

        lesson.pending = null;
        lesson.position = lesson.tasks.size();
        lesson.accepted = lesson.tasks.size();
        planner.evaluate(state, FOUR_AM);
        assertThat(Planner.date(state, FOUR_AM)).isEqualTo(DAY.plusDays(1));
        assertThat(Planner.active(state, FOUR_AM)).isSameAs(state.lessons.get(1));
        assertThat(DayMemory.dialogue(state, FOUR_AM)).isEmpty();
        assertThat(Planner.label(state, lesson)).isEqualTo("2026-11-21 · сдать до 22.11 04:00");
        lesson.carried = true;
        lesson.flexibleEnd = lesson.flexibleEnd.plusSeconds(86_400);
        assertThat(Planner.label(state, lesson)).isEqualTo("2026-11-21 · долг · сдать до 23.11 04:00");

        state.flexible = false;
        assertThat(Planner.date(state, TWO_AM)).as("timed mode keeps midnight").isEqualTo(DAY.plusDays(1));
    }

    @Test
    void skippedDayKeepsTheStreakAndTheChatKnowsIt() {
        // The real state on 2026-11-21: four full days, a /skip on the 20th, two steps today.
        var state = new MentorState();
        new Planner().resetStudy(state, LocalDate.of(2026, 11, 16), Instant.parse("2026-11-16T07:00:00Z"));
        for (int day = 16; day <= 19; day++) state.stepsByDay.put(LocalDate.of(2026, 11, day), 4);
        state.skippedDays.add(LocalDate.of(2026, 11, 20));
        state.stepsByDay.put(DAY, 2);
        assertThat(Planner.streak(state, EVENING)).isEqualTo(5);
        assertThat(MentorContext.snapshot(state, "какой стрик?", EVENING))
                .contains("Стрик: 5 дн. подряд", "Сегодня зачтено шагов: 2", "стрик не рвёт", "не выдумывай");
    }

    @Test
    void remindersNeverTalkAboutMidnight() {
        // On 2026-11-22 a reminder still said the streak «сгорит в полночь» although the day ends at 04:00.
        for (int slot = 0; slot < 7; slot++) {
            for (long seed = 0; seed < 12; seed++) {
                for (int accepted = 0; accepted <= 3; accepted++) {
                    for (int today = 0; today <= 2; today++) {
                        var progress = new MentorTone.Progress(accepted, 4, "Теория", seed % 3 == 0, 5, today, 2,
                                2, seed % 2 == 0);
                        assertThat(MentorTone.dailyReminder(slot, progress, seed)).doesNotContainPattern("(?iu)полно|полуно");
                    }
                }
            }
        }
    }

    @Test
    void midnightLessonsMoveToFourKeepingCredit() {
        var planner = new Planner();
        var state = new MentorState();
        planner.resetStudy(state, DAY, EVENING);
        var old = state.lessons.getFirst();
        old.start = Planner.at(state, DAY, LocalTime.MIDNIGHT);
        old.flexibleEnd = Planner.at(state, DAY.plusDays(1), LocalTime.MIDNIGHT);
        old.started = true;
        old.position = 2;
        old.accepted = 2;
        var tasks = List.copyOf(old.tasks);

        planner.evaluate(state, TWO_AM);
        assertThat(Planner.date(state, old.start)).isEqualTo(DAY);
        assertThat(old.end()).isEqualTo(Planner.at(state, DAY.plusDays(1), LocalTime.of(4, 0)));
        assertThat(old.accepted).isEqualTo(2);
        assertThat(old.position).isEqualTo(2);
        assertThat(old.tasks).isEqualTo(tasks);

        var start = old.start;
        var end = old.end();
        planner.evaluate(state, TWO_AM);
        assertThat(old.start).isEqualTo(start);
        assertThat(old.end()).isEqualTo(end);
    }
}
