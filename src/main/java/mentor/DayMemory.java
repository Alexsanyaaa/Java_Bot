package mentor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mentor.MentorState.Exchange;

final class DayMemory {
    /**
     * 2026-11-24: the owner wants Oleg to know more. The chat sees the last {@link #CHAT_DAYS} study days, up to this
     * many characters (about 20k tokens); Groq still trims it to its own small budget in MentorModel.
     */
    static final int CHAT_BUDGET = 60_000;
    static final int CHAT_DAYS = 3;
    private static final java.time.format.DateTimeFormatter DAY_LABEL = java.time.format.DateTimeFormatter.ofPattern("dd.MM");
    private static final int SUBMISSION_BUDGET = 16_000;

    private DayMemory() {}

    static void remember(MentorState state, Instant at, String question, String answer, Long submissionLesson) {
        var day = Planner.date(state, at);
        state.conversation.removeIf(item -> item.at() == null
                || Planner.date(state, item.at()).isBefore(day.minusDays(CHAT_DAYS - 1)));
        state.conversation.add(new Exchange(question, answer, at, submissionLesson));
    }

    /**
     * The chat history for the model: today and the previous study days, newest first until the budget runs out.
     * Each earlier day starts with a marker line, so old lessons and promises are not taken for today's.
     */
    static List<Exchange> dialogue(MentorState state, Instant now) {
        var day = Planner.date(state, now);
        var first = day.minusDays(CHAT_DAYS - 1);
        var result = new ArrayList<Exchange>();
        int left = CHAT_BUDGET;
        java.time.LocalDate shown = null;
        for (var item : state.conversation.reversed()) {
            if (item.at() == null || item.at().isAfter(now)) continue;
            var itemDay = Planner.date(state, item.at());
            if (itemDay.isBefore(first) || itemDay.isAfter(day)) continue;
            int size = item.question().length() + item.answer().length() + 40;
            if (size > left) break;
            if (shown != null && !shown.equals(itemDay)) result.addFirst(marker(shown, day));
            result.addFirst(item);
            left -= size;
            shown = itemDay;
        }
        if (shown != null && !shown.equals(day)) result.addFirst(marker(shown, day));
        boolean talkedToday = result.stream().anyMatch(item -> item.at() != null && Planner.date(state, item.at()).equals(day));
        if (!result.isEmpty() && !talkedToday) result.add(new Exchange(
                "(— " + day.format(DAY_LABEL) + ", сегодняшний учебный день: сегодня пока не говорили —)", "", now, null));
        return result;
    }

    private static Exchange marker(java.time.LocalDate itemDay, java.time.LocalDate today) {
        String which = itemDay.equals(today) ? "сегодняшний учебный день"
                : itemDay.equals(today.minusDays(1)) ? "вчерашний учебный день, это уже прошло"
                : "прошлый учебный день, это уже прошло";
        return new Exchange("(— " + itemDay.format(DAY_LABEL) + ", " + which + " —)", "", null, null);
    }

    /** The mentor's latest message of the day before the given moment: the context a short reply refers to. */
    static String lastMentorMessage(MentorState state, Instant before) {
        var day = Planner.date(state, before);
        for (var item : state.conversation.reversed()) {
            if (item.at() == null || item.at().isAfter(before)) continue;
            if (!Planner.date(state, item.at()).equals(day)) break;
            if (!item.answer().isBlank()) return item.answer();
        }
        return "";
    }

    static List<String> earlierSubmissions(MentorState state, long lessonId, MentorState.Attempt current) {
        var day = Planner.date(state, current.submittedAt());
        var result = new ArrayList<String>();
        int left = SUBMISSION_BUDGET;
        for (var item : state.conversation.reversed()) {
            // Plain chat messages of the day count too: an answer written before the bot understood it is not lost.
            boolean chat = item.submissionLesson() == null && !item.question().isBlank()
                    && !item.question().startsWith("/") && item.question().length() >= 3;
            if (!chat && (item.submissionLesson() == null || item.submissionLesson() != lessonId)
                    || item.at() == null || !Planner.date(state, item.at()).equals(day)
                    || item.at().isAfter(current.submittedAt()) || current.text().contains(item.question())) continue;
            String text = chat ? "(сообщение из чата) " + item.question().replace("\n", "\n(сообщение из чата) ")
                    : item.question();
            if (result.contains(text)) continue;
            int size = text.length() + 40;
            if (size > left) break;
            result.addFirst(text);
            left -= size;
        }
        return result;
    }
}
