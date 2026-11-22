package mentor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import mentor.MentorState.Project;
import mentor.MentorState.Task;

/**
 * The School 21 mentor: leads T04B step by step to the deadline. It lives in the same bot as the daily
 * interview mentor and takes over the chat when the student presses «🏫 Школа 21».
 */
final class SchoolMentor {
    static final LocalTime NUDGE_FROM = LocalTime.of(20, 30);
    static final LocalTime NUDGE_UNTIL = LocalTime.of(22, 30);

    record Pace(int done, int total, long daysLeft, int behind) {
        int left() {
            return total - done;
        }

        double perDay() {
            return daysLeft <= 0 ? left() : (double) left() / daysLeft;
        }
    }

    private SchoolMentor() {}

    static Project project(MentorState state) {
        return state.projects.stream().filter(item -> LearningCatalog.T04B.equals(item.id()) && !item.closed())
                .findFirst().orElse(null);
    }

    /** The step to do now, or null when all T04B steps are done. */
    static Task currentStep(Project project) {
        return project.completedSteps() >= LearningCatalog.T04B_STEPS ? null
                : LearningCatalog.practice(project, project.completedSteps());
    }

    /** The even plan is counted from the first time the School 21 mentor is used. */
    static void ensurePlan(MentorState state, Instant now) {
        Project project = project(state);
        if (project != null && state.schoolPlanStart == null) {
            state.schoolPlanStart = Planner.date(state, now);
            state.schoolPlanStartSteps = project.completedSteps();
        }
    }

    static Pace pace(MentorState state, Project project, Instant now) {
        LocalDate today = Planner.date(state, now);
        int total = LearningCatalog.T04B_STEPS;
        int done = Math.min(project.completedSteps(), total);
        long daysLeft = project.deadline() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(today, project.deadline()));
        int behind = 0;
        if (state.schoolPlanStart != null && project.deadline() != null && project.deadline().isAfter(state.schoolPlanStart)) {
            double span = ChronoUnit.DAYS.between(state.schoolPlanStart, project.deadline());
            double elapsed = Math.min(span, Math.max(0, ChronoUnit.DAYS.between(state.schoolPlanStart, today)));
            double expected = state.schoolPlanStartSteps + (total - state.schoolPlanStartSteps) * elapsed / span;
            behind = Math.max(0, (int) Math.floor(expected) - done);
        }
        return new Pace(done, total, daysLeft, behind);
    }

    static String status(MentorState state, Instant now) {
        Project project = project(state);
        if (project == null) {
            return "🏫 Школа 21\nT04B не добавлен. Добавь: /t04b ГГГГ-ММ-ДД (дата дедлайна из кабинета).";
        }
        Pace pace = pace(state, project, now);
        var text = new StringBuilder("🏫 Школа 21 · T04B «Крестики-нолики»\n");
        text.append("До дедлайна (").append(project.deadline()).append(") — ").append(pace.daysLeft()).append(" дн. ")
                .append("Сделано ").append(pace.done()).append(" из ").append(pace.total()).append(" шагов.\n");
        Task step = currentStep(project);
        if (step == null) {
            return text.append("Все шаги пройдены. Осталось сдать проект на проверку.").toString();
        }
        text.append(paceLine(pace)).append("\n\nШаг ").append(pace.done() + 1).append(": ").append(step.prompt());
        String pages = step.resource().lines().findFirst().orElse("");
        if (!pages.isBlank()) text.append("\nПособие: ").append(pages);
        return text.append("\n\nСделал — напиши сюда, что получилось и как проверил. Вернуться к собесу — «📚 Собес».")
                .toString();
    }

    private static String paceLine(Pace pace) {
        double perDay = pace.perDay();
        long every = Math.max(1, Math.round(1 / Math.max(perDay, 0.01)));
        String rate = perDay > 1.2 ? String.format(java.util.Locale.ROOT, "~%.1f шага в день", perDay).replace('.', ',')
                : every == 1 ? "примерно шаг в день"
                : "шаг каждые " + every + (every < 5 ? " дня" : " дней");
        return "Нужный темп: " + rate + "."
                + (pace.behind() > 0 ? " Ты отстаёшь от плана на " + pace.behind() + " шаг(а)." : " Пока идёшь по плану.");
    }

    /** What the School 21 mentor knows: progress, the current step, the T04B requirements and the T03 code trace. */
    static String context(MentorState state, Instant now) {
        return "Сейчас " + now.atZone(ZoneId.of(state.zone)).toLocalDateTime().withNano(0) + " (" + state.zone + ").\n"
                + status(state, now) + "\n\n" + LearningCatalog.T04B_REQUIREMENTS + "\n" + LearningCatalog.T03_TRACE;
    }

    /** One evening reminder a day about the deadline; null when not due or nothing to remind about. */
    static String nudge(MentorState state, Instant now) {
        Project project = project(state);
        LocalDate today = Planner.date(state, now);
        LocalTime time = now.atZone(ZoneId.of(state.zone)).toLocalTime();
        if (project == null || currentStep(project) == null || today.equals(state.schoolNudgeDay)
                || today.equals(state.schoolStepDay) || Planner.isPaused(state, today)
                || time.isBefore(NUDGE_FROM) || !time.isBefore(NUDGE_UNTIL)) {
            return null;
        }
        Pace pace = pace(state, project, now);
        String opener = pace.behind() > 0
                ? "Школа 21: отстаёшь на " + pace.behind() + " шаг(а). Дедлайн через " + pace.daysLeft()
                        + " дн., а ты сделал " + pace.done() + " из " + pace.total() + ". Сам себя не догонишь — хотя бы один шаг сегодня."
                : "Школа 21: до дедлайна " + pace.daysLeft() + " дн., сделано " + pace.done() + " из " + pace.total()
                        + ". По плану, но запас тает. Шаг " + (pace.done() + 1) + " ждёт.";
        return opener + "\n\n" + currentStep(project).prompt();
    }
}
