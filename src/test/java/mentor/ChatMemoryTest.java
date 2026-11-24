package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import mentor.MentorState.Exchange;
import org.junit.jupiter.api.Test;

/** 2026-11-24: «пусть больше знает» — Oleg remembers the last three study days, older ones are marked with a date. */
class ChatMemoryTest {
    // Evenings in Moscow (UTC+3): 21:00 on 20, 21, 22 and 23 November.
    private static final Instant D20 = Instant.parse("2026-11-20T18:00:00Z");
    private static final Instant D21 = Instant.parse("2026-11-21T18:00:00Z");
    private static final Instant D22 = Instant.parse("2026-11-22T18:00:00Z");
    private static final Instant D23 = Instant.parse("2026-11-23T18:00:00Z");

    @Test
    void remembersThreeStudyDaysWithDateMarkers() {
        var state = new MentorState();
        DayMemory.remember(state, D20, "Четыре дня назад", "Старое", null);
        DayMemory.remember(state, D21, "Позавчера про HashMap", "Ответ", null);
        DayMemory.remember(state, D22, "Вчера застрял на JPA", "Ответ", null);
        DayMemory.remember(state, D23, "Сегодня привет", "Здорово", null);

        assertThat(state.conversation).extracting(Exchange::question).doesNotContain("Четыре дня назад");
        var questions = DayMemory.dialogue(state, D23.plusSeconds(60)).stream().map(Exchange::question).toList();
        assertThat(questions).containsExactly(
                "(— 21.11, прошлый учебный день, это уже прошло —)", "Позавчера про HashMap",
                "(— 22.11, вчерашний учебный день, это уже прошло —)", "Вчера застрял на JPA",
                "(— 23.11, сегодняшний учебный день —)", "Сегодня привет");
    }

    @Test
    void aLongDayDoesNotPushTheBudget() {
        var state = new MentorState();
        String big = "x".repeat(25_000);
        DayMemory.remember(state, D21, big, "", null);
        DayMemory.remember(state, D22, big, "", null);
        DayMemory.remember(state, D23, big, "", null);
        int total = DayMemory.dialogue(state, D23.plusSeconds(60)).stream()
                .mapToInt(item -> item.question().length() + item.answer().length()).sum();
        assertThat(total).isLessThanOrEqualTo(DayMemory.CHAT_BUDGET);
        assertThat(DayMemory.dialogue(state, D23.plusSeconds(60)).getLast().question()).isEqualTo(big);
    }
}
