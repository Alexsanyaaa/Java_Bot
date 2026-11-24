package mentor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MentorState {
    public String zone = "Europe/Moscow";
    public Map<Integer, LocalTime> windows = new LinkedHashMap<>();
    public Map<LocalDate, LocalTime> draft = new LinkedHashMap<>();
    public List<Lesson> lessons = new ArrayList<>();
    public List<Project> projects = new ArrayList<>();
    public List<String> feedbackQueue = new ArrayList<>();
    public List<Exchange> conversation = new ArrayList<>();
    public List<ProjectPing> projectPings = new ArrayList<>();
    public LocalDate projectPingWeek;
    public Map<String, Skill> skills = new LinkedHashMap<>();
    public Set<Integer> solved = new LinkedHashSet<>();
    public Map<LocalDate, Boolean> evaluatedDays = new LinkedHashMap<>();
    public Map<LocalDate, String> career = new LinkedHashMap<>();
    public Map<LocalDate, String> devops = new LinkedHashMap<>();
    public LocalDate pausedThrough;
    public LocalDate resetFrom;
    public boolean flexible;
    public Set<LocalDate> skippedDays = new LinkedHashSet<>();
    public LocalDate weeklySent;
    public int strictRemaining;
    public int curriculumVersion;
    public String latestProjectReport = "";
    public LocalDate latestProjectReportDate;
    public Set<String> knownTopics = new LinkedHashSet<>();
    public Map<String, String> topicVideos = new LinkedHashMap<>();
    public String lastProjectGitHead = "";
    public String interviewTopic = "";
    public int interviewTurn;
    public long ownerChat;
    public long updateOffset;
    /**
     * The update being answered right now (0 when idle). If the process dies mid-answer (a deploy, 2026-11-24: a photo
     * was lost), the next start asks Telegram for it again — once, so an update that kills the bot cannot loop.
     */
    public long inFlightUpdate;
    public long redeliveredUpdate;
    public CommandReply pendingCommandReply;
    /** Model-accepted steps per day; two or more keep the streak alive. */
    public Map<LocalDate, Integer> stepsByDay = new LinkedHashMap<>();
    /** One recall question per day about an already studied topic. */
    public LocalDate recallDay;
    public String recallTopic = "";
    public long recallMessageId;
    public boolean recallAnswered;
    /** Earlier answers to today's recall question; an objection to a verdict is reviewed together with them. */
    public String recallAttempt = "";
    /** Topic id → the last study day it was asked (question of the day or lesson interview); keeps questions from repeating. */
    public Map<String, LocalDate> askedOn = new LinkedHashMap<>();
    /** Reminders fire at random moments within ±30 minutes of their slot unless this is set (tests). */
    public boolean exactReminders;
    /** «Начинаю»: the student promised to send something; the bot checks back at this moment. */
    public Instant commitAt;
    public long commitLessonId;
    public int commitAccepted;
    /** «Через час»: a postponed reminder, not a cancellation. */
    public Instant snoozeUntil;
    public Instant lastUserMessageAt;
    /** «🏫 Школа 21» switches the chat to the School 21 mentor; «📚 Собес» switches back. */
    /** The warm-up LeetCode problem Oleg offered last; «решил» without a number refers to it. */
    public Integer lastOfferedProblem;
    public boolean schoolMode;
    /** School mode lasts until the end of the day it was switched on; mornings start with the daily mentor. */
    public LocalDate schoolModeDay;
    public LocalDate schoolPlanStart;
    public int schoolPlanStartSteps;
    public LocalDate schoolNudgeDay;
    public LocalDate schoolStepDay;
    public Instant lastReminderAt;
    /** Last successfully sent chat reaction; survives restarts so reactions stay sparse. */
    public Instant lastReactionAt;
    /** The bot's latest messages (Telegram id → text, newest last), so Oleg knows what a reaction was put on. */
    public Map<Long, String> sentMessages = new LinkedHashMap<>();
    /** When Oleg last answered one of the owner's reactions; keeps him from replying to every emoji. */
    public Instant lastReactionReplyAt;

    record CommandReply(long updateId, long chat, String text, List<TelegramApi.Button> buttons) {}

    record Project(String id, String name, String description, String cloneUrl, LocalDate deadline,
                   boolean closed, int completedSteps) {
        Project finishStep(Task task) {
            int next = completedSteps + 1;
            if (LearningCatalog.T04B.equals(id) && task.topic().matches("t04b-[0-9]+")) {
                int taskNumber = Integer.parseInt(task.topic().substring("t04b-".length()));
                if (taskNumber != completedSteps) return this;
            }
            return new Project(id, name, description, cloneUrl, deadline, closed, next);
        }

        Project close() {
            return new Project(id, name, description, cloneUrl, deadline, true, completedSteps);
        }
    }

    record Task(String type, String topic, int minutes, String prompt, String criterion, String resource,
                String projectId, Integer algorithmId) {}

    record Skill(boolean demonstrated, LocalDate reviewOn) {}

    record Attempt(String text, Instant submittedAt) {}

    record Exchange(String question, String answer, Instant at, Long submissionLesson) {}

    public static final class ProjectPing {
        public long id;
        public String projectId;
        public Instant scheduledAt;
        public Instant sentAt;
        public long telegramMessageId;
        public Instant answeredAt;
        public String report = "";
        public String assessment = "";
        public String feedback = "";
    }

    public static final class Lesson {
        public long id;
        public Instant start;
        public Instant flexibleEnd;
        public int moves;
        public String moveReason;
        public boolean started;
        public boolean paused;
        public boolean preSent;
        public boolean startSent;
        public int flexibleReminderSlot;
        public boolean followupSent;
        public boolean missedNoticeSent;
        public boolean strict;
        public boolean strictCounted;
        public boolean windowClosed;
        public int position;
        public int hints;
        public int accepted;
        public int selfChecked;
        public String lastAttempt = "";
        public boolean reviewDelayNotified;
        /** A free-mode lesson left unfinished at 04:00 stays open one more day as an optional tail. */
        public boolean carried;
        public Attempt pending;
        public List<Task> tasks = new ArrayList<>();

        Instant end() {
            return flexibleEnd == null ? start.plusSeconds(7_200) : flexibleEnd;
        }

        Task currentTask() {
            return position < tasks.size() ? tasks.get(position) : null;
        }
    }
}
