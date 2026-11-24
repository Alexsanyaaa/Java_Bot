package mentor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import mentor.MentorState.Lesson;
import mentor.MentorState.Project;
import mentor.MentorState.Task;
import org.springframework.stereotype.Component;

@Component
final class Planner {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    static final Duration BEFORE = Duration.ofMinutes(15);
    static final Duration FOLLOWUP = Duration.ofMinutes(30);
    static final Duration ACTIVITY_DELAY = Duration.ofMinutes(10);
    static final Duration SLOT = Duration.ofHours(2);
    /**
     * Free-mode reminders: evening and night only, denser toward the 04:00 deadline. Slots before 04:00 belong to the
     * night after the study day. The owner studies only in the evening (2026-11-23); before that it was 10:00–23:30.
     */
    static final List<LocalTime> DAILY_REMINDERS = List.of(
            LocalTime.of(18, 0), LocalTime.of(20, 0), LocalTime.of(21, 30), LocalTime.of(22, 30),
            LocalTime.of(23, 30), LocalTime.of(0, 30), LocalTime.of(1, 30), LocalTime.of(2, 30));
    /** Strict hours in free mode: Mentor Focus blocks distracting apps in this window. */
    static final LocalTime FOCUS_FROM = LocalTime.of(19, 0);
    static final LocalTime FOCUS_TO = LocalTime.of(21, 0);
    static final int STREAK_STEPS = 2;
    /** In free mode a study day runs from 04:00 to 04:00: an answer sent at 2 a.m. still counts for the evening before. */
    static final LocalTime DAY_START = LocalTime.of(4, 0);
    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    record Notice(long lessonId, Instant start, String kind, String text, int flexibleSlot) {
        Notice(long lessonId, Instant start, String kind, String text) {
            this(lessonId, start, kind, text, 0);
        }
    }

    void resetStudy(MentorState state, LocalDate from, Instant now) {
        if (state.resetFrom != null && !from.isAfter(state.resetFrom)) return;
        state.resetFrom = from;
        state.flexible = true;
        state.draft.clear();
        state.lessons.clear();
        state.projects.replaceAll(project -> project.closed() ? project : new Project(project.id(), project.name(),
                project.description(), project.cloneUrl(), project.deadline(), false, 0));
        state.feedbackQueue.clear();
        state.conversation.clear();
        state.skills.clear();
        state.knownTopics.clear();
        state.evaluatedDays.clear();
        state.skippedDays.clear();
        state.career.clear();
        state.devops.clear();
        state.projectPings.clear();
        state.projectPingWeek = null;
        state.weeklySent = weekOf(date(state, now));
        state.latestProjectReport = "";
        state.latestProjectReportDate = null;
        state.lastProjectGitHead = "";
        state.interviewTopic = "";
        state.interviewTurn = 0;
        state.pendingCommandReply = null;
        state.pausedThrough = null;
        state.strictRemaining = 0;
        prepareFlexible(state, now);
    }

    void prepareFlexible(MentorState state, Instant now) {
        if (!state.flexible) return;
        migrateFlexibleDays(state, now);
        LocalDate today = date(state, now);
        LocalDate first = state.resetFrom != null && state.resetFrom.isAfter(today) ? state.resetFrom : today;
        long id = state.lessons.stream().mapToLong(lesson -> lesson.id).max().orElse(0);
        int index = (int) state.lessons.stream().filter(lesson -> !lesson.paused).count();
        Set<Integer> problems = reservedAlgorithms(state, now);
        Set<String> topics = new HashSet<>();
        boolean added = false;
        for (int offset = 0; offset < 7; offset++) {
            LocalDate day = first.plusDays(offset);
            if (isPaused(state, day) || state.lessons.stream().anyMatch(lesson -> date(state, lesson.start).equals(day))) continue;
            Lesson lesson = new Lesson();
            lesson.id = ++id;
            lesson.start = at(state, day, DAY_START);
            lesson.flexibleEnd = at(state, day.plusDays(1), DAY_START);
            lesson.tasks = new ArrayList<>(LearningCatalog.day(state, day, index++, problems, topics));
            lesson.tasks.add(LearningCatalog.dailyPractice(lesson.tasks.getFirst().topic()));
            state.lessons.add(lesson);
            added = true;
        }
        if (added) refreshProjects(state, now);
    }

    /** Free-mode lessons created before the 04:00 boundary started at midnight; they move to 04:00 keeping their credit. */
    private static void migrateFlexibleDays(MentorState state, Instant now) {
        ZoneId zone = ZoneId.of(state.zone);
        for (Lesson lesson : state.lessons) {
            if (lesson.flexibleEnd == null) continue;
            var oldStart = lesson.start.atZone(zone);
            if (!oldStart.toLocalTime().equals(LocalTime.MIDNIGHT)) continue;
            LocalDate day = oldStart.toLocalDate();
            lesson.start = at(state, day, DAY_START);
            lesson.flexibleEnd = at(state, lesson.flexibleEnd.atZone(zone).toLocalDate(), DAY_START);
            if (lesson.end().isAfter(now) && lesson.windowClosed) {
                lesson.windowClosed = false;
                state.evaluatedDays.remove(day);
            }
        }
    }

    static LocalDate weekOf(LocalDate day) {
        return day.minusDays(day.getDayOfWeek().getValue() - 1L);
    }

    static boolean skipUsed(MentorState state, LocalDate day) {
        return state.skippedDays.stream().anyMatch(skipped -> weekOf(skipped).equals(weekOf(day)));
    }

    String skipDay(MentorState state, LocalDate day, Instant now) {
        if (day.isBefore(date(state, now))) return "Прошедший день пропустить уже нельзя.";
        if (skipUsed(state, day)) return "Пропуск на эту неделю уже использован.";
        var lessons = state.lessons.stream().filter(lesson -> !lesson.paused && date(state, lesson.start).equals(day)).toList();
        if (lessons.isEmpty() || lessons.stream().noneMatch(lesson -> lesson.end().isAfter(now))) {
            return "На эту дату нет открытого учебного дня.";
        }
        if (lessons.stream().anyMatch(lesson -> lesson.accepted > 0 || lesson.pending != null)) {
            return "На этот день уже есть результат или ответ на проверке.";
        }
        state.skippedDays.add(day);
        lessons.forEach(lesson -> { lesson.paused = true; lesson.strict = false; });
        state.draft.remove(day);
        assignStrict(state, now);
        refreshProjects(state, now);
        return "Учебный день " + day + " пропущен без штрафа. Новый пропуск будет доступен с понедельника.";
    }

    void draft(MentorState state, Instant now) {
        state.draft.clear();
        LocalDate today = date(state, now);
        for (int i = 0; i < 7; i++) {
            LocalDate day = today.plusDays(i);
            LocalTime time = state.windows.get(day.getDayOfWeek().getValue());
            if (time != null && at(state, day, time).isAfter(now)
                    && !isPaused(state, day)
                    && state.lessons.stream().noneMatch(lesson -> date(state, lesson.start).equals(day))) {
                state.draft.put(day, time);
            }
        }
    }

    int confirm(MentorState state, Instant now) {
        int created = 0;
        long nextId = state.lessons.stream().mapToLong(lesson -> lesson.id).max().orElse(0);
        Set<Integer> chosen = reservedAlgorithms(state, now);
        var topics = new java.util.HashSet<String>();
        for (var entry : state.draft.entrySet()) {
            LocalDate day = entry.getKey();
            Instant start = at(state, day, entry.getValue());
            if (!start.isAfter(now) || isPaused(state, day) || !isFree(state, start, 0)) {
                continue;
            }
            Lesson lesson = new Lesson();
            lesson.id = ++nextId;
            lesson.start = start;
            int index = Math.toIntExact(nextId - 1);
            lesson.tasks = new ArrayList<>(LearningCatalog.day(state, day, index, chosen, topics));
            lesson.tasks.add(LearningCatalog.dailyPractice(lesson.tasks.getFirst().topic()));
            state.lessons.add(lesson);
            created++;
        }
        state.draft.clear();
        refreshProjects(state, now);
        assignStrict(state, now);
        return created;
    }

    /**
     * The last step of every open daily lesson is hands-on practice on the lesson's topic. School 21 (T04B)
     * no longer sits inside daily lessons: it has its own mode. A step the student is already on stays as is.
     */
    void refreshProjects(MentorState state, Instant now) {
        state.lessons.stream().filter(lesson -> !lesson.paused && lesson.end().isAfter(now)).forEach(lesson -> {
            int last = lesson.tasks.size() - 1;
            if (lesson.position > last || lesson.position == last && (lesson.started || lesson.pending != null)) return;
            lesson.tasks.set(last, LearningCatalog.dailyPractice(lesson.tasks.getFirst().topic()));
        });
    }

    boolean isFree(MentorState state, Instant start, long excludedId) {
        return state.lessons.stream().filter(lesson -> !lesson.paused && lesson.id != excludedId)
                .noneMatch(lesson -> lesson.start.isBefore(start.plus(SLOT)) && lesson.end().isAfter(start));
    }

    void pause(MentorState state, LocalDate through, Instant now) {
        LocalDate previous = state.pausedThrough;
        state.pausedThrough = through;
        for (Lesson lesson : state.lessons) {
            LocalDate day = date(state, lesson.start);
            if (lesson.started || lesson.pending != null || !lesson.end().isAfter(now) || state.skippedDays.contains(day)) continue;
            if (!day.isAfter(through)) {
                lesson.paused = true;
                lesson.strict = false;
            } else if (previous != null && !day.isAfter(previous)) {
                lesson.paused = false;
            }
        }
        assignStrict(state, now);
        refreshProjects(state, now);
    }

    static Set<Integer> reservedAlgorithms(MentorState state, Instant now) {
        Set<Integer> problems = new HashSet<>();
        state.lessons.stream().filter(lesson -> !lesson.paused && lesson.end().isAfter(now))
                .flatMap(lesson -> lesson.tasks.stream()).map(Task::algorithmId)
                .filter(Objects::nonNull).forEach(problems::add);
        return problems;
    }

    void evaluate(MentorState state, Instant now) {
        if (state.flexible) migrateFlexibleDays(state, now);
        carryDebt(state, now);
        prepareFlexible(state, now);
        updateCurriculum(state, now);
        refreshAlgorithms(state, now);
        refreshProjects(state, now);
        LocalDate today = date(state, now);
        List<LocalDate> finished = state.lessons.stream()
                .filter(lesson -> !lesson.paused)
                .map(lesson -> date(state, lesson.start))
                .filter(day -> day.isBefore(today))
                .distinct().sorted().toList();
        for (LocalDate day : finished) {
            if (state.evaluatedDays.containsKey(day)) {
                continue;
            }
            List<Lesson> dayLessons = state.lessons.stream()
                    .filter(lesson -> !lesson.paused && date(state, lesson.start).equals(day)).toList();
            if (dayLessons.stream().anyMatch(lesson -> lesson.end().isAfter(now))) continue;
            if (dayLessons.stream().anyMatch(lesson -> lesson.accepted == 0 && lesson.pending != null)) {
                continue;
            }
            boolean missed = dayLessons.stream().noneMatch(lesson -> lesson.accepted > 0);
            state.evaluatedDays.put(day, missed);
            long misses = state.evaluatedDays.entrySet().stream()
                    .filter(entry -> entry.getValue() && !entry.getKey().isBefore(day.minusDays(6))
                            && !entry.getKey().isAfter(day)).count();
            if (missed && dayLessons.stream().anyMatch(lesson -> lesson.strict)) {
                state.strictRemaining += 2;
            } else if (missed && misses == 2) {
                state.strictRemaining = Math.max(3, state.strictRemaining);
            } else if (missed && state.flexible) {
                state.strictRemaining = Math.max(2, state.strictRemaining);
            }
        }
        for (Lesson lesson : state.lessons) {
            if (lesson.strict && !lesson.strictCounted && !focusEnd(state, lesson).isAfter(now)) {
                lesson.strictCounted = true;
                state.strictRemaining = Math.max(0, state.strictRemaining - 1);
            }
        }
        assignStrict(state, now);
        boolean newlyExpired = state.lessons.stream()
                .anyMatch(lesson -> !lesson.paused && !lesson.end().isAfter(now) && !lesson.windowClosed);
        if (newlyExpired) {
            state.lessons.stream().filter(lesson -> !lesson.end().isAfter(now))
                    .forEach(lesson -> lesson.windowClosed = true);
            refreshProjects(state, now);
        }
    }

    private void refreshAlgorithms(MentorState state, Instant now) {
        var upcoming = state.lessons.stream().filter(lesson -> !lesson.paused && lesson.end().isAfter(now))
                .sorted(Comparator.comparing(lesson -> lesson.start)).toList();
        Set<Integer> reserved = new HashSet<>();
        for (var lesson : upcoming) {
            Integer id = lesson.tasks.get(1).algorithmId();
            if (lesson.started && id != null) reserved.add(id);
        }
        var replace = new ArrayList<Lesson>();
        for (var lesson : upcoming) {
            if (lesson.started) continue;
            Integer id = lesson.tasks.get(1).algorithmId();
            if (id == null || state.solved.contains(id) || !reserved.add(id)) replace.add(lesson);
        }
        for (var lesson : replace) {
            lesson.tasks.set(1, LearningCatalog.nextAlgorithm(state, reserved));
        }
    }

    /**
     * In free mode a lesson left unfinished at 04:00 stays open one more day as an optional tail; today's lesson
     * still comes first (see {@link #todayFirst}).
     * Only once, so debt never piles up beyond yesterday.
     */
    private void carryDebt(MentorState state, Instant now) {
        if (!state.flexible) return;
        for (Lesson lesson : state.lessons) {
            if (lesson.flexibleEnd == null || lesson.paused || lesson.carried || lesson.currentTask() == null
                    || lesson.end().isAfter(now) || lesson.end().isBefore(now.minus(Duration.ofHours(6)))
                    || isPaused(state, date(state, lesson.end()))) {
                continue;
            }
            lesson.carried = true;
            lesson.flexibleEnd = lesson.flexibleEnd.plus(Duration.ofDays(1));
            lesson.windowClosed = false;
        }
    }

    /** Steps accepted today and the current streak of days with at least {@link #STREAK_STEPS} steps. */
    static int todaySteps(MentorState state, Instant now) {
        return state.stepsByDay.getOrDefault(date(state, now), 0);
    }

    static int streak(MentorState state, Instant now) {
        LocalDate day = date(state, now);
        if (state.stepsByDay.getOrDefault(day, 0) < STREAK_STEPS) day = day.minusDays(1);
        int streak = 0;
        for (int guard = 0; guard < 400; guard++, day = day.minusDays(1)) {
            if (state.stepsByDay.getOrDefault(day, 0) >= STREAK_STEPS) {
                streak++;
            } else if (!isPaused(state, day)) {
                break;
            }
        }
        return streak;
    }

    static Instant focusStart(MentorState state, Lesson lesson) {
        return lesson.flexibleEnd == null ? lesson.start : at(state, date(state, lesson.start), FOCUS_FROM);
    }

    static Instant focusEnd(MentorState state, Lesson lesson) {
        return lesson.flexibleEnd == null ? lesson.end() : at(state, date(state, lesson.start), FOCUS_TO);
    }

    private void updateCurriculum(MentorState state, Instant now) {
        if (state.curriculumVersion < 4) {
            // v3: exercises say what to send; v4: threads basics before volatile. Refresh cards not started yet.
            for (Lesson lesson : state.lessons) {
                if (lesson.started || lesson.paused || !lesson.end().isAfter(now)) continue;
                Task theory = lesson.tasks.getFirst();
                if (theory.type().equals("Теория") && !LearningCatalog.isPlaylist(theory)
                        && !StudyMaterials.readings(theory.topic()).isEmpty()) {
                    lesson.tasks.set(0, LearningCatalog.theoryForTopic(theory.topic()));
                }
            }
        }
        if (state.curriculumVersion >= 2) {
            state.curriculumVersion = Math.max(state.curriculumVersion, 4);
            return;
        }
        for (Lesson lesson : state.lessons) {
            if (lesson.started || lesson.paused || !lesson.end().isAfter(now)) {
                continue;
            }
            Task previous = lesson.tasks.getFirst();
            Task theory = StudyMaterials.readings(previous.topic()).isEmpty() || LearningCatalog.isPlaylist(previous)
                    ? LearningCatalog.theory(Math.toIntExact(lesson.id - 1))
                    : LearningCatalog.theoryForTopic(previous.topic());
            Task algorithm = lesson.tasks.get(1);
            lesson.tasks.set(0, theory);
            lesson.tasks.set(1, new Task(algorithm.type(), algorithm.topic(), LearningCatalog.ALGORITHM_MINUTES, algorithm.prompt(),
                    algorithm.criterion(), algorithm.resource(), algorithm.projectId(), algorithm.algorithmId()));
            lesson.tasks.set(2, LearningCatalog.interview(state, date(state, lesson.start), theory.topic()));
        }
        state.curriculumVersion = 4;
        refreshProjects(state, now);
    }

    void assignStrict(MentorState state, Instant now) {
        long inProgress = state.lessons.stream().filter(lesson -> lesson.strict && !lesson.paused
                && !focusStart(state, lesson).isAfter(now) && focusEnd(state, lesson).isAfter(now)).count();
        long available = Math.max(0, state.strictRemaining - inProgress);
        List<Lesson> upcoming = state.lessons.stream().filter(lesson -> !lesson.paused && !lesson.carried
                        && lesson.currentTask() != null && focusStart(state, lesson).isAfter(now))
                .sorted(Comparator.comparing(lesson -> lesson.start)).toList();
        for (int i = 0; i < upcoming.size(); i++) {
            upcoming.get(i).strict = i < available;
        }
    }

    Notice nextNotice(MentorState state, Instant now) {
        for (Lesson lesson : state.lessons.stream().filter(item -> !item.paused && item.end().isAfter(now))
                .sorted(Comparator.comparing(item -> item.start)).toList()) {
            if (lesson.flexibleEnd != null) {
                if (lesson.carried && date(state, lesson.start).isBefore(date(state, now))) continue;
                Notice notice = flexibleNotice(state, lesson, now);
                if (notice != null) return notice;
                continue;
            }
            if (lesson.start.isAfter(now)) {
                if (!lesson.preSent && !now.isBefore(lesson.start.minus(BEFORE))) {
                    return new Notice(lesson.id, lesson.start, "pre", MentorTone.before(lesson.id)
                            + "\nСкоро занятие в " + time(state, lesson.start) + ". Первый блок: " + lesson.tasks.getFirst().prompt()
                            + "\nМатериалы и границы чтения уже готовы: /lesson " + lesson.id + strictText(lesson));
                }
            } else if (!lesson.startSent) {
                return new Notice(lesson.id, lesson.start, "start", MentorTone.start(lesson.id)
                        + "\nЗанятие началось. Первый шаг: " + lesson.tasks.getFirst().prompt()
                        + "\nМатериалы: /lesson " + lesson.id + strictText(lesson));
            } else if (lesson.accepted == 0 && lesson.pending == null && !lesson.followupSent
                    && !now.isBefore(lesson.start.plus(FOLLOWUP))) {
                return new Notice(lesson.id, lesson.start, "followup", followupText(lesson));
            }
        }
        return missedNotice(state, now);
    }

    private Notice flexibleNotice(MentorState state, Lesson lesson, Instant now) {
        LocalDate day = date(state, now);
        // Reminders are about today's lesson only: an unfinished earlier lesson is an optional tail (2026-11-23).
        Lesson target = lesson;
        Task task = target.currentTask();
        if (lesson.start.isAfter(now) || task == null || target.pending != null || isPaused(state, day)) {
            return null;
        }
        int lastSent = Math.max(lesson.flexibleReminderSlot, lesson.startSent ? 1 : 0);
        List<LocalTime> slots = reminderTimes(state, day);
        for (int i = lastSent; i < slots.size(); i++) {
            Instant slot = reminderAt(state, day, slots.get(i));
            if (now.isBefore(slot)) return null;
            // Each reminder is valid for at most an hour (and not past the next one or the deadline), so an outage
            // does not produce a burst of stale ones.
            Instant until = slot.plus(Duration.ofHours(1));
            if (i + 1 < slots.size()) until = earlier(until, reminderAt(state, day, slots.get(i + 1)));
            until = earlier(until, lesson.end());
            if (!now.isBefore(until)) continue;
            int hoursLeft = (int) Math.max(0, Duration.between(now, lesson.end()).toHours());
            boolean ignored = state.lastReminderAt != null && Planner.date(state, state.lastReminderAt).equals(day)
                    && (state.lastUserMessageAt == null || state.lastUserMessageAt.isBefore(state.lastReminderAt));
            var progress = new MentorTone.Progress(target.accepted, target.tasks.size(), task.type(), false,
                    streak(state, now), todaySteps(state, now), STREAK_STEPS, hoursLeft, ignored);
            String title = LearningCatalog.exercise(task).lines().findFirst().orElse(task.prompt());
            String focus = lesson.strict && now.isBefore(focusEnd(state, lesson))
                    ? "\nСегодня строгие часы " + FOCUS_FROM + "–" + FOCUS_TO + ": Mentor Focus блокирует отвлекалки." : "";
            return new Notice(lesson.id, lesson.start, "daily",
                    MentorTone.dailyReminder(i == slots.size() - 1 ? MentorTone.LAST_REMINDER : Math.min(i, 3),
                            progress, lesson.id + i)
                            + "\n\nУрок #" + target.id + ". " + MentorContext.progress(target) + "."
                            + "\nШаг " + (target.position + 1) + " · " + task.type() + " — " + title
                            + "\n\n" + MentorTone.quickStart(task.type()) + focus, i + 1);
        }
        return null;
    }

    /**
     * The study day's reminder moments: each slot moves by a random but stable offset of up to ±30 minutes (the last
     * one only up to +15), so reminders do not become background noise. Order and the 20-minute gap are kept on the
     * study-day clock that starts at 04:00, so slots after midnight sort after the evening ones.
     */
    static List<LocalTime> reminderTimes(MentorState state, LocalDate day) {
        if (state.exactReminders) return DAILY_REMINDERS;
        var minutes = new ArrayList<Integer>();
        for (int i = 0; i < DAILY_REMINDERS.size(); i++) {
            long seed = day.toEpochDay() * 1_000_003L + i * 7_919L;
            seed = (seed ^ (seed >>> 13)) * 0x9E3779B97F4A7C15L;
            int max = i == DAILY_REMINDERS.size() - 1 ? 15 : 30;
            int offset = (int) Math.floorMod(seed >>> 17, (long) (30 + max + 1)) - 30;
            minutes.add(sinceDayStart(DAILY_REMINDERS.get(i)) + offset);
        }
        for (int i = 1; i < minutes.size(); i++) {
            minutes.set(i, Math.max(minutes.get(i), minutes.get(i - 1) + 20));
        }
        return minutes.stream().map(minute -> DAY_START.plusMinutes(Math.min(minute, 24 * 60 - 1))).toList();
    }

    /** A reminder slot as a moment: slots before 04:00 fall on the night after the study day. */
    static Instant reminderAt(MentorState state, LocalDate day, LocalTime slot) {
        return at(state, slot.isBefore(DAY_START) ? day.plusDays(1) : day, slot);
    }

    private static int sinceDayStart(LocalTime time) {
        return Math.floorMod(time.toSecondOfDay() / 60 - DAY_START.toSecondOfDay() / 60, 24 * 60);
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private Notice missedNotice(MentorState state, Instant now) {
        LocalDate today = date(state, now);
        LocalTime localTime = now.atZone(ZoneId.of(state.zone)).toLocalTime();
        // Evening only: the owner does not study in the morning or during the day (2026-11-23).
        if (isPaused(state, today) || localTime.isBefore(LocalTime.of(17, 0)) || !localTime.isBefore(LocalTime.of(23, 30))) {
            return null;
        }
        Lesson lesson = state.lessons.stream().filter(item -> !item.paused && !item.missedNoticeSent
                        && item.accepted == 0 && item.pending == null && !item.end().isAfter(now)
                        && !item.end().isBefore(now.minus(Duration.ofHours(20))))
                .max(Comparator.comparing(item -> item.start)).orElse(null);
        if (lesson == null) {
            return null;
        }
        String text = MentorTone.missed(lesson.id) + "\n" + (lesson.flexibleEnd == null
                ? "Занятие в " + time(state, lesson.start) + " закончилось без зачтённого ответа. "
                + "Ты готовишься к Java-собеседованию. В следующее окно начни с 10 минут и пришли один результат. "
                : "Урок за " + date(state, lesson.start) + " так и не начался. Стрик обнулён. ")
                + (state.flexible && state.strictRemaining > 0
                ? "\nШтраф: строгие часы " + FOCUS_FROM + "–" + FOCUS_TO + " на ближайшие дни (" + state.strictRemaining
                + "). Обнови окна в Mentor Focus кнопкой ниже. "
                : "")
                + "\nСледующий шаг — /today.";
        return new Notice(lesson.id, lesson.start, "missed", text);
    }

    static String followupText(Lesson lesson) {
        return MentorTone.followup(lesson.id) + "\n" + (lesson.started
                ? "Уже полчаса с начала занятия. Нужна первая попытка. Открой /today, поработай 10 минут и пришли ответ."
                : "С начала занятия прошло уже полчаса, а ты ещё не начал. Нажми «Начать» и удели первому шагу 10 минут.");
    }

    void markNotice(MentorState state, Notice notice) {
        Lesson lesson = byId(state, notice.lessonId());
        if (lesson == null || !lesson.start.equals(notice.start())) {
            return;
        }
        switch (notice.kind()) {
            case "pre" -> lesson.preSent = true;
            case "start" -> lesson.startSent = true;
            case "daily" -> lesson.flexibleReminderSlot = Math.max(lesson.flexibleReminderSlot, notice.flexibleSlot());
            case "followup" -> lesson.followupSent = true;
            case "missed" -> lesson.missedNoticeSent = true;
            default -> throw new IllegalArgumentException("Неизвестное напоминание");
        }
    }

    static boolean isPaused(MentorState state, LocalDate day) {
        return state.skippedDays.contains(day) || (state.resetFrom != null && day.isBefore(state.resetFrom))
                || (state.pausedThrough != null && !day.isAfter(state.pausedThrough));
    }

    static LocalDate date(MentorState state, Instant instant) {
        var local = instant.atZone(ZoneId.of(state.zone));
        return state.flexible && local.toLocalTime().isBefore(DAY_START)
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    /** A free-mode lesson shows its real deadline: «до 04:00 следующего дня» was wrong for a carried (debt) lesson. */
    static String label(MentorState state, Lesson lesson) {
        return lesson.flexibleEnd == null ? time(state, lesson.start)
                : date(state, lesson.start) + (lesson.carried ? " · хвост, необязательно" : "") + " · сдать до " + deadline(state, lesson);
    }

    /** «23.11 04:00»: when a free-mode lesson stops accepting answers. */
    static String deadline(MentorState state, Lesson lesson) {
        return DEADLINE.format(lesson.end().atZone(ZoneId.of(state.zone)));
    }

    static String time(MentorState state, Instant instant) {
        return TIME.format(instant.atZone(ZoneId.of(state.zone)));
    }

    static Instant at(MentorState state, LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ZoneId.of(state.zone)).toInstant();
    }

    static Lesson byId(MentorState state, long id) {
        return state.lessons.stream().filter(lesson -> lesson.id == id).findFirst().orElse(null);
    }

    static Lesson active(MentorState state, Instant now) {
        return state.lessons.stream().filter(lesson -> !lesson.paused && !lesson.start.isAfter(now)
                && lesson.end().isAfter(now)).min(todayFirst(state, now)).orElse(null);
    }

    /**
     * Today's lesson always comes first. An unfinished lesson of an earlier day stays open one more day as an optional
     * tail: it never blocks today's lesson, reminders ignore it, and it takes answers only once today's is done.
     * Until 2026-11-23 it was a mandatory «долг» that came first and held back the new lesson.
     */
    static java.util.Comparator<Lesson> todayFirst(MentorState state, Instant now) {
        LocalDate today = date(state, now);
        return java.util.Comparator.comparing((Lesson lesson) -> !date(state, lesson.start).equals(today));
    }

    /** The still open, unfinished lesson of an earlier day — optional, never required. */
    static Lesson tail(MentorState state, Instant now) {
        LocalDate today = date(state, now);
        return state.lessons.stream().filter(lesson -> lesson.carried && !lesson.paused && lesson.currentTask() != null
                && lesson.end().isAfter(now) && date(state, lesson.start).isBefore(today)).findFirst().orElse(null);
    }

    private static String strictText(Lesson lesson) {
        return lesson.strict ? "\nСтрогий режим. На телефоне должно действовать ограничение до конца окна." : "";
    }
}
