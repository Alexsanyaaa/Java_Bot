package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import mentor.MentorState.Skill;
import org.junit.jupiter.api.Test;

/**
 * 2026-11-24: «он меня каждый день спрашивает про хэшкод один и тот же вопрос». The weak, never answered
 * collections topic stayed the «weakest» forever, so the question of the day and the mini interview repeated it.
 */
class QuestionRotationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    private static MentorState studied() {
        var state = new MentorState();
        state.skills.put("collections", new Skill(false, DAY.minusDays(5)));
        for (String id : List.of("interfaces", "exceptions", "atomicity", "sql", "di"))
            state.skills.put(id, new Skill(true, DAY.minusDays(1)));
        return state;
    }

    @Test
    void ignoredQuestionOfTheDayDoesNotComeBackEveryDay() {
        var state = studied();
        var asked = new ArrayList<String>();
        for (int day = 0; day < 8; day++) {
            LocalDate today = DAY.plusDays(day);
            String topic = MentorBot.recallTopic(state, today);
            // What maybeSendRecall does; the student never answers.
            state.recallDay = today;
            state.recallTopic = topic;
            state.askedOn.put(topic, today);
            asked.add(topic);
        }
        assertThat(asked.getFirst()).isEqualTo("collections"); // the weak topic still goes first
        for (int i = 0; i < asked.size(); i++)
            for (int j = i + 1; j < Math.min(asked.size(), i + LearningCatalog.ASK_REST_DAYS); j++)
                assertThat(asked.get(j)).as("day %d repeats day %d: %s", j, i, asked).isNotEqualTo(asked.get(i));
        assertThat(asked.stream().filter("collections"::equals).count()).isBetween(1L, 2L);
    }

    @Test
    void oldUnansweredRecallCountsAsAskedAfterTheUpdate() {
        // Production state before the fix: no askedOn yet, yesterday's hashCode question still open.
        var state = studied();
        state.recallDay = DAY.minusDays(1);
        state.recallTopic = "collections";
        assertThat(MentorBot.recallTopic(state, DAY)).isNotEqualTo("collections");
    }

    @Test
    void questionOfTheDaySkipsTodaysLessonInterview() {
        var state = studied();
        state.askedOn.put("collections", DAY.minusDays(10));
        var lessonInterview = LearningCatalog.interview(state, DAY, "sql");
        var lesson = new MentorState.Lesson();
        lesson.start = Planner.at(state, DAY, java.time.LocalTime.of(10, 0));
        lesson.tasks = new ArrayList<>(List.of(LearningCatalog.theoryForTopic("sql"), lessonInterview, lessonInterview));
        state.lessons.add(lesson);
        assertThat(LearningCatalog.topicsIn(lessonInterview)).isNotEmpty()
                .doesNotContain(MentorBot.recallTopic(state, DAY));
    }

    @Test
    void miniInterviewReviewSlotsRotate() {
        var state = studied();
        state.askedOn.put("collections", DAY.minusDays(1));
        state.askedOn.put("interfaces", DAY.minusDays(2));
        var topics = LearningCatalog.topicsIn(LearningCatalog.interview(state, DAY, "sql"));
        assertThat(topics).contains("sql").doesNotContain("collections", "interfaces").hasSize(3);
    }
}
