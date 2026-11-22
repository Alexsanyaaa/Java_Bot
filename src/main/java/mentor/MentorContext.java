package mentor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import mentor.MentorState.Lesson;
import mentor.MentorState.Task;

final class MentorContext {
    private static final Pattern LESSON_NUMBER = Pattern.compile("(?:[#№]\\s*|урок(?:а|е|у)?\\s+)([0-9]{1,12})(?![0-9])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LESSON_PROGRESS = Pattern.compile(
            "(?iu)(?:зач[её]т|зачт|закрыт|выполнен|прогресс|статус|сколько\\s+шагов)");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private MentorContext() {}

    static String progressReply(MentorState state, String message, Instant now) {
        // Only a plain question about the status; a quote of the bot's own text («Урок #4. Зачтено…») is a complaint
        // for the chat to answer, not a status request.
        if (message.length() > 180 || !LESSON_PROGRESS.matcher(message).find() || !message.contains("?")
                || message.matches("(?s).*[\"«»“”].*")) return null;
        var number = LESSON_NUMBER.matcher(message);
        if (!number.find()) return null;
        long id = Long.parseLong(number.group(1));
        if (number.find()) return null;
        Lesson lesson = Planner.byId(state, id);
        if (lesson == null) return "Урок #" + id + " в плане не найден.";
        String reply = "Урок #" + id + ". " + progress(lesson) + ".";
        Task remaining = lesson.currentTask();
        if (remaining == null) return reply;
        reply += "\nШаг " + (lesson.position + 1) + " («" + remaining.type() + "») "
                + (lesson.pending == null ? "ждёт зачёта." : "на проверке.");
        if (!lesson.end().isAfter(now)) reply += " Учебное окно закончилось.";
        return reply;
    }

    static String progress(Lesson lesson) {
        return "Зачтено " + lesson.accepted + " из " + lesson.tasks.size() + " шагов";
    }

    static String snapshot(MentorState state, String message, Instant now) {
        ZoneId zone = ZoneId.of(state.zone);
        LocalDate today = Planner.date(state, now);
        StringBuilder data = new StringBuilder("Сейчас ").append(format(now, zone))
                .append(" (").append(zone).append(").\n");
        if (state.flexible) {
            // The models counted «до полуночи» on their own; give them the real deadline and the time left.
            Lesson due = state.lessons.stream().filter(lesson -> !lesson.paused && lesson.currentTask() != null
                    && !lesson.start.isAfter(now) && lesson.end().isAfter(now)).findFirst().orElse(null);
            data.append("Учебный день кончается в 04:00 ночи, а не в полночь.");
            if (due != null) {
                long minutes = java.time.Duration.between(now, due.end()).toMinutes();
                data.append(" Дедлайн урока #").append(due.id).append(due.carried ? " (долг)" : "").append(": ")
                        .append(Planner.deadline(state, due)).append(", осталось ").append(minutes / 60).append(" ч ")
                        .append(minutes % 60).append(" мин.");
            }
            data.append(" Про дедлайн говори «до 04:00» или «до четырёх утра»; слова «полночь» и «до полуночи» про"
                    + " дедлайн и стрик не используй.\n");
        }
        // Without these facts the chat invented its own streak rules ("пропустил — сгорел") on 2026-11-21.
        int streak = Planner.streak(state, now);
        data.append("Стрик: ").append(streak).append(" дн. подряд. Сегодня зачтено шагов: ")
                .append(Planner.todaySteps(state, now)).append(" (для стрика нужно ").append(Planner.STREAK_STEPS)
                .append("). Правила: день идёт в стрик, если за него зачтено ").append(Planner.STREAK_STEPS)
                .append("+ шага; пропуск без штрафа (/skip, раз в неделю) — нейтральный день, стрик не рвёт и урок не "
                        + "переносит; стрик сгорает, только если день не пропущен официально и в нём меньше ")
                .append(Planner.STREAK_STEPS).append(" зачтённых шагов. Пропуск на этой неделе: ")
                .append(Planner.skipUsed(state, today) ? "уже использован" : "доступен")
                .append(". Других правил про стрик и пропуски нет — не выдумывай.\n");
        if (state.resetFrom != null && today.isBefore(state.resetFrom)) {
            data.append("Учёба возобновится ").append(state.resetFrom).append(".\n");
        } else if (state.skippedDays.contains(today)) {
            data.append("Сегодня учебный день пропущен без штрафа.\n");
        } else if (Planner.isPaused(state, today)) {
            data.append("Расписание на паузе по ").append(state.pausedThrough).append(" включительно.\n");
        }
        var week = state.lessons.stream()
                .filter(lesson -> {
                    LocalDate date = Planner.date(state, lesson.start);
                    return !date.isBefore(today) && date.isBefore(today.plusDays(7));
                })
                .sorted(Comparator.comparing(lesson -> lesson.start))
                .toList();
        Set<Long> detailed = new LinkedHashSet<>();
        var requested = LESSON_NUMBER.matcher(message);
        while (requested.find() && detailed.size() < 3) {
            detailed.add(Long.parseLong(requested.group(1)));
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        LocalDate selectedDay = lower.contains("послезавтра") ? today.plusDays(2)
                : lower.contains("завтра") ? today.plusDays(1) : today;
        if (detailed.isEmpty()) {
            week.stream().filter(lesson -> Planner.date(state, lesson.start).equals(selectedDay))
                    .limit(1).forEach(lesson -> detailed.add(lesson.id));
        }
        if (detailed.isEmpty()) {
            week.stream().filter(lesson -> !lesson.paused && lesson.currentTask() != null && lesson.end().isAfter(now))
                    .limit(1).forEach(lesson -> detailed.add(lesson.id));
        }
        // Say plainly which step is open now: «скинь задание» must get this step, not any other.
        Lesson open = state.lessons.stream().filter(lesson -> !lesson.paused && lesson.currentTask() != null
                && !lesson.start.isAfter(now) && lesson.end().isAfter(now)).findFirst().orElse(null);
        if (open != null) {
            Task current = open.currentTask();
            data.append("ТЕКУЩИЙ ШАГ, его и давай на «скинь задание»: урок #").append(open.id).append(", шаг ")
                    .append(open.position + 1).append(" из ").append(open.tasks.size()).append(" («").append(current.type())
                    .append("»): ").append(LearningCatalog.exercise(current)).append('\n');
        }
        data.append("Подтверждённые уроки с сегодняшнего дня на семь дней вперёд:\n");
        if (week.isEmpty()) {
            data.append("Подтверждённых уроков в этом интервале нет.\n");
        }
        Set<Long> included = new LinkedHashSet<>();
        for (Lesson lesson : week) {
            appendLesson(data, lesson, state, zone, now, detailed.contains(lesson.id));
            included.add(lesson.id);
        }
        var recent = state.lessons.stream().filter(lesson -> lesson.accepted > 0
                        && Planner.date(state, lesson.start).isBefore(today))
                .sorted(Comparator.comparing((Lesson lesson) -> lesson.start).reversed()).limit(7).toList();
        if (!recent.isEmpty()) {
            data.append("Зачтённые шаги предыдущих уроков (до семи последних):\n");
            for (var lesson : recent) {
                data.append('#').append(lesson.id).append(" · ").append(Planner.date(state, lesson.start))
                        .append(" · ").append(lesson.accepted).append('/').append(lesson.tasks.size())
                        .append(lesson.selfChecked == 0 ? "" : " (самопроверка: " + lesson.selfChecked + ")").append(": ");
                for (int i = 0; i < Math.min(lesson.position, lesson.tasks.size()); i++) {
                    Task task = lesson.tasks.get(i);
                    if (i > 0) data.append("; ");
                    data.append(shortTask(task));
                }
                data.append('\n');
            }
        }
        data.append("Он уже решил задач LeetCode (просто знай, «база» не упоминай): ").append(state.solved.size()).append(". Номера: ")
                .append(state.solved.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")))
                .append(".\n");
        // «Дай задачу» must hit an unsolved one, not Two Sum for the third time.
        var unsolved = LearningCatalog.unsolvedProblems(state, 5);
        data.append(unsolved.isEmpty() ? "Все задачи каталога решены — предложи задачу с LeetCode по теме, но назови номер.\n"
                : "Если просит задачу — бери отсюда, это нерешённые (по порядку), и подавай по-своему:\n- "
                        + String.join("\n- ", unsolved) + "\n");
        if (!state.skills.isEmpty()) {
            data.append("Последние отметки по навыкам (внутренние коды тем):\n");
            state.skills.entrySet().stream().sorted(java.util.Map.Entry.<String, MentorState.Skill>comparingByValue(
                    Comparator.comparing(MentorState.Skill::reviewOn).reversed())).limit(12).forEach(entry ->
                    data.append("- ").append(entry.getKey()).append(": ")
                            .append(entry.getValue().demonstrated() ? "подтверждён" : "есть пробел")
                            .append(", повтор ").append(entry.getValue().reviewOn()).append('\n'));
        }
        var refs = LESSON_NUMBER.matcher(message);
        int references = 0;
        while (refs.find() && references < 3) {
            long id = Long.parseLong(refs.group(1));
            if (included.add(id)) {
                references++;
                Lesson lesson = Planner.byId(state, id);
                if (lesson == null) {
                    data.append("Урок #").append(id).append(" в сохранённом расписании не найден.\n");
                } else {
                    data.append("Урок по запросу вне указанной недели:\n");
                    appendLesson(data, lesson, state, zone, now, true);
                }
            }
        }
        if (!state.draft.isEmpty()) {
            data.append("Неподтверждённый черновик (/confirm подтверждает и включает напоминания):\n");
            state.draft.forEach((day, time) -> {
                Instant start = Planner.at(state, day, time);
                if (start.isAfter(now)) {
                    data.append("- ").append(format(start, zone)).append('\n');
                }
            });
        }
        var projects = state.projects.stream().filter(project -> !project.closed()).limit(8).toList();
        data.append("Школа 21 — дополнительная практика. ").append(LearningCatalog.T03_CONTEXT).append('\n');
        if (projects.isEmpty()) {
            data.append("Активных проектов нет.\n");
        } else {
            data.append("Активные проекты:\n");
            for (var project : projects) {
                data.append("- ").append(project.name()).append(" · дедлайн ").append(project.deadline())
                        .append(" · зачтённых шагов ").append(project.completedSteps()).append('\n');
            }
        }
        // The daily mentor does not carry the School 21 project notes (T04B requirements, T03 trace):
        // they cost thousands of tokens per message and belong to a separate project assistant.
        return data.toString();
    }

    private static void appendLesson(StringBuilder data, Lesson lesson, MentorState state, ZoneId zone, Instant now, boolean detailed) {
        data.append('#').append(lesson.id).append(" · ")
                .append(lesson.flexibleEnd == null ? format(lesson.start, zone) + "–" + format(lesson.end(), zone)
                        : Planner.label(state, lesson)).append(" · ").append(status(lesson, now))
                .append(" · зачтено ").append(lesson.accepted).append(" из ").append(lesson.tasks.size()).append('\n');
        if (lesson.strict && !lesson.paused && lesson.end().isAfter(now)) {
            data.append("  Строгое окно с ").append(format(lesson.start.minus(Planner.BEFORE), zone)).append('\n');
        }
        for (int index = 0; index < lesson.tasks.size(); index++) {
            Task task = lesson.tasks.get(index);
            data.append("  ").append(index + 1).append(". ").append(task.type())
                    .append(" (").append(task.minutes()).append(" мин)");
            if (task.projectId() != null && state.projects.stream()
                    .noneMatch(project -> project.id().equals(task.projectId()) && !project.closed())) {
                data.append(" · шаг закрытого проекта\n");
                continue;
            }
            if (index < lesson.position) {
                data.append(" · зачтено");
            } else if ((lesson.started || lesson.flexibleEnd != null && !lesson.start.isAfter(now))
                    && index == lesson.position && lesson.end().isAfter(now)) {
                data.append(lesson.pending == null ? " · текущий шаг" : " · ответ на проверке");
            } else {
                data.append(" · без зачёта");
            }
            data.append(" — ").append(detailed ? task.prompt() : shortTask(task));
            if (detailed && !task.resource().isBlank()) {
                data.append(" | Материал: ").append(task.resource());
            }
            data.append('\n');
        }
        if (!detailed) {
            data.append("  Полная карточка со ссылками доступна через /lesson ").append(lesson.id).append('\n');
        }
    }

    private static String shortTask(Task task) {
        return task.prompt().split("[.\\n]", 2)[0];
    }

    private static String status(Lesson lesson, Instant now) {
        if (lesson.paused) {
            return "отменён на время паузы";
        }
        if (lesson.currentTask() == null) {
            return "выполнен";
        }
        if (lesson.pending != null) {
            return "ответ ожидает проверки";
        }
        if (!lesson.end().isAfter(now)) {
            return lesson.accepted == 0 ? "окно закончилось без зачёта" : "выполнен частично";
        }
        if (lesson.started) {
            return "начат";
        }
        return lesson.start.isAfter(now) ? "запланирован" : "окно открыто, урок не начат";
    }

    private static String format(Instant instant, ZoneId zone) {
        return DATE_TIME.format(instant.atZone(zone));
    }
}
