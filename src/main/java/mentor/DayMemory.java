package mentor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mentor.MentorState.Exchange;

final class DayMemory {
    private static final int CHAT_BUDGET = 20_000;
    private static final int SUBMISSION_BUDGET = 16_000;

    private DayMemory() {}

    static void remember(MentorState state, Instant at, String question, String answer, Long submissionLesson) {
        var day = Planner.date(state, at);
        state.conversation.removeIf(item -> item.at() == null
                || Planner.date(state, item.at()).isBefore(day.minusDays(1)));
        state.conversation.add(new Exchange(question, answer, at, submissionLesson));
    }

    static List<Exchange> dialogue(MentorState state, Instant now) {
        var day = Planner.date(state, now);
        var result = new ArrayList<Exchange>();
        int left = CHAT_BUDGET;
        for (var item : state.conversation.reversed()) {
            if (item.at() == null || !Planner.date(state, item.at()).equals(day)) continue;
            int size = item.question().length() + item.answer().length() + 40;
            if (size > left) break;
            result.addFirst(item);
            left -= size;
        }
        return result;
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
