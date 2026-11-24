package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 2026-11-23: lesson #7 stuck at 3/4 (a Spring Boot practice step) held back lesson #8 — answers, reminders and /today
 * all went to the old lesson, and Oleg refused to «forget» it. Unfinished steps of earlier lessons are now optional.
 */
class OptionalTailTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 22);
    private static final Instant EVENING = Instant.parse("2026-11-22T19:00:00Z"); // 22:00 Moscow, lesson #1 day
    private static final Instant ROLLOVER = Instant.parse("2026-11-23T01:00:30Z"); // 04:00:30 Moscow, the day rolls over
    private static final Instant NEXT_NOON = Instant.parse("2026-11-23T09:00:00Z"); // 12:00 Moscow, the next study day

    @Test
    void todaysLessonComesFirstAndTheOldOneIsOptional() {
        var json = JsonMapper.builder().build();
        var store = new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json);
        var planner = new Planner();
        var bot = new MentorBot(store, planner, new TelegramApi(json, "", "", 0),
                new MentorModel(json, "http://127.0.0.1:1", "test", ""), Clock.fixed(NEXT_NOON, ZoneOffset.UTC), "", "", "");
        var state = new MentorState();
        planner.resetStudy(state, DAY, EVENING);
        state.exactReminders = true;
        var old = state.lessons.getFirst();
        old.started = true;
        old.position = 3;
        old.accepted = 3;
        state.stepsByDay.put(DAY, 3);

        planner.evaluate(state, ROLLOVER); // the bot checks every 8 s, so the tail is made right at 04:00
        planner.evaluate(state, NEXT_NOON);
        var today = state.lessons.get(1);
        assertThat(old.carried).isTrue();
        assertThat(Planner.tail(state, NEXT_NOON)).isSameAs(old);
        assertThat(Planner.active(state, NEXT_NOON)).isSameAs(today);
        assertThat(Planner.label(state, old)).contains("хвост, необязательно").doesNotContain("долг");

        // /today shows today's lesson and mentions the tail as optional.
        String todayText = bot.respond(state, "/today", "", NEXT_NOON).text();
        assertThat(todayText).startsWith("Урок #2").contains("Необязательный хвост: урок #1, шаг 4", "или забить");

        // Reminders talk about today's lesson only (the 18:00 slot).
        var notice = planner.nextNotice(state, Instant.parse("2026-11-23T15:05:00Z"));
        assertThat(notice.text()).contains("Урок #2", "Шаг 1").doesNotContain("Урок #1", "долг");

        // Oleg's notes: today's step is the current one; the tail is optional and may be dropped.
        String notes = MentorContext.snapshot(state, "скинь задание", NEXT_NOON);
        assertThat(notes).contains("ТЕКУЩИЙ ШАГ, его и давай на «скинь задание»: урок #2, шаг 1",
                "Необязательный хвост прошлого урока: урок #1, шаг 4", "соглашайся без нотаций", "Дедлайн урока #2:")
                .doesNotContain("(долг)");

        // An answer goes to today's lesson, not to the tail.
        bot.respond(state, "/submit", "Ответ на теорию сегодняшнего урока", NEXT_NOON);
        assertThat(today.pending).isNotNull();
        assertThat(old.pending).isNull();

        // Once today's lesson is done, the tail takes answers — still optional.
        today.pending = null;
        today.position = today.tasks.size();
        today.accepted = today.tasks.size();
        assertThat(bot.respond(state, "/today", "", NEXT_NOON).text()).startsWith("Урок #2");
        assertThat(MentorContext.snapshot(state, "скинь задание", NEXT_NOON))
                .contains("Сегодняшний урок закрыт. Остался только необязательный хвост",
                        "ТЕКУЩИЙ ШАГ, его и давай на «скинь задание»: урок #1, шаг 4");
        bot.respond(state, "/submit", "Код практики по прошлому уроку", NEXT_NOON);
        assertThat(old.pending).isNotNull();
    }

    @Test
    void anUntouchedDayTailSaysOneStepSavesTheDay() {
        var json = JsonMapper.builder().build();
        var store = new StateStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")), json);
        var planner = new Planner();
        var bot = new MentorBot(store, planner, new TelegramApi(json, "", "", 0),
                new MentorModel(json, "http://127.0.0.1:1", "test", ""), Clock.fixed(NEXT_NOON, ZoneOffset.UTC), "", "", "");
        var state = new MentorState();
        planner.resetStudy(state, DAY, EVENING);
        planner.evaluate(state, ROLLOVER);
        planner.evaluate(state, NEXT_NOON);
        assertThat(bot.respond(state, "/today", "", NEXT_NOON).text())
                .contains("Необязательный хвост: урок #1, шаг 1", "Хоть один шаг из него — и тот день не засчитается пропуском");
    }
}
