package mentor;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.random.RandomGenerator;
import mentor.MentorState.Project;
import mentor.MentorState.ProjectPing;

final class ProjectCheckins {
    private ProjectCheckins() {}

    static boolean eligible(Project project) {
        String label = (project.name() + " " + project.description()).toLowerCase(Locale.ROOT);
        return !project.closed() && !label.contains("devops") && !label.contains("девопс") && !label.contains("d01")
                && (LearningCatalog.T04B.equals(project.id())
                || label.contains("java") || label.contains("spring") || label.contains("джава"));
    }

    static void schedule(MentorState state, Instant now, RandomGenerator random) {
        LocalDate today = Planner.date(state, now);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var projects = state.projects.stream().filter(ProjectCheckins::eligible)
                .sorted(Comparator.comparing(Project::deadline, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (projects.isEmpty() || monday.equals(state.projectPingWeek)) {
            return;
        }
        // Persist the choice once per week: polling and restarts never reroll the send time.
        state.projectPingWeek = monday;
        var days = new ArrayList<LocalDate>();
        for (LocalDate day = today; day.isBefore(monday.plusDays(7)); day = day.plusDays(1)) {
            if (!Planner.isPaused(state, day)) {
                days.add(day);
            }
        }
        int count = 0;
        long id = state.projectPings.stream().mapToLong(ping -> ping.id).max().orElse(0);
        while (!days.isEmpty() && count < 2) {
            LocalDate day = days.remove(random.nextInt(days.size()));
            var slots = new ArrayList<Instant>();
            for (int minute = 11 * 60; minute < 20 * 60; minute++) {
                Instant candidate = Planner.at(state, day, LocalTime.of(minute / 60, minute % 60));
                if (candidate.isAfter(now.plusSeconds(3600)) && state.lessons.stream()
                        .noneMatch(lesson -> !lesson.paused && lesson.flexibleEnd == null && !candidate.isBefore(lesson.start.minus(Planner.BEFORE))
                                && candidate.isBefore(lesson.end()))) {
                    slots.add(candidate);
                }
            }
            if (!slots.isEmpty()) {
                var ping = new ProjectPing();
                ping.id = ++id;
                ping.projectId = projects.get(count % projects.size()).id();
                ping.scheduledAt = slots.get(random.nextInt(slots.size()));
                state.projectPings.add(ping);
                count++;
            }
        }
    }

    static ProjectPing due(MentorState state, Instant now) {
        LocalDate today = Planner.date(state, now);
        var time = now.atZone(java.time.ZoneId.of(state.zone)).toLocalTime();
        if (Planner.isPaused(state, today) || time.isBefore(LocalTime.of(11, 0)) || !time.isBefore(LocalTime.of(21, 0))
                || pending(state, now) != null || state.lessons.stream().anyMatch(lesson -> !lesson.paused && lesson.flexibleEnd == null
                    && !now.isBefore(lesson.start.minus(Planner.BEFORE)) && now.isBefore(lesson.end()))) {
            return null;
        }
        return state.projectPings.stream().filter(ping -> ping.sentAt == null && !ping.scheduledAt.isAfter(now)
                        && Planner.date(state, ping.scheduledAt).equals(today) && project(state, ping) != null)
                .min(Comparator.comparing(ping -> ping.scheduledAt)).orElse(null);
    }

    static ProjectPing pending(MentorState state, Instant now) {
        return state.projectPings.stream().filter(ping -> ping.sentAt != null && ping.answeredAt == null
                        && Planner.date(state, ping.sentAt).equals(Planner.date(state, now)) && project(state, ping) != null)
                .max(Comparator.comparing(ping -> ping.sentAt)).orElse(null);
    }

    static Project project(MentorState state, ProjectPing ping) {
        return state.projects.stream().filter(item -> item.id().equals(ping.projectId) && eligible(item))
                .findFirst().orElse(null);
    }

    static String question(MentorState state, ProjectPing ping) {
        Project project = project(state, ping);
        return MentorTone.projectPing(ping.id) + "\n\nПроект: " + project.name()
                + (project.deadline() == null ? "" : ". Дедлайн: " + project.deadline())
                + ".\nЧто с прошлого раза сделал, как проверил и что мешает следующему шагу?"
                + "\nОтветь обычным сообщением до конца дня. Можно позже ответом на это сообщение или через /progress текст.";
    }

    static String context(MentorState state, ProjectPing ping, Instant now) {
        Project project = project(state, ping);
        if (project == null) {
            return "";
        }
        StringBuilder result = new StringBuilder("Проверка хода проекта #").append(ping.id)
                .append(". Проект: ").append(project.name()).append("\nУсловие: ").append(project.description())
                .append("\nДедлайн: ").append(project.deadline());
        if (project.deadline() != null) {
            result.append("; дней до дедлайна: ").append(ChronoUnit.DAYS.between(Planner.date(state, now), project.deadline()));
        }
        result.append("\nЗачтено проектных шагов: ").append(project.completedSteps())
                .append(". Школа 21 — дополнительная практика по 20 минут, основной трек — подготовка к собеседованию.");
        if (LearningCatalog.T04B.equals(project.id())) {
            result.append("\n").append(LearningCatalog.T03_CONTEXT).append('\n').append(LearningCatalog.T04B_REQUIREMENTS);
        }
        if (!state.latestProjectReport.isBlank() && LearningCatalog.T04B.equals(project.id())) {
            result.append("\nПоследняя запись о работе (").append(state.latestProjectReportDate)
                    .append("): ").append(state.latestProjectReport);
        }
        ProjectPing previous = state.projectPings.stream().filter(item -> item.id != ping.id
                        && item.projectId.equals(ping.projectId) && item.answeredAt != null)
                .max(Comparator.comparing(item -> item.answeredAt)).orElse(null);
        if (previous != null) {
            result.append("\nПоследний отчёт (").append(Planner.time(state, previous.answeredAt)).append("): ")
                    .append(previous.report).append("\nПредыдущая оценка: ").append(previous.assessment)
                    .append("\nУточнение/следующий шаг из разбора: ").append(previous.feedback);
        }
        Instant since = previous == null ? now.minusSeconds(7 * 86400) : previous.answeredAt;
        result.append("\nЗапланированные шаги за прошедший период:\n");
        state.lessons.stream().filter(lesson -> !lesson.paused && !lesson.start.isBefore(since) && !lesson.start.isAfter(now))
                .sorted(Comparator.comparing(lesson -> lesson.start)).limit(7).forEach(lesson ->
                        lesson.tasks.stream().filter(task -> project.id().equals(task.projectId())).forEach(task ->
                                result.append("- ").append(Planner.time(state, lesson.start)).append(": ")
                                        .append(task.prompt()).append("; ")
                                        .append(lesson.stepDone(lesson.tasks.indexOf(task)) ? "зачтено" : "зачёт не записан")
                                        .append('\n')));
        result.append("\nОтсутствие зачёта не доказывает отсутствие работы вне бота. Репозиторий бот не проверял.");
        if (!ping.report.isBlank()) {
            result.append("\nТекущий отчёт, ожидающий уточнения: ").append(ping.report)
                    .append("\nПоследний вопрос ментора: ").append(ping.feedback);
        }
        return result.toString();
    }
}
