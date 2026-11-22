package mentor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import mentor.MentorState.Lesson;
import mentor.MentorState.Project;
import mentor.MentorState.Skill;
import mentor.MentorState.Task;
import mentor.TelegramApi.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class MentorBot {
    private static final Logger LOG = LoggerFactory.getLogger(MentorBot.class);
    private static final Pattern DIRECT_SUBMISSION = Pattern.compile(
            "(?isu)^(?:сдаю(?:\\s+(?:задачу|задание|решение|ответ))?|вот\\s+(?:мо[её]\\s+)?(?:решение|ответ))\\s*[:：]\\s*(.+)$");
    /** Status claims only the application may make; the chat model's sentences with them are dropped. */
    private static final Pattern CHAT_CREDIT = Pattern.compile(
            "(?iuU)(?:\\b(?:шаг|урок|блок|задач\\w*)\\b[^.!?\\n]{0,65}\\b(?:зачт\\w*|засчитан\\w*|закрыт\\w*|пройден\\w*|принят\\w*)\\b"
                    + "|\\bя\\s+(?:зач[её]л|засчитал)\\b|^\\s*(?:засчитываю|зачитываю|засчитано|принято|accepted)\\b"
                    + "|\\b(?:сдал|закрыл)\\s+шаг"
                    // a lesson card («Собеседование · 30 мин · шаг 3 из 4») is only ever produced by the application
                    + "|·\\s*\\d+\\s*мин\\s*·\\s*шаг\\s*\\d+\\s*из\\s*\\d+"
                    + "|\\b(?:переходим|перейд[её]м)\\s+к\\s+(?:следующ|шагу|алгоритм|собес)"
                    + "|^\\s*(?:верно[.!,]?\\s*)?следующий\\s+шаг\\s*[—:-]"
                    + "|\\bсч[её]тчик\\w*\\s+(?:обнов|измен|поправ)|\\bна\\s+следующем\\s+пуллинге\\b)");
    private static final Pattern CODE_LIKE = Pattern.compile(
            "```|[{};]\\s*$|\\breturn\\b|\\bpublic\\b|\\bclass\\s+\\w|\\bSELECT\\b|=>|->", Pattern.MULTILINE);
    private static final Pattern CHAT_SENTENCES =Pattern.compile("(?<=[.!?])(?=\\h)|(?<=[.!?]\\*\\*)(?=\\h)|(?<=\\n)");
    private static final int MAX_SUBMISSION_LENGTH = 16_000;
    private static final String HELP = """
            Наставник для Java-стажировки
            Напиши обычным сообщением, чтобы поговорить с ментором. Например: «Проведи мини-собеседование по Java Core».
            /newchat — начать диалог с чистого листа
            /windows 1=19:00 2=19:00 3=19:00 4=19:00 5=19:00 6=12:00 7=12:00 — окна по дням недели (1 = Пн)
            /zone Europe/Moscow — часовой пояс до первого плана
            /week — предложить план на семь дней, /confirm — сохранить
            /today — ближайшее или текущее занятие
            /lesson [ID] — подготовленный урок: ссылки, что изучить, задания
            /materials — материалы ближайшего урока и дополнительные источники
            /progress текст — ответ на проверку хода Java-проекта (можно позже)
            Сдача — кнопка «Ответить», «Вот моё решение: …» или /submit текст
            /hint текст — одна подсказка с учётом попытки
            /selfcheck текст — самопроверка, если ИИ недоступен
            /move ID ГГГГ-ММ-ДД ЧЧ:ММ причина — один перенос
            /skip [ГГГГ-ММ-ДД] — один пропуск учебного дня за неделю без штрафа
            /mode — выбрать свободный режим или расписание
            /pause ГГГГ-ММ-ДД — пауза включительно
            /t04b ГГГГ-ММ-ДД [SSH-адрес] — добавить крестики-нолики
            /project ГГГГ-ММ-ДД | название | описание | SSH-адрес — добавить проект
            /projects — список, /close ID — закрыть проект
            /pdf project или /pdf spring — материалы
            /solved 1 20 121 — отметить решённые задачи LeetCode
            /career — шаг к стажировке, /career текст — сохранить результат
            /vacancy текст — разобрать требования вакансии
            /devops текст — ответ на вопрос о ходе D01
            /report — недельный отчёт и строгие окна
            """;

    record Reply(String text, Button... buttons) {}
    private record Pending(long lessonId, int position, List<Task> tasks, MentorState.Attempt attempt,
                           String context, List<String> earlierSubmissions, String mentorContext) {}

    private final java.util.concurrent.atomic.AtomicBoolean reviewing = new java.util.concurrent.atomic.AtomicBoolean();

    private final StateStore store;
    private final Planner planner;
    private final TelegramApi telegram;
    private final MentorModel model;
    private final Clock clock;
    private final String projectPdf;
    private final String springPdf;
    private final String t04bSsh;
    @Value("${mentor.t04b.local-repo}")
    private String localProjectRepo = "";
    @Value("${mentor.study.reset-from}")
    private String resetStudyFrom = "";

    @jakarta.annotation.PostConstruct
    void applyStudyReset() {
        if (!resetStudyFrom.isBlank()) {
            LocalDate date = LocalDate.parse(resetStudyFrom);
            store.write(state -> {
                planner.resetStudy(state, date, clock.instant());
                return null;
            });
        }
    }

    private Instant nextReview = Instant.EPOCH;

    MentorBot(StateStore store, Planner planner, TelegramApi telegram, MentorModel model, Clock clock,
              @Value("${mentor.material.project}") String projectPdf,
              @Value("${mentor.material.spring}") String springPdf,
              @Value("${mentor.t04b.clone-url}") String t04bSsh) {
        this.store = store;
        this.planner = planner;
        this.telegram = telegram;
        this.model = model;
        this.clock = clock;
        this.projectPdf = projectPdf;
        this.springPdf = springPdf;
        this.t04bSsh = t04bSsh;
        if (!telegram.enabled()) {
            LOG.info("Telegram не настроен. Добавь TELEGRAM_BOT_TOKEN и TELEGRAM_OWNER_ID.");
        }
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        if (!telegram.enabled()) {
            return;
        }
        try {
            for (JsonNode update : telegram.updates(store.read(state -> state.updateOffset))) {
                long updateId = update.path("update_id").asLong();
                // At most once: the update is marked as handled before any reply goes out. When Telegram
                // hangs, a reply may arrive late, but it is never re-sent and never multiplied.
                store.write(state -> {
                    state.updateOffset = Math.max(state.updateOffset, updateId + 1);
                    state.pendingCommandReply = null;
                    return null;
                });
                try {
                    handle(update);
                } catch (IOException | RuntimeException failure) {
                    LOG.warn("Telegram: ответ на сообщение не отправлен ({})", failure.getClass().getSimpleName());
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Telegram: не удалось обработать входящие данные ({})", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 8000)
    void reminders() {
        if (!telegram.enabled()) {
            return;
        }
        try {
            Instant now = clock.instant();
            long chat = store.read(state -> state.ownerChat);
            if (chat == 0) {
                return;
            }
            Planner.Notice notice = store.write(state -> {
                planner.evaluate(state, now);
                return planner.nextNotice(state, now);
            });
            if (notice != null) {
                // Marked first: a reminder is sent at most once even if Telegram does not confirm it.
                store.write(state -> {
                    planner.markNotice(state, notice);
                    if (notice.kind().equals("daily")) state.lastReminderAt = now;
                    return null;
                });
                if (notice.kind().equals("missed")) {
                    boolean strict = store.read(state -> state.flexible && state.strictRemaining > 0);
                    if (strict) telegram.send(chat, notice.text(), new Button("Окна для Android", "focus"));
                    else telegram.send(chat, notice.text());
                } else if (notice.kind().equals("daily")) {
                    telegram.send(chat, notice.text(), nudgeButtons());
                } else {
                    telegram.send(chat, notice.text(), lessonButtons(notice.lessonId(), notice.start()));
                }
            }
            flushFeedback(chat);
            maybeSendNudge(chat, now);
            maybeSendRecall(chat, now);
            maybeSendSchoolNudge(chat, now);
            maybeSendWeekly(chat, now);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Не удалось обработать напоминание ({})", failure.getClass().getSimpleName());
        }
    }

    private void flushFeedback(long chat) throws IOException, InterruptedException {
        for (int sent = 0; sent < 5; sent++) {
            // Taken off the queue before sending: the verdict is also stored in the lesson and the day's chat.
            String feedback = store.write(state -> state.feedbackQueue.isEmpty() ? null : state.feedbackQueue.removeFirst());
            if (feedback == null) return;
            telegram.sendFormatted(chat, feedback, new Button("Текущее задание", "today"));
        }
    }

    @Scheduled(fixedDelay = 5000)
    void review() {
        Instant now = clock.instant();
        if (!telegram.enabled() || !model.enabled() || now.isBefore(nextReview)) {
            return;
        }
        if (!reviewing.compareAndSet(false, true)) return;
        try {
            reviewPending();
        } finally {
            reviewing.set(false);
        }
    }

    /** Reviews a fresh answer right away, so the verdict follows the receipt within seconds. */
    private void reviewNow(long chat) throws IOException, InterruptedException {
        reviewNow(chat, false);
    }

    /**
     * Reviews the fresh attempt at once. With {@code quietMissing} (an answer guessed by the router, not sent
     * explicitly) a review that finds no answer to any step stays silent and returns true: the message was
     * conversation after all and gets a normal chat reply instead of «Пока нет ответа».
     */
    private boolean reviewNow(long chat, boolean quietMissing) throws IOException, InterruptedException {
        if (!model.enabled() || !reviewing.compareAndSet(false, true)) return false;
        boolean nothingFound;
        try {
            nothingFound = withTyping(chat, () -> reviewPending(quietMissing));
        } finally {
            reviewing.set(false);
        }
        flushFeedback(chat);
        return nothingFound;
    }

    private void reviewPending() {
        reviewPending(false);
    }

    /** Oleg's wording of a verdict's feedback; the reviewer's own text when the chat models are unavailable. */
    private String voiced(boolean accepted, String task, String answer, String feedback) {
        if (feedback == null || feedback.isBlank() || !model.enabled()) return feedback;
        try {
            String text = withoutChatCredit(model.voice(accepted, task, answer, feedback)).strip();
            return text.isBlank() ? feedback : text;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return feedback;
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Олег не пересказал вердикт ({}), остаётся текст проверки", failure.getClass().getSimpleName());
            return feedback;
        }
    }

    private boolean reviewPending(boolean quietMissing) {
        Pending pending = store.read(state -> state.lessons.stream().filter(lesson -> lesson.pending != null)
                .filter(lesson -> lesson.currentTask() != null)
                .min(Comparator.comparing(lesson -> lesson.pending.submittedAt())).map(lesson -> {
                    var tasks = List.copyOf(lesson.tasks.subList(lesson.position, lesson.tasks.size()));
                    return new Pending(lesson.id, lesson.position, tasks, lesson.pending,
                            tasks.stream().map(task -> projectContext(state, task)).filter(context -> !context.isBlank())
                                    .distinct().collect(Collectors.joining("\n")),
                            DayMemory.earlierSubmissions(state, lesson.id, lesson.pending),
                            DayMemory.lastMentorMessage(state, lesson.pending.submittedAt()));
                }).orElse(null));
        if (pending == null) {
            return false;
        }
        try {
            var results = model.reviewLesson(pending.position() + 1, pending.tasks(), pending.context(),
                    pending.earlierSubmissions(), pending.mentorContext(), pending.attempt().text());
            // Oleg retells what will be shown; the verdicts themselves stay the reviewer's.
            var said = new java.util.HashMap<Integer, String>();
            for (var result : results) {
                boolean accepted = result.status().equals("accepted");
                int index = result.step() - 1 - pending.position();
                if (!accepted && !result.status().equals("revise") || index < 0 || index >= pending.tasks().size()) break;
                said.put(result.step(), voiced(accepted, LearningCatalog.exercise(pending.tasks().get(index)),
                        pending.attempt().text(), result.feedback()));
                if (!accepted) break;
            }
            return store.write(state -> {
                Lesson lesson = Planner.byId(state, pending.lessonId());
                if (lesson == null || lesson.position != pending.position() || !pending.attempt().equals(lesson.pending)
                        || !lesson.tasks.subList(lesson.position, lesson.tasks.size()).equals(pending.tasks())) {
                    return false;
                }
                if (quietMissing && !results.isEmpty() && results.getFirst().status().equals("missing")) {
                    lesson.pending = null;
                    // Not an answer after all: the chat reply will record the message once, as conversation.
                    state.conversation.removeIf(item -> Long.valueOf(lesson.id).equals(item.submissionLesson())
                            && item.question().equals(pending.attempt().text()));
                    return true;
                }
                Instant reviewedAt = clock.instant();
                var feedback = new java.util.StringJoiner("\n\n");
                for (var result : results) {
                    Task reviewedTask = pending.tasks().get(result.step() - 1 - pending.position());
                    if (!reviewedTask.equals(lesson.currentTask())) break;
                    if (result.status().equals("accepted")) {
                        feedback.add("Засчитано. Шаг " + result.step() + " из " + lesson.tasks.size()
                                + " («" + reviewedTask.type() + "»): " + said.getOrDefault(result.step(), result.feedback()));
                        accept(state, lesson, reviewedAt, true);
                    } else {
                        if (result.status().equals("revise")) {
                            feedback.add("Нужна доработка. Шаг " + result.step() + ": " + said.getOrDefault(result.step(), result.feedback()));
                            recordSkill(state, lesson, false, reviewedAt);
                        } else if (feedback.length() == 0) {
                            feedback.add("Пока нет ответа на шаг " + result.step() + " («" + reviewedTask.type() + "»).");
                        }
                        if (lesson.position == pending.position()) lesson.lastAttempt = pending.attempt().text();
                        break;
                    }
                }
                lesson.pending = null;
                String status = "Урок #" + lesson.id + ": " + MentorContext.progress(lesson).toLowerCase(Locale.ROOT) + ".";
                String reply = feedback + "\n\n" + status + "\n\n" + (lesson.end().isAfter(reviewedAt) || lesson.currentTask() == null
                        ? taskText(lesson) : "Учебное окно завершилось. Следующее занятие — /today.");
                state.feedbackQueue.add(reply);
                DayMemory.remember(state, reviewedAt, "", reply, null);
                return false;
            });
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException failure) {
            nextReview = clock.instant().plusSeconds(60);
            LOG.warn("Проверка временно недоступна ({})", failure.getClass().getSimpleName());
            store.write(state -> {
                Lesson lesson = Planner.byId(state, pending.lessonId());
                if (lesson != null && pending.attempt().equals(lesson.pending) && !lesson.reviewDelayNotified) {
                    lesson.reviewDelayNotified = true;
                    state.feedbackQueue.add("Урок #" + lesson.id + ". Решение сохранено, проверка задерживается. "
                            + "Бот повторит проверку. Пересылать решение не нужно.");
                }
                return null;
            });
            return false;
        }
    }

    private void handle(JsonNode update) throws IOException, InterruptedException {
        JsonNode callback = update.path("callback_query");
        boolean clicked = !callback.isMissingNode();
        JsonNode message = clicked ? callback.path("message") : update.path("message");
        JsonNode from = clicked ? callback.path("from") : message.path("from");
        if (from.path("id").asLong() != telegram.ownerId || !message.path("chat").path("type").asText().equals("private")) {
            return;
        }
        long chat = message.path("chat").path("id").asLong();
        if (clicked) {
            try {
                telegram.answerCallback(callback.path("id").asText());
            } catch (IOException failure) {
                LOG.debug("Не удалось подтвердить кнопку Telegram");
            }
        }
        Instant now = clock.instant();
        long sentAt = message.path("date").asLong();
        Instant received = sentAt > 0 && sentAt < now.getEpochSecond() ? Instant.ofEpochSecond(sentAt) : now;
        String input = clicked ? "/" + callback.path("data").asText() : message.path("text").asText();
        if (!clicked && !message.path("voice").isMissingNode()) {
            input = transcribeVoice(chat, message.path("voice"));
            if (input.isEmpty()) return;
            telegram.send(chat, "Распознано: " + input);
        }
        if (!clicked) {
            input = switch (input.strip().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "")) {
                case "📚 урок", "покажи урок", "что сегодня" -> "/today";
                case "🗓 план", "покажи план" -> "/week";
                case "🎙 собеседование", "начать собеседование" -> "/interview";
                case "🧩 проект", "мой проект", "🏫 школа 21", "школа 21", "школа" -> "/school";
                case "📚 собес", "собес" -> "/daily";
                case "📊 прогресс", "мой прогресс" -> "/report";
                case "⚙️ настройки" -> "/settings";
                case "меню" -> "/menu";
                case "покажи материалы на завтра", "материалы на завтра" -> "/tomorrow";
                case "закончить собеседование" -> "/endinterview";
                default -> input;
            };
            String lower = input.toLowerCase(Locale.ROOT);
            if (lower.startsWith("я закончил раздел про ")) {
                input = "/known " + input.substring("я закончил раздел про ".length()).strip();
            } else if (lower.startsWith("запиши результат по проекту:")) {
                input = "/progress " + input.substring("запиши результат по проекту:".length()).strip();
            }
            var reply = message.path("reply_to_message");
            if (!input.startsWith("/") && reply.path("from").path("is_bot").asBoolean()) {
                String prompt = reply.path("text").asText();
                if (prompt.startsWith("Ответ на задание #")) {
                    String expected = store.read(state -> {
                        Lesson active = submittingLesson(state, received);
                        return active == null ? "" : answerPrompt(active);
                    });
                    if (!prompt.equals(expected)) {
                        telegram.send(chat, "Это задание уже сменилось. Открой «📚 Урок».");
                        return;
                    }
                    input = "/submit " + input;
                } else if (prompt.startsWith("Свободные окна на неделю")) {
                    input = "/windows " + input;
                } else if (prompt.startsWith("Отчёт по Java-проекту")) {
                    input = "/progress " + input;
                }
            }
            if (!input.startsWith("/")) {
                var direct = DIRECT_SUBMISSION.matcher(input.strip());
                if (direct.matches() && store.read(state -> submittingLesson(state, received) != null
                        && state.interviewTopic.isBlank())) {
                    input = "/submit " + direct.group(1);
                }
            }
        }
        if (input.isBlank()) {
            return;
        }
        if (input.length() > 3900) {
            telegram.send(chat, "Пришли фрагмент до 3900 символов.");
            return;
        }
        String[] parts = input.strip().split("(?U)\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String args = parts.length > 1 ? parts[1].strip() : "";
        store.write(state -> {
            state.ownerChat = chat;
            state.lastUserMessageAt = now;
            return null;
        });
        try {
            switch (command) {
                case "/menu", "/start" -> telegram.menu(chat,
                        "Выбери урок, план или собеседование в меню. Для разговора просто напиши сообщение.");
                case "/answer" -> {
                    String prompt = store.write(state -> {
                        state.schoolMode = false;
                        Lesson active = submittingLesson(state, now);
                        return active == null ? "" : answerPrompt(active);
                    });
                    if (prompt.isEmpty()) telegram.send(chat, "Открой «📚 Урок» и начни текущее занятие.");
                    else telegram.ask(chat, prompt, "Решение и объяснение");
                }
                case "/setupwindows" -> telegram.ask(chat, "Свободные окна на неделю\nПришли, например, 1=19:00 3=19:00 6=12:00. 1 — понедельник, 7 — воскресенье.", "1=19:00 3=19:00 6=12:00");
                case "/askprogress" -> telegram.ask(chat, "Отчёт по Java-проекту\nЧто сделал, как проверил и что мешает двигаться дальше?", "Результат и проверка");
                case "/gitcheck" -> checkGit(chat);
                case "/focus" -> sendFocus(chat, now);
                case "/pdf" -> sendPdf(chat, args);
                case "/hint" -> hint(chat, args, now);
                case "/vacancy" -> vacancy(chat, args);
                case "/progress" -> talk(chat, args, now, received, true, 0, update.path("update_id").asLong(), false);
                default -> {
                    if (!command.startsWith("/")) {
                        boolean projectReply = message.path("reply_to_message").path("from").path("is_bot").asBoolean()
                                && message.path("reply_to_message").path("text").asText()
                                    .contains("Что с прошлого раза сделал, как проверил");
                        if (!projectReply && store.read(state -> !state.interviewTopic.isBlank())) {
                            interviewAnswer(chat, input.strip(), now);
                            return;
                        }
                        long repliedTo = message.path("reply_to_message").path("message_id").asLong();
                        boolean recallReply = repliedTo != 0 && store.read(state -> state.recallMessageId == repliedTo);
                        if (!projectReply && !recallReply && store.read(state -> schoolModeOn(state, now))) {
                            schoolTalk(chat, input.strip(), now);
                            return;
                        }
                        talk(chat, input.strip(), now, received, projectReply, projectReply ? repliedTo : 0,
                                update.path("update_id").asLong(), recallReply);
                        return;
                    }
                    Reply reply = store.write(state -> {
                        Reply result = respond(state, command, args, command.equals("/submit") ? received : now);
                        state.pendingCommandReply = new MentorState.CommandReply(update.path("update_id").asLong(),
                                chat, result.text(), List.of(result.buttons()));
                        return result;
                    });
                    telegram.send(chat, reply.text(), reply.buttons());
                    if (command.equals("/submit")) reviewNow(chat);
                }
            }
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            telegram.send(chat, failure.getMessage() == null ? "Проверь формат команды в /help." : failure.getMessage());
        }
    }

    Reply respond(MentorState state, String command, String args, Instant now) {
        // Anything about the daily lesson brings the daily mentor back, so lesson answers never reach the school one.
        if (List.of("/startnow", "/submit", "/lesson", "/interview", "/selfcheck", "/answer").contains(command)
                || command.startsWith("/start:") || command.startsWith("/lesson:")) {
            state.schoolMode = false;
        }
        Reply reply = respondCommand(state, command, args, now);
        if (command.equals("/today") || command.equals("/lesson") || command.startsWith("/lesson:")
                || command.startsWith("/start:") || command.equals("/selfcheck")
                || command.equals("/interview") || command.equals("/endinterview")) {
            DayMemory.remember(state, now, command + (args.isBlank() ? "" : " " + args), reply.text(), null);
        }
        return reply;
    }

    private Reply respondCommand(MentorState state, String command, String args, Instant now) {
        planner.evaluate(state, now);
        if (command.startsWith("/start:") || command.startsWith("/move:") || command.startsWith("/lesson:") || command.startsWith("/skip:")) {
            String[] callback = command.substring(1).split(":");
            Lesson lesson = callback.length == 3 ? Planner.byId(state, Long.parseLong(callback[1])) : null;
            if (lesson == null || lesson.paused || lesson.start.getEpochSecond() != Long.parseLong(callback[2])) {
                return new Reply("Это окно уже изменилось. Проверь /today.");
            }
            if (callback[0].equals("start")) {
                return begin(state, lesson, now);
            }
            if (callback[0].equals("lesson")) {
                return lesson(state, Long.toString(lesson.id), now);
            }
            if (callback[0].equals("skip")) {
                return new Reply(planner.skipDay(state, Planner.date(state, lesson.start), now));
            }
            return new Reply(movePrompt(lesson));
        }
        return switch (command) {
            case "/start", "/help" -> new Reply(HELP + "\n/menu — открыть кнопки Telegram");
            case "/settings" -> new Reply("Настройки", new Button("Свободные окна", "setupwindows"),
                    new Button("Учебные темы", "topics"), new Button("Режим занятий", "mode"),
                    new Button("Окна для Android", "focus"), new Button("Все команды", "help"));
            case "/projectmenu" -> new Reply(projects(state), new Button("Проверить Git", "gitcheck"),
                    new Button("Отчитаться о работе", "askprogress"));
            case "/topics" -> new Reply(LearningCatalog.topics()
                    + "\n\nОткрыть материал — /topic ID. Знаешь тему — /known ID."
                    + "\nПривязать ролик — /video ID ссылка | что посмотреть.");
            case "/topic" -> {
                String topic = LearningCatalog.topicId(args);
                yield topic.isEmpty() ? new Reply("Выбери тему.", new Button("Список тем", "topics"))
                        : new Reply(LearningCatalog.theoryForTopic(topic).prompt() + "\n\n" + topicMaterials(state, topic),
                                new Button("Эту тему уже знаю", "known " + topic));
            }
            case "/known" -> {
                String topic = LearningCatalog.topicId(args.replaceFirst("[.!?]+$", ""));
                if (topic.isEmpty()) yield new Reply("Укажи ID или полное название темы.", new Button("Список тем", "topics"));
                state.knownTopics.add(topic);
                yield new Reply("Сохранил твою отметку. Учту её при подборе новых уроков.");
            }
            case "/video" -> {
                String[] fields = args.split("\\s+", 2);
                String topic = LearningCatalog.topicId(fields[0]);
                String[] video = fields.length == 2 ? fields[1].split("\\s*\\|\\s*", 2) : new String[0];
                if (topic.isEmpty() || video.length != 2 || !video[0].matches(
                        "https://(?:www\\.)?(?:youtube\\.com/watch\\?v=|youtu\\.be/)[A-Za-z0-9_-]{11}(?:[?&]t=[0-9]+s?)?")
                        || video[1].isBlank() || video[1].length() > 300) {
                    yield new Reply("Формат: /video ID ссылка на ролик | что посмотреть.", new Button("Список тем", "topics"));
                }
                state.topicVideos.put(topic, "Дополнительное видео, добавленное тобой\n" + video[0] + "\nСмотреть: " + video[1]);
                yield new Reply("Видео добавлено к теме.", new Button("Открыть тему", "topic " + topic));
            }
            case "/tomorrow" -> {
                LocalDate day = Planner.date(state, now).plusDays(1);
                Lesson next = state.lessons.stream().filter(item -> !item.paused && Planner.date(state, item.start).equals(day))
                        .findFirst().orElse(null);
                yield next == null ? new Reply("На завтра нет подтверждённого занятия.", new Button("Открыть план", "week"))
                        : lesson(state, Long.toString(next.id), now);
            }
            case "/interview" -> {
                if (state.interviewTopic.isBlank()) {
                    state.interviewTopic = LearningCatalog.interview(state, Planner.date(state, now), "collections").topic();
                    state.interviewTurn = 0;
                }
                yield new Reply(interviewQuestion(state), new Button("Закончить", "endinterview"));
            }
            case "/endinterview" -> {
                int answered = state.interviewTurn;
                state.interviewTopic = "";
                state.interviewTurn = 0;
                yield new Reply("Собеседование завершено. Ответов разобрано: " + answered + ".");
            }
            case "/newchat" -> {
                state.conversation.clear();
                yield new Reply("Начали новый диалог. Зачтённые задания и учебный прогресс сохранились.");
            }
            case "/zone" -> new Reply(setZone(state, args));
            case "/windows" -> new Reply(setWindows(state, args));
            case "/mode" -> studyMode(state, args, now);
            case "/skip" -> new Reply(planner.skipDay(state,
                    args.isBlank() ? Planner.date(state, now) : LocalDate.parse(args), now));
            case "/week" -> {
                if (state.flexible) {
                    yield new Reply("Свободный режим. Занимайся в любое время, учебный день — до 04:00.\n" + upcoming(state, now),
                            new Button("Режим занятий", "mode"));
                }
                planner.draft(state, now);
                if (state.draft.isEmpty()) {
                    String confirmed = upcoming(state, now);
                    yield confirmed.isBlank() ? new Reply("Укажи свободные окна через /windows.")
                            : new Reply("Подтверждённый план подготовки к Java-собеседованию\n" + confirmed);
                }
                String confirmed = upcoming(state, now);
                yield new Reply((confirmed.isBlank() ? "" : "Подтверждённые уроки\n" + confirmed + "\n\n")
                        + "Черновик новых занятий. "
                        + LearningCatalog.INTERVIEW_TRACK_MINUTES + " мин на теорию, алгоритмы и интервью. "
                        + LearningCatalog.PRACTICE_MINUTES + " мин на дополнительную практику.\n"
                        + draftText(state, now) + "\nПодтверди, чтобы включить напоминания.", new Button("Подтвердить", "confirm"));
            }
            case "/confirm" -> {
                if (state.flexible) yield new Reply("В свободном режиме уроки появляются автоматически.");
                int created = planner.confirm(state, now);
                yield new Reply(created == 0 ? "Нет новых окон для подтверждения. Открой /week."
                        : "Сохранено уроков: " + created + ". Напоминания включены.\n" + upcoming(state, now));
            }
            case "/today" -> {
                state.schoolMode = false;
                yield today(state, now);
            }
            case "/school" -> {
                state.schoolMode = true;
                state.schoolModeDay = Planner.date(state, now);
                SchoolMentor.ensurePlan(state, now);
                yield new Reply(SchoolMentor.status(state, now), new Button("📚 Собес", "daily"));
            }
            case "/daily" -> {
                state.schoolMode = false;
                Reply lesson = today(state, now);
                yield new Reply("Вернулись к собесу.\n\n" + lesson.text(), lesson.buttons());
            }
            case "/startnow" -> {
                Lesson lesson = submittingLesson(state, now);
                if (lesson == null) yield new Reply("Сейчас открытого шага нет. Проверь /today.");
                lesson.started = true;
                state.commitAt = now.plus(java.time.Duration.ofMinutes(40));
                state.commitLessonId = lesson.id;
                state.commitAccepted = lesson.accepted;
                state.snoozeUntil = null;
                yield new Reply("Засёк 40 минут. Через 40 минут жду хоть что-то — кривое тоже принимается.\n\n"
                        + taskText(lesson), taskButtons(lesson));
            }
            case "/snooze" -> {
                state.snoozeUntil = now.plus(java.time.Duration.ofHours(1));
                yield new Reply("Ок, через час напомню. Это перенос, а не отмена.");
            }
            case "/lesson" -> lesson(state, args, now);
            case "/materials" -> new Reply(lesson(state, args, now).text()
                    + "\n\nДополнительно — сохранённые источники:\nПлейлист 1: " + LearningCatalog.YOUTUBE_PLAYLIST_1
                    + "\nТочный ролик для текущей темы пока не выбран; обязательного просмотра нет."
                    + "\n\nПлейлист 2: " + LearningCatalog.YOUTUBE_PLAYLIST_2
                    + "\nРазбивки на темы и проверенных таймкодов пока нет; задания выше используют подготовленные статьи."
                    + "\nПособия: /pdf spring и /pdf project.");
            case "/move" -> new Reply(move(state, args, now));
            case "/pause" -> new Reply(pause(state, args, now));
            case "/t04b" -> new Reply(addT04b(state, args, now));
            case "/project" -> new Reply(addProject(state, args, now));
            case "/projects" -> new Reply(projects(state));
            case "/close" -> new Reply(closeProject(state, args, now));
            case "/submit" -> new Reply(submit(state, args, now));
            case "/selfcheck" -> new Reply(selfCheck(state, args, now));
            case "/solved" -> new Reply(solved(state, args));
            case "/career" -> new Reply(career(state, args, now));
            case "/devops" -> new Reply(devops(state, args, now));
            case "/report" -> new Reply(report(state, now));
            default -> new Reply("Команды доступны в /help.");
        };
    }

    private static String topicMaterials(MentorState state, String topic) {
        String video = state.topicVideos.get(topic);
        return StudyMaterials.forTopic(topic) + (video == null ? "" : "\n\n" + video);
    }

    private static Button[] taskButtons(Lesson lesson) {
        return lesson.currentTask() != null && lesson.pending == null
                ? new Button[]{new Button("Ответить на задание", "answer")} : new Button[0];
    }

    private static String answerPrompt(Lesson lesson) {
        return "Ответ на задание #" + lesson.id + ", шаг " + (lesson.position + 1)
                + ". Пришли решение и короткое объяснение.";
    }

    private static String interviewQuestion(MentorState state) {
        return "Вопрос " + (state.interviewTurn + 1) + " из 5\n"
                + LearningCatalog.interviewQuestion(state.interviewTopic).prompt()
                + "\n\nОтветь сообщением. Голосовые доступны при настроенном распознавании.";
    }

    private String transcribeVoice(long chat, JsonNode voice) throws IOException, InterruptedException {
        if (!model.voiceEnabled()) {
            telegram.send(chat, "Распознавание голосовых пока не подключено. Можешь прислать текст.");
            return "";
        }
        if (voice.path("duration").asInt() > 90 || voice.path("file_size").asLong() > TelegramApi.MAX_VOICE_BYTES
                || voice.path("file_id").asText().isBlank()) {
            telegram.send(chat, "Пришли голосовое до 90 секунд и до 1 МиБ.");
            return "";
        }
        try {
            return model.transcribe(telegram.voice(voice.path("file_id").asText()));
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Распознавание голосового недоступно: {}", failure.getClass().getSimpleName());
            telegram.send(chat, "Сервис распознавания не обработал голосовое. Пришли текст, чтобы продолжить.");
            return "";
        }
    }

    private void interviewAnswer(long chat, String answer, Instant now) throws IOException, InterruptedException {
        String topic = store.read(state -> state.interviewTopic);
        if (topic.isBlank()) return;
        MentorModel.Review review;
        try {
            review = model.review(LearningCatalog.interviewQuestion(topic), "", answer);
        } catch (IOException failure) {
            telegram.send(chat, "Проверка пока недоступна. Пришли ответ ещё раз чуть позже.");
            return;
        }
        String interviewSaid = voiced(review.accepted(), LearningCatalog.interviewQuestion(topic).prompt(), answer, review.feedback());
        Reply next = store.write(state -> {
            state.skills.put(topic, new Skill(review.accepted(), Planner.date(state, now).plusDays(review.accepted() ? 4 : 1)));
            state.interviewTurn++;
            String feedback = (review.accepted() ? "Засчитано. " : "Нужна доработка. ") + interviewSaid;
            Reply response;
            if (state.interviewTurn >= 5) {
                state.interviewTopic = "";
                state.interviewTurn = 0;
                response = new Reply(feedback + "\n\nСобеседование завершено. Результаты учтены в подборе тем.");
            } else {
                state.interviewTopic = LearningCatalog.nextInterviewTopic(topic);
                response = new Reply(feedback + "\n\n" + interviewQuestion(state), new Button("Закончить", "endinterview"));
            }
            DayMemory.remember(state, now, answer, response.text(), null);
            return response;
        });
        telegram.sendFormatted(chat, next.text(), next.buttons());
    }

    private static Button[] nudgeButtons() {
        return new Button[] {new Button("🔥 Начинаю", "startnow"), new Button("⏰ Через час", "snooze")};
    }

    /** Follow-ups the student asked for: «Начинаю» is checked after 40 minutes, «Через час» comes back in an hour. */
    private void maybeSendNudge(long chat, Instant now) throws IOException, InterruptedException {
        String text = store.write(state -> {
            if (state.commitAt != null && !now.isBefore(state.commitAt)) {
                state.commitAt = null;
                Lesson lesson = Planner.byId(state, state.commitLessonId);
                if (lesson == null || lesson.accepted != state.commitAccepted || lesson.pending != null
                        || lesson.currentTask() == null || !lesson.end().isAfter(now)) return null;
                return MentorTone.brokenPromise(now.getEpochSecond()) + "\n\nУрок #" + lesson.id + ". "
                        + MentorContext.progress(lesson) + ".\nШаг " + (lesson.position + 1) + " · "
                        + lesson.currentTask().type() + "\n" + MentorTone.quickStart(lesson.currentTask().type());
            }
            if (state.snoozeUntil != null && !now.isBefore(state.snoozeUntil)) {
                state.snoozeUntil = null;
                Lesson lesson = submittingLesson(state, now);
                if (lesson == null || lesson.pending != null) return null;
                return "Час прошёл. Ты просил напомнить — напоминаю.\n\nУрок #" + lesson.id + ". "
                        + MentorContext.progress(lesson) + ".\nШаг " + (lesson.position + 1) + " · "
                        + lesson.currentTask().type() + "\n" + MentorTone.quickStart(lesson.currentTask().type());
            }
            return null;
        });
        if (text != null) telegram.send(chat, text, nudgeButtons());
    }

    private static boolean schoolModeOn(MentorState state, Instant now) {
        return state.schoolMode && Planner.date(state, now).equals(state.schoolModeDay);
    }

    /** The evening deadline reminder of the School 21 mentor, at most once a day. */
    private void maybeSendSchoolNudge(long chat, Instant now) throws IOException, InterruptedException {
        String text = store.write(state -> {
            SchoolMentor.ensurePlan(state, now);
            String nudge = SchoolMentor.nudge(state, now);
            if (nudge != null) state.schoolNudgeDay = Planner.date(state, now);
            return nudge;
        });
        if (text != null) telegram.send(chat, text, new Button("🏫 Школа 21", "school"));
    }

    /**
     * Chat in School 21 mode. A report about the current T04B step goes to the step review; everything else
     * is a conversation with the School 21 mentor.
     */
    private void schoolTalk(long chat, String message, Instant now) throws IOException, InterruptedException {
        MentorState snapshot = store.read(state -> state);
        Project project = SchoolMentor.project(snapshot);
        Task step = project == null ? null : SchoolMentor.currentStep(project);
        MentorModel.Intent intent = null;
        if (step != null) {
            try {
                intent = model.route(List.of(step), "", DayMemory.lastMentorMessage(snapshot, now), message);
            } catch (IOException failure) {
                LOG.warn("Классификатор (Школа 21) недоступен: {}", failure.getClass().getSimpleName());
            }
        }
        String reply;
        if (intent == MentorModel.Intent.ANSWER) {
            int number = project.completedSteps() + 1;
            telegram.send(chat, "Принял отчёт по шагу " + number + " из " + LearningCatalog.T04B_STEPS + ", проверяю…");
            MentorModel.Review review;
            try {
                review = withTyping(chat, () -> model.reviewProjectStep(step, projectContext(snapshot, step), message));
            } catch (IOException failure) {
                telegram.send(chat, "Проверка сейчас не отвечает. Пришли отчёт ещё раз чуть позже — он не потерян, он у тебя в чате.");
                return;
            }
            String stepSaid = voiced(review.accepted(), step.prompt(), message, review.feedback());
            reply = store.write(state -> {
                Project current = SchoolMentor.project(state);
                if (current == null || current.completedSteps() + 1 != number) {
                    return "Пока я проверял, шаг уже сменился. Нажми «🏫 Школа 21», там актуальный статус.";
                }
                if (!review.accepted()) {
                    return "Шаг " + number + " пока не засчитан: " + stepSaid;
                }
                state.projects.set(state.projects.indexOf(current), current.finishStep(step));
                state.schoolStepDay = Planner.date(state, now);
                return "Засчитано. Шаг " + number + " из " + LearningCatalog.T04B_STEPS + ": " + stepSaid
                        + "\n\n" + SchoolMentor.status(state, now);
            });
        } else {
            MentorModel.Dialogue result;
            try {
                result = withTyping(chat, () -> model.talk(message, DayMemory.dialogue(snapshot, now),
                        SchoolMentor.context(snapshot, now), "", false, true));
            } catch (IOException failure) {
                telegram.send(chat, "Все мои бесплатные мозги сейчас в лимите, даже подождав не отлипли. Повтори через минуту — я не умер.");
                return;
            }
            reply = withoutChatCredit(result.answer());
            if (reply.isBlank()) reply = SchoolMentor.status(snapshot, now);
        }
        String answer = reply;
        store.write(state -> {
            DayMemory.remember(state, now, message, answer, null);
            return null;
        });
        telegram.sendFormatted(chat, answer, new Button("📚 Собес", "daily"));
    }

    private static boolean recallOpen(MentorState state, Instant now) {
        return !state.recallAnswered && !state.recallTopic.isBlank()
                && Planner.date(state, now).equals(state.recallDay);
    }

    /**
     * Once a day, at a random moment between 12:00 and 21:00, asks one question about a topic that was
     * already studied. Weak topics and the oldest review dates go first.
     */
    private void maybeSendRecall(long chat, Instant now) throws IOException, InterruptedException {
        String question = store.write(state -> {
            LocalDate today = Planner.date(state, now);
            if (today.equals(state.recallDay) || Planner.isPaused(state, today)) return null;
            int minute = Math.floorMod(today.hashCode() * 31 + 7, 9 * 60);
            LocalTime at = LocalTime.of(12, 0).plusMinutes(minute);
            LocalTime local = now.atZone(ZoneId.of(state.zone)).toLocalTime();
            if (local.isBefore(at) || !local.isBefore(LocalTime.of(22, 0))) return null;
            state.recallDay = today;
            state.recallAnswered = false;
            state.recallAttempt = "";
            state.recallMessageId = 0;
            var studied = new java.util.LinkedHashSet<String>(state.skills.keySet());
            state.lessons.stream().filter(lesson -> lesson.position > 0 && Planner.date(state, lesson.start).isBefore(today))
                    .map(lesson -> lesson.tasks.getFirst().topic()).forEach(studied::add);
            studied.removeIf(topic -> LearningCatalog.topicId(topic).isEmpty());
            String topic = studied.stream().min(Comparator
                    .comparing((String id) -> state.skills.containsKey(id) && state.skills.get(id).demonstrated())
                    .thenComparing(id -> state.skills.containsKey(id) ? state.skills.get(id).reviewOn() : LocalDate.MIN))
                    .orElse("");
            state.recallTopic = topic;
            return topic.isBlank() ? null : MentorTone.recall(today.toEpochDay()) + "\n\n"
                    + LearningCatalog.interviewQuestion(topic).prompt()
                    + "\n\nОтветь прямо сюда, можно голосовым.";
        });
        if (question == null) return;
        long messageId = telegram.send(chat, question);
        store.write(state -> {
            state.recallMessageId = messageId;
            DayMemory.remember(state, now, "", question, null);
            return null;
        });
    }

    private void recallAnswer(long chat, String answer, Instant now) throws IOException, InterruptedException {
        String topic = store.read(state -> state.recallTopic);
        String earlier = store.read(state -> state.recallAttempt);
        // An objection («я же написал…») is graded together with the first answer, so a misread can be fixed.
        String full = earlier.isBlank() ? answer : earlier + "\nУточнение ученика после вердикта: " + answer;
        MentorModel.Review review;
        try {
            review = withTyping(chat, () -> model.review(LearningCatalog.interviewQuestion(topic), "", full));
        } catch (IOException failure) {
            telegram.send(chat, "Проверка вопроса дня сейчас не отвечает. Пришли ответ ещё раз чуть позже.");
            return;
        }
        String recallSaid = voiced(review.accepted(), LearningCatalog.interviewQuestion(topic).prompt(), full, review.feedback());
        String reply = (review.accepted() ? "Вопрос дня — засчитано. " : "Вопрос дня — не дотянул. ") + recallSaid
                + (review.accepted() ? "" : "\nНе согласен — ответь на это сообщение и объясни, перепроверю.");
        store.write(state -> {
            state.skills.put(topic, new Skill(review.accepted(), Planner.date(state, now).plusDays(review.accepted() ? 7 : 2)));
            state.recallAnswered = review.accepted();
            state.recallAttempt = review.accepted() ? "" : full.length() > 4000 ? full.substring(full.length() - 4000) : full;
            DayMemory.remember(state, now, answer, reply, null);
            return null;
        });
        long messageId = telegram.send(chat, reply);
        if (!review.accepted()) store.write(state -> {
            state.recallMessageId = messageId; // replying to the verdict counts as an appeal
            return null;
        });
    }

    private void checkGit(long chat) throws IOException, InterruptedException {
        var snapshot = store.read(state -> state);
        Project project = findProject(snapshot, LearningCatalog.T04B);
        if (localProjectRepo.isBlank() || project == null || project.closed()) {
            telegram.send(chat, "Для проверки нужна активная карточка T04B и путь к локальной копии Git.");
            return;
        }
        String evidence;
        try {
            evidence = LocalProjectGit.snapshot(Path.of(localProjectRepo), snapshot.lastProjectGitHead);
        } catch (IOException failure) {
            telegram.send(chat, "Не удалось прочитать локальную копию Git. Проверь путь и доступ к репозиторию.");
            return;
        }
        String[] details = evidence.split("\n", 2);
        String result = "Git T04B · " + details[0].substring(0, 8) + "\n" + details[1];
        String feedback = "";
        if (model.enabled()) {
            try {
                feedback = model.reviewGit(project.description(), details[1]);
            } catch (IOException failure) {
                feedback = "Разбор ИИ пока недоступен.";
            }
        }
        telegram.send(chat, result);
        if (!feedback.isBlank()) telegram.sendFormatted(chat, feedback);
        store.write(state -> { state.lastProjectGitHead = details[0]; return null; });
    }

    private void sendFocus(long chat, Instant now) throws IOException, InterruptedException {
        String windows = store.read(state -> state.lessons.stream()
                .filter(lesson -> !lesson.paused && Planner.focusEnd(state, lesson).isAfter(now)
                        && (lesson.flexibleEnd == null || lesson.strict))
                .sorted(Comparator.comparing(lesson -> Planner.focusStart(state, lesson))).limit(100)
                .map(lesson -> Planner.focusStart(state, lesson).minus(Planner.BEFORE).toEpochMilli() + ","
                        + Planner.focusEnd(state, lesson).toEpochMilli() + "," + lesson.strict)
                .collect(Collectors.joining("\n")));
        int strict = store.read(state -> (int) state.lessons.stream().filter(lesson -> lesson.strict && !lesson.paused
                && Planner.focusEnd(state, lesson).isAfter(now)).count());
        telegram.send(chat, strict == 0
                ? "Строгих окон сейчас нет, блокировать нечего. Они появятся после пропущенного дня."
                : "Строгих окон впереди: " + strict + ". Как подключить:\n"
                + "1. Сохрани файл ниже на телефон.\n2. В Mentor Focus нажми импорт и выбери этот файл.\n"
                + "3. Там же выбери приложения для блокировки и включи ограничения.\n"
                + "4. В «Специальных возможностях» Android включи службу Mentor Focus.\n"
                + "После каждого нового штрафа импортируй файл заново.");
        if (strict == 0) return;
        Path directory = Files.createTempDirectory("mentor-focus-");
        Path file = directory.resolve("mentor-focus.txt");
        try {
            Files.writeString(file, "MENTOR_FOCUS_1\n" + windows);
            telegram.document(chat, file);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private Reply studyMode(MentorState state, String args, Instant now) {
        if (!args.equals("flexible") && !args.equals("timed")) {
            return new Reply(state.flexible ? "Текущий режим: в любое время, учебный день — до 04:00." : "Текущий режим: по расписанию.",
                    new Button("В любое время дня", "mode flexible"), new Button("По расписанию", "mode timed"));
        }
        boolean flexible = args.equals("flexible");
        if (state.flexible != flexible) {
            state.flexible = flexible;
            state.draft.clear();
            state.lessons.removeIf(lesson -> lesson.end().isAfter(now) && !lesson.started
                    && !state.skippedDays.contains(Planner.date(state, lesson.start)));
            planner.prepareFlexible(state, now);
            planner.refreshProjects(state, now);
        }
        return flexible ? new Reply("Включён свободный режим. Открой «Урок».")
                : new Reply("Включено расписание. Проверь свободные окна, затем подтверди план.",
                        new Button("Свободные окна", "setupwindows"), new Button("План", "week"));
    }

    private Reply today(MentorState state, Instant now) {
        if (state.skippedDays.contains(Planner.date(state, now))) {
            return new Reply("Сегодня учебный день пропущен без штрафа.", new Button("План", "week"));
        }
        Lesson lesson = Planner.active(state, now);
        if (lesson != null) {
            String heading = "Урок #" + lesson.id + " · " + Planner.label(state, lesson);
            if (lesson.started) return new Reply(heading + "\n\n" + taskText(lesson), taskButtons(lesson));
            return new Reply(heading + "\n\n" + MentorTone.start(lesson.id) + "\n\n" + taskText(lesson),
                    lessonButtons(lesson.id, lesson.start));
        }
        lesson = state.lessons.stream().filter(item -> !item.paused && item.start.isAfter(now))
                .min(Comparator.comparing(item -> item.start)).orElse(null);
        return lesson == null ? new Reply("Ближайших занятий нет. Открой /week.")
                : new Reply(MentorTone.prepared(lesson.id) + "\nСледующий урок: " + Planner.label(state, lesson)
                        + "\n" + agenda(lesson) + "\nСсылки и задания: /lesson " + lesson.id,
                        lessonButtons(lesson.id, lesson.start));
    }

    private Reply lesson(MentorState state, String args, Instant now) {
        Lesson selected;
        if (args.isBlank()) {
            selected = Planner.active(state, now);
            if (selected == null) {
                selected = state.lessons.stream().filter(item -> !item.paused && item.start.isAfter(now))
                        .min(Comparator.comparing(item -> item.start)).orElse(null);
            }
        } else {
            if (!args.matches("[0-9]{1,12}")) {
                return new Reply("Номер урока без ребуса: /lesson 1.");
            }
            selected = Planner.byId(state, Long.parseLong(args));
        }
        if (selected == null) {
            return new Reply("Такого урока пока нет. Подготовь расписание через /week и /confirm.");
        }
        StringBuilder text = new StringBuilder(MentorTone.prepared(selected.id))
                .append("\n\nУрок #").append(selected.id).append(" · ").append(Planner.label(state, selected))
                .append(selected.paused ? " · на паузе" : "")
                .append("\n").append(MentorContext.progress(selected)).append('.');
        for (Task task : selected.tasks) {
            if (task.projectId() != null && state.projects.stream()
                    .noneMatch(project -> project.id().equals(task.projectId()) && !project.closed())) {
                continue;
            }
            text.append("\n\n").append(task.type()).append(" · ").append(task.minutes()).append(" мин\n")
                    .append(LearningCatalog.card(task));
            String video = state.topicVideos.get(task.topic());
            if (task.type().equals("Теория") && video != null) text.append("\n\n").append(video);
        }
        if (selected.paused || selected.currentTask() == null) return new Reply(text.toString());
        if (!selected.end().isAfter(now)) return new Reply(text.append("\n\nУчебное окно закончилось.").toString());
        if (selected.started) {
            return new Reply(text.append("\n\nОтвет просто пиши сюда — я сам пойму, что это сдача.")
                    .toString(), taskButtons(selected));
        }
        return new Reply(text.append("\n\nНажми «Начать», когда будешь готов.").toString(),
                lessonButtons(selected.id, selected.start));
    }

    private Reply begin(MentorState state, Lesson lesson, Instant now) {
        if (lesson.currentTask() == null) {
            return new Reply("Урок #" + lesson.id + ". " + MentorContext.progress(lesson) + ".");
        }
        if (!lesson.end().isAfter(now)) {
            return new Reply("Учебное окно закончилось. Проверь /today.");
        }
        if (now.isBefore(lesson.flexibleEnd == null ? lesson.start.minus(Planner.BEFORE) : lesson.start)) {
            return new Reply("Занятие запланировано на " + Planner.label(state, lesson) + ".");
        }
        lesson.started = true;
        return new Reply(MentorTone.start(lesson.id) + "\n\n" + taskText(lesson), taskButtons(lesson));
    }

    private Reply activity(MentorState state, Instant now) {
        Lesson lesson = Planner.active(state, now);
        if (lesson != null && lesson.flexibleEnd == null && !lesson.started && !lesson.followupSent
                && !now.isBefore(lesson.start.plus(Planner.ACTIVITY_DELAY))) {
            lesson.followupSent = true;
            return new Reply(MentorTone.start(lesson.id) + "\nУчебное окно уже началось. Давай начнём с 10 минут.",
                    lessonButtons(lesson.id, lesson.start));
        }
        return null;
    }

    private void talk(long chat, String message, Instant now, Instant received, boolean progressReply,
                      long replyToMessage, long updateId, boolean replyToRecall)
            throws IOException, InterruptedException {
        MentorState snapshot = store.write(state -> {
            planner.evaluate(state, now);
            return state;
        });
        if (!progressReply) {
            String progress = MentorContext.progressReply(snapshot, message, now);
            if (progress != null) {
                telegram.send(chat, progress);
                store.write(state -> {
                    DayMemory.remember(state, now, message, progress, null);
                    return null;
                });
                return;
            }
        }
        var currentPing = ProjectCheckins.pending(snapshot, now);
        if (replyToMessage > 0) {
            currentPing = snapshot.projectPings.stream().filter(ping -> ping.telegramMessageId == replyToMessage
                            && ping.sentAt != null && ping.answeredAt == null && ProjectCheckins.project(snapshot, ping) != null)
                    .findFirst().orElse(null);
        } else if (progressReply && currentPing == null) {
            currentPing = snapshot.projectPings.stream().filter(ping -> ping.sentAt != null && ping.answeredAt == null
                            && ProjectCheckins.project(snapshot, ping) != null)
                    .max(Comparator.comparing(ping -> ping.sentAt)).orElse(null);
        }
        final var ping = currentPing;
        if (progressReply && ping == null && replyToMessage == 0 && !message.isBlank()
                && snapshot.projects.stream().anyMatch(project -> project.id().equals(LearningCatalog.T04B) && !project.closed())) {
            String reply = "Записал результат по T04B. Учту его при следующей проверке проекта.";
            store.write(state -> {
                state.latestProjectReport = message;
                state.latestProjectReportDate = Planner.date(state, now);
                DayMemory.remember(state, now, "/progress " + message, reply, null);
                return null;
            });
            telegram.send(chat, reply);
            return;
        }
        if (progressReply && (ping == null || message.isBlank())) {
            telegram.send(chat, ping == null ? "Открытой проверки проекта пока нет. Бот сам спросит, когда придёт время."
                    : "Отчёт из пустоты не соберу. Напиши /progress что сделал, как проверил и что мешает дальше.");
            return;
        }
        Lesson submissionLesson = !progressReply ? submittingLesson(snapshot, received) : null;
        String recallQuestion = !progressReply && recallOpen(snapshot, now)
                ? LearningCatalog.interviewQuestion(snapshot.recallTopic).prompt() : "";
        boolean routerFailed = false;
        // «Решил», «accepted» about a warm-up problem: the bot marks it solved itself, no /solved needed.
        Integer solvedNow = progressReply ? null : solvedWarmup(snapshot, message, submissionLesson);
        String solvedNote = "";
        if (solvedNow != null) {
            store.write(state -> {
                state.solved.add(solvedNow);
                if (solvedNow.equals(state.lastOfferedProblem)) state.lastOfferedProblem = null;
                return null;
            });
            solvedNote = "\nТолько что отмечено решённой (это сделал ты сам, автоматически): " + solvedNow + ". "
                    + LearningCatalog.problemTitle(solvedNow) + ". Он это не видит, про отметку не говори. "
                    + "Отреагируй как Олег на то, что он решил эту задачу: респект с подъёбом и один вопрос как на "
                    + "собесе именно по ней (сложность, крайний случай или другой подход). Следующую задачу — только "
                    + "если он прямо просит ещё.";
        }
        if (solvedNow == null && (submissionLesson != null || !recallQuestion.isBlank())) {
            MentorModel.Intent intent = null;
            try {
                intent = replyToRecall ? MentorModel.Intent.RECALL : model.route(
                        submissionLesson == null ? List.of()
                                : List.copyOf(submissionLesson.tasks.subList(submissionLesson.position, submissionLesson.tasks.size())),
                        recallQuestion, DayMemory.lastMentorMessage(snapshot, now), message);
            } catch (IOException failure) {
                routerFailed = true;
                LOG.warn("Классификатор сообщения недоступен: {}", failure.getClass().getSimpleName());
            }
            if (intent == MentorModel.Intent.ANSWER) {
                String changed = store.write(state -> {
                    Lesson current = submittingLesson(state, received);
                    if (current == null || current.id != submissionLesson.id || current.position != submissionLesson.position) {
                        return "Пока я читал сообщение, шаг сменился. Открой «Урок», там актуальный статус.";
                    }
                    current.started = true;
                    String receipt = queueSubmission(state, current, message, received);
                    return model.enabled() ? null : receipt;
                });
                if (changed != null) {
                    telegram.send(chat, changed);
                    return;
                }
                // The verdict itself says what was credited. If the review finds no answer at all, the router
                // guessed wrong and the message is answered as ordinary conversation below.
                // Only a short remark without code may silently turn out to be chat; code or a long answer always gets
                // the review's verdict (on 2026-11-19 a correct solution vanished into chat this way).
                boolean conversational = message.length() < 200 && !CODE_LIKE.matcher(message).find();
                if (!reviewNow(chat, conversational)) return;
            }
            if (intent == MentorModel.Intent.RECALL && !recallQuestion.isBlank()) {
                recallAnswer(chat, message, now);
                return;
            }
        }
        MentorModel.Dialogue result;
        try {
            MentorState current = solvedNow == null ? snapshot : store.read(state -> state);
            String notes = MentorContext.snapshot(current, message, now) + solvedNote;
            result = withTyping(chat, () -> model.talk(message, DayMemory.dialogue(current, now), notes,
                    ping == null ? "" : ProjectCheckins.context(current, ping, now), progressReply));
        } catch (IOException failure) {
            LOG.warn("Ответ ментора недоступен: {}", failure.getClass().getSimpleName());
            if (progressReply && ping != null) {
                store.write(state -> {
                    state.projectPings.stream().filter(item -> item.id == ping.id && item.answeredAt == null)
                            .findFirst().ifPresent(item -> appendProjectReport(item, message));
                    return null;
                });
                telegram.send(chat, "Отчёт сохранён. ИИ пока не смог дать разбор, проверка остаётся открытой.");
            } else {
                String unavailable = "Все мои бесплатные мозги сейчас в лимите, даже подождав не отлипли. Повтори через минуту или напиши «продолжи» — сообщение я помню."
                        + (ping == null ? "" : " Отчёт по проекту можно сохранить через /progress.");
                store.write(state -> {
                    DayMemory.remember(state, now, message, unavailable, null);
                    return null;
                });
                telegram.send(chat, unavailable);
            }
            return;
        }
        String safeAnswer = withoutChatCredit(result.answer());
        if (routerFailed && submissionLesson != null && !safeAnswer.isBlank()) {
            safeAnswer += "\n\n(Если это был ответ на задание — отправь его через «Ответить» или «Вот моё решение: …».)";
        }
        String answer = safeAnswer.isBlank()
                ? store.read(state -> {
                    Lesson lesson = submissionLesson == null
                            ? Planner.active(state, now) : Planner.byId(state, submissionLesson.id);
                    return (lesson == null ? "" : "Урок #" + lesson.id + ". " + MentorContext.progress(lesson) + ".\n")
                            + "Зачёт появляется после проверки решения. Чтобы отправить его на проверку, "
                            + "нажми «Ответить» в разделе «Урок» или напиши «Вот моё решение: …».";
                })
                : safeAnswer;
        telegram.sendFormatted(chat, answer);
        Integer offered = LearningCatalog.linkedProblem(answer);
        Reply reminder = store.write(state -> {
            if (offered != null && !state.solved.contains(offered)) state.lastOfferedProblem = offered;
            if (ping != null && result.projectReport()) {
                state.projectPings.stream().filter(item -> item.id == ping.id && item.answeredAt == null
                        && ProjectCheckins.project(state, item) != null).findFirst().ifPresent(item -> {
                    appendProjectReport(item, message);
                    item.assessment = result.assessment();
                    item.feedback = answer;
                    if (!result.assessment().equals("unclear")) {
                        item.answeredAt = now;
                    }
                });
            }
            DayMemory.remember(state, now, message, answer, null);
            return activity(state, now);
        });
        if (reminder != null) {
            telegram.send(chat, reminder.text(), reminder.buttons());
        }
    }

    @FunctionalInterface
    private interface ModelCall<T> {
        T call() throws IOException, InterruptedException;
    }

    /**
     * Runs a slow model call while Telegram shows «печатает…». If it takes longer than 25 seconds,
     * one short message says the bot is alive and still thinking.
     */
    private <T> T withTyping(long chat, ModelCall<T> call) throws IOException, InterruptedException {
        var future = new java.util.concurrent.CompletableFuture<T>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(call.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        long started = System.nanoTime();
        boolean told = false;
        while (true) {
            Thread.ofVirtual().start(() -> {
                try {
                    telegram.typing(chat);
                } catch (Exception ignored) {
                    // The indicator is cosmetic; a failure must not affect the answer.
                }
            });
            try {
                return future.get(4500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException waiting) {
                if (!told && System.nanoTime() - started > 25_000_000_000L) {
                    told = true;
                    try {
                        telegram.send(chat, MentorTone.stillThinking(started));
                    } catch (IOException ignored) {
                        LOG.debug("Не удалось отправить сообщение о долгом ответе");
                    }
                }
            } catch (java.util.concurrent.ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof InterruptedException interrupted) throw interrupted;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IOException(cause);
            }
        }
    }

    private static final Pattern SOLVED_CLAIM = Pattern.compile(
            "(?iuU)\\b(?:решил|решено|решена|сдал|accepted|solved|зашл[аo]|прошл[аo]|затащил|готово)\\b");
    private static final Pattern PROBLEM_NUMBER = Pattern.compile(
            "(?iuU)(?:задач\\w*|leetcode|литкод\\w*|#|№|решил|solved|сдал)\\s*(\\d{1,4})\\b");

    /**
     * The warm-up problem a «решил / accepted» message is about: named or numbered in the message, otherwise the
     * one Oleg offered last. A problem that is the open lesson step is left to the lesson review.
     */
    static Integer solvedWarmup(MentorState state, String message, Lesson lesson) {
        if (!SOLVED_CLAIM.matcher(message).find()) return null;
        Integer id = LearningCatalog.namedProblem(message);
        if (id == null) id = LearningCatalog.linkedProblem(message);
        if (id == null) {
            var number = PROBLEM_NUMBER.matcher(message);
            if (number.find()) id = Integer.valueOf(number.group(1));
        }
        Task step = lesson == null ? null : lesson.currentTask();
        boolean lessonCode = step != null && step.algorithmId() != null && CODE_LIKE.matcher(message).find();
        if (id == null && !lessonCode) id = state.lastOfferedProblem;
        if (id == null || id < 1 || state.solved.contains(id)) return null;
        if (step != null && id.equals(step.algorithmId())) return null;
        return id;
    }

    static String withoutChatCredit(String answer) {
        var kept = new StringBuilder();
        boolean creditRemoved = false;
        for (String part : CHAT_SENTENCES.split(answer)) {
            String plain = TelegramText.format(part).text().strip().replaceFirst("^[*_`]+", "");
            if (CHAT_CREDIT.matcher(plain).find()) {
                creditRemoved = true;
                continue;
            }
            if (creditRemoved && plain.toLowerCase(Locale.ROOT).startsWith("бэкенд обновит счётчик")) {
                continue;
            }
            kept.append(part);
        }
        return kept.toString().strip();
    }

    private static void appendProjectReport(MentorState.ProjectPing ping, String message) {
        if (ping.report.equals(message) || ping.report.endsWith("\nУточнение: " + message)) return;
        String report = ping.report.isBlank() ? message : ping.report + "\nУточнение: " + message;
        ping.report = report.length() <= 6000 ? report : report.substring(report.length() - 6000);
    }

    private void maybeSendProjectPing(long chat, Instant now) throws IOException, InterruptedException {
        var ping = store.write(state -> {
            ProjectCheckins.schedule(state, now, java.util.concurrent.ThreadLocalRandom.current());
            return ProjectCheckins.due(state, now);
        });
        if (ping == null) {
            return;
        }
        String question = store.read(state -> ProjectCheckins.project(state, ping) == null ? ""
                : ProjectCheckins.question(state, ping));
        if (!question.isBlank()) {
            store.write(state -> {
                state.projectPings.stream().filter(item -> item.id == ping.id).findFirst()
                        .ifPresent(item -> item.sentAt = now);
                return null;
            });
            long messageId = telegram.send(chat, question);
            store.write(state -> {
                state.projectPings.stream().filter(item -> item.id == ping.id).findFirst()
                        .ifPresent(item -> item.telegramMessageId = messageId);
                return null;
            });
        }
    }

    private String setZone(MentorState state, String args) {
        if (!state.lessons.isEmpty()) {
            return "Часовой пояс можно менять до подтверждения первого расписания.";
        }
        state.zone = ZoneId.of(args).getId();
        state.draft.clear();
        return "Часовой пояс: " + state.zone;
    }

    private String setWindows(MentorState state, String args) {
        if (args.isBlank()) {
            return "Пример: /windows 1=19:00 3=19:00 6=12:00. Каждый указанный день получает одно окно на 2 часа.";
        }
        var windows = new java.util.LinkedHashMap<Integer, LocalTime>();
        for (String part : args.split("\\s+")) {
            String[] entry = part.split("=", -1);
            if (entry.length != 2 || !entry[0].matches("[1-7]")) {
                throw new IllegalArgumentException("Формат окна: 1=19:00 (1 — Пн, 7 — Вс).");
            }
            windows.put(Integer.parseInt(entry[0]), timeInput(entry[1]));
        }
        state.windows = windows;
        state.draft.clear();
        return "Свободных дней в шаблоне: " + windows.size() + " из 7. Открой /week.";
    }

    private String move(MentorState state, String args, Instant now) {
        String[] parts = args.split("\\s+", 4);
        if (parts.length < 4 || !parts[0].matches("[0-9]{1,12}")) {
            return "Сначала попробуй 10 минут. Формат: /move ID ГГГГ-ММ-ДД ЧЧ:ММ причина.";
        }
        Lesson lesson = Planner.byId(state, Long.parseLong(parts[0]));
        if (lesson == null || lesson.paused || !lesson.end().isAfter(now) || lesson.started) {
            return "Перенос доступен для предстоящего или ещё не начатого урока.";
        }
        if (lesson.flexibleEnd != null) return "Этот урок доступен весь день. Если нужен выходной, используй /skip.";
        if (lesson.moves > 0) {
            return "Этот урок уже перенесён. Начни с 10 минут в назначенное время.";
        }
        LocalDate day = LocalDate.parse(parts[1]);
        LocalDate today = Planner.date(state, now);
        Instant newStart = Planner.at(state, day, timeInput(parts[2]));
        if (day.isBefore(today) || day.isAfter(today.plusDays(1)) || !newStart.isAfter(now)
                || newStart.equals(lesson.start) || Planner.isPaused(state, day) || !planner.isFree(state, newStart, lesson.id)) {
            return "Выбери другое свободное будущее окно на сегодня или завтра.";
        }
        Task practice = lesson.tasks.getLast();
        Project project = findProject(state, practice.projectId());
        if (project != null && project.deadline() != null && day.isAfter(project.deadline())) {
            return "Новое окно должно быть не позже даты дедлайна проекта.";
        }
        lesson.moves++;
        lesson.moveReason = parts[3].strip();
        lesson.start = newStart;
        lesson.preSent = false;
        lesson.startSent = false;
        lesson.followupSent = false;
        planner.refreshProjects(state, now);
        planner.assignStrict(state, now);
        return "Урок #" + lesson.id + " перенесён на " + Planner.time(state, newStart) + ".\n" + upcoming(state, now);
    }

    private String pause(MentorState state, String args, Instant now) {
        LocalDate through = LocalDate.parse(args);
        if (through.isBefore(Planner.date(state, now))) {
            return "Выбери сегодняшнюю или будущую дату.";
        }
        planner.pause(state, through, now);
        return "Расписание на паузе по " + through + " включительно. Уже начатое занятие можно завершить.";
    }

    private String addT04b(MentorState state, String args, Instant now) {
        String[] parts = args.split("\\s+", 2);
        LocalDate deadline = LocalDate.parse(parts[0]);
        validateDeadline(state, deadline, now);
        Project existing = findProject(state, LearningCatalog.T04B);
        if (existing != null && existing.closed()) {
            return "T04B уже закрыт. История остаётся в /projects.";
        }
        if (existing != null) {
            state.projects.set(state.projects.indexOf(existing), new Project(existing.id(), existing.name(),
                    existing.description(), existing.cloneUrl(), deadline, false, existing.completedSteps()));
            planner.refreshProjects(state, now);
            return "Дедлайн T04B обновлён: " + deadline + ". Прогресс сохранён.";
        }
        String cloneUrl = parts.length == 2 ? parts[1] : t04bSsh;
        if (!cloneUrl.isBlank() && !validCloneUrl(cloneUrl)) {
            return "Укажи SSH-адрес для git clone.";
        }
        state.projects.add(new Project(LearningCatalog.T04B, "Крестики-нолики T04B", LearningCatalog.T04B_DESCRIPTION,
                cloneUrl, deadline, false, 0));
        planner.refreshProjects(state, now);
        return "T04B добавлен. Дедлайн " + deadline + ". Уроки доступны через /week и /today.";
    }

    private String addProject(MentorState state, String args, Instant now) {
        String[] parts = args.split("\\s*\\|\\s*", 4);
        if (parts.length != 4 || parts[1].isBlank() || parts[1].length() > 100
                || parts[2].isBlank() || parts[2].length() > 2000
                || !validCloneUrl(parts[3])) {
            return "Формат: /project ГГГГ-ММ-ДД | название | описание | SSH-адрес для клонирования.";
        }
        LocalDate deadline = LocalDate.parse(parts[0]);
        validateDeadline(state, deadline, now);
        String id = UUID.randomUUID().toString().substring(0, 8);
        state.projects.add(new Project(id, parts[1], parts[2], parts[3], deadline, false, 0));
        planner.refreshProjects(state, now);
        return "Добавлен проект " + parts[1] + " (" + id + ").";
    }

    private String projects(MentorState state) {
        if (state.projects.isEmpty()) {
            return "Проектов пока нет. Добавь /t04b ГГГГ-ММ-ДД.";
        }
        return state.projects.stream().map(project -> project.id() + " · " + project.name() + " · "
                + (project.closed() ? "закрыт" : "до " + project.deadline() + ", шагов " + project.completedSteps())
                + (project.cloneUrl().isBlank() ? "" : "\n" + project.cloneUrl())).collect(Collectors.joining("\n\n"));
    }

    private String closeProject(MentorState state, String id, Instant now) {
        Project project = findProject(state, id);
        if (project == null || project.closed()) {
            return "Активный проект с таким ID не найден. Список в /projects.";
        }
        state.projects.set(state.projects.indexOf(project), project.close());
        for (Lesson lesson : state.lessons) {
            Task current = lesson.currentTask();
            if (current != null && id.equals(current.projectId()) && !lesson.end().isAfter(now)) {
                lesson.pending = null;
                lesson.lastAttempt = "";
            }
        }
        planner.refreshProjects(state, now);
        return "Проект «" + project.name() + "» закрыт. Будущие проектные шаги обновлены.";
    }

    private String submit(MentorState state, String args, Instant now) {
        Lesson lesson = submittingLesson(state, now);
        if (lesson == null) {
            return "Нажми «Начать» в активном учебном окне. Текущее занятие — /today.";
        }
        if (args.length() < 12) {
            return "Пришли результат и короткое объяснение: «Вот моё решение: …».";
        }
        return queueSubmission(state, lesson, args, now);
    }

    private String queueSubmission(MentorState state, Lesson lesson, String args, Instant now) {
        String text = args;
        if (lesson.pending != null) {
            if (lesson.pending.text().equals(args) || lesson.pending.text().endsWith("\n\n" + args)) {
                return "Этот ответ уже на проверке.";
            }
            text = lesson.pending.text() + "\n\n" + args;
            if (text.length() > MAX_SUBMISSION_LENGTH) {
                return "Ответ уже на проверке. Пришли оставшуюся часть после разбора.";
            }
        }
        Instant submittedAt = lesson.pending != null && lesson.pending.submittedAt().isAfter(now)
                ? lesson.pending.submittedAt() : now;
        lesson.lastAttempt = text;
        lesson.started = true;
        if (lesson.pending == null) lesson.reviewDelayNotified = false;
        lesson.pending = new MentorState.Attempt(text, submittedAt);
        DayMemory.remember(state, now, args, "", lesson.id);
        String step = "шаг " + (lesson.position + 1) + " из " + lesson.tasks.size() + " («" + lesson.currentTask().type() + "»)";
        return model.enabled() ? "Принял как ответ на " + step + ". Попытка сохранена, проверяю…"
                : "Попытка на " + step + " сохранена. ИИ пока не настроен. Доступна /selfcheck с объяснением проверки.";
    }

    private String selfCheck(MentorState state, String args, Instant now) {
        Lesson lesson = state.lessons.stream().filter(item -> item.pending != null)
                .min(Comparator.comparing(item -> item.pending.submittedAt())).orElse(null);
        if (lesson == null || args.length() < 25) {
            return "Сначала отправь /submit. Затем /selfcheck и пояснение, как проверил результат (от 25 символов).";
        }
        lesson.selfChecked++;
        accept(state, lesson, now, false);
        return "Отмечено как самопроверка, отдельно от проверки ИИ.\n\n" + taskText(lesson);
    }

    private void accept(MentorState state, Lesson lesson, Instant now, boolean modelChecked) {
        Task task = lesson.currentTask();
        if (modelChecked) {
            recordSkill(state, lesson, true, now);
        }
        if (task.algorithmId() != null) {
            state.solved.add(task.algorithmId());
        }
        Project project = findProject(state, task.projectId());
        if (project != null && !project.closed()) {
            state.projects.set(state.projects.indexOf(project), project.finishStep(task));
        }
        var day = Planner.date(state, lesson.start);
        if (Boolean.TRUE.equals(state.evaluatedDays.get(day))) state.evaluatedDays.put(day, false);
        lesson.pending = null;
        lesson.position++;
        lesson.accepted++;
        state.stepsByDay.merge(Planner.date(state, now), 1, Integer::sum);
        state.stepsByDay.keySet().removeIf(old -> old.isBefore(Planner.date(state, now).minusDays(400)));
        lesson.hints = 0;
        lesson.lastAttempt = "";
        planner.refreshProjects(state, now);
    }

    private void recordSkill(MentorState state, Lesson lesson, boolean accepted, Instant now) {
        Task task = lesson.currentTask();
        if (task.type().equals("Собеседование") || task.type().equals("Теория") && !LearningCatalog.isPlaylist(task)) {
            state.skills.put(task.topic(), new Skill(accepted, Planner.date(state, now).plusDays(accepted ? 4 : 1)));
            state.lessons.stream().filter(item -> !item.started && !item.paused && item.start.isAfter(now))
                    .forEach(item -> item.tasks.set(2,
                            LearningCatalog.interview(state, Planner.date(state, item.start), item.tasks.getFirst().topic())));
        }
    }

    private String solved(MentorState state, String args) {
        if (!args.matches("[0-9]{1,8}(?:\\s+[0-9]{1,8})*")) {
            return "Пришли номера через пробел. Пример: /solved 1 20 121.";
        }
        for (String part : args.split("\\s+")) {
            state.solved.add(Integer.parseInt(part));
        }
        return "В твоём списке решённых задач: " + state.solved.size() + ".";
    }

    private String career(MentorState state, String args, Instant now) {
        LocalDate week = weekStart(state, now);
        if (args.isBlank()) {
            String result = state.career.get(week);
            return result != null ? "Шаг этой недели: " + result
                    : "Выбери Java-стажировку. Выпиши 3 требования и отметь, какие можешь показать на проекте. "
                    + "Можно разобрать текст через /vacancy. Результат запиши через /career.";
        }
        state.career.put(week, args);
        return "Шаг к стажировке сохранён в отчёте недели.";
    }

    private String devops(MentorState state, String args, Instant now) {
        if (args.isBlank()) {
            return "Как идёт D01 Linux? Что готово и есть ли блокер? Ответь через /devops.";
        }
        state.devops.put(weekStart(state, now), args);
        return "Статус D01 сохранён.";
    }

    private String report(MentorState state, Instant now) {
        LocalDate first = Planner.date(state, now).minusDays(6);
        List<Lesson> recent = state.lessons.stream().filter(lesson -> !lesson.paused
                && !Planner.date(state, lesson.start).isBefore(first) && !lesson.start.isAfter(now)).toList();
        long completed = recent.stream().filter(lesson -> lesson.currentTask() == null).count();
        long submitted = recent.stream().mapToInt(lesson -> lesson.accepted).sum();
        long selfChecked = recent.stream().mapToInt(lesson -> lesson.selfChecked).sum();
        long missed = state.evaluatedDays.entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(first) && entry.getValue()).count();
        String skills = state.skills.entrySet().stream().filter(entry -> entry.getValue().demonstrated())
                .map(entry -> skillName(entry.getKey())).sorted().collect(Collectors.joining(", "));
        String projectReport = state.projectPings.stream().filter(ping -> ping.sentAt != null)
                .max(Comparator.comparing(ping -> ping.sentAt)).map(ping -> "Проверка Java-проекта: "
                        + Planner.time(state, ping.sentAt) + " · " + switch (ping.assessment) {
                            case "adequate" -> "прогресс достаточный по отчёту";
                            case "insufficient" -> "нужен следующий результат";
                            case "unclear" -> "жду уточнение";
                            default -> "жду ответ через /progress";
                        }).orElse("Проверки Java-проекта: до двух случайных сообщений в неделю для активного проекта.");
        return "Последние семь дней: закончено уроков " + completed + "/" + recent.size()
                + ", результатов " + submitted + " (самопроверка: " + selfChecked + ")"
                + ", пропущенных дней " + missed + ".\n"
                + "Навыки: " + (skills.isBlank() ? "пока нет проверенных ответов" : skills) + ".\n"
                + "Стажировка: " + state.career.getOrDefault(weekStart(state, now), "шаг ещё не отмечен") + "\n"
                + "D01: " + state.devops.getOrDefault(weekStart(state, now), "жду статус") + "\n"
                + projectReport + "\n"
                + (state.flexible ? "Режим: в любое время, учебный день — до 04:00.\n" : "Строгих окон осталось: " + state.strictRemaining + ".\n")
                + "Пропуск на эту неделю: " + (Planner.skipUsed(state, Planner.date(state, now)) ? "использован" : "доступен") + ".\n"
                + upcoming(state, now);
    }

    private void maybeSendWeekly(long chat, Instant now) throws IOException, InterruptedException {
        LocalDate week = store.read(state -> weekStart(state, now));
        String message = store.read(state -> {
            LocalTime local = now.atZone(ZoneId.of(state.zone)).toLocalTime();
            if (local.isBefore(LocalTime.of(10, 0)) || !local.isBefore(LocalTime.of(21, 0))
                    || Planner.isPaused(state, Planner.date(state, now))
                    || !state.flexible && state.windows.isEmpty() || week.equals(state.weeklySent)) {
                return "";
            }
            return MentorTone.weekly(week.toEpochDay() / 7) + "\n\n" + report(state, now)
                    + (state.flexible ? "\n\nНовый урок доступен каждый день через «Урок».\n"
                    : "\n\nПодтверди новые окна через /week.\n") + careerPrompt()
                    + "\nКак идёт D01 Linux? Есть ли блокер? Ответь через /devops.";
        });
        if (!message.isBlank()) {
            store.write(state -> {
                state.weeklySent = week;
                return null;
            });
            telegram.send(chat, message);
        }
    }

    private void sendPdf(long chat, String args) throws IOException, InterruptedException {
        String file = switch (args) {
            case "project" -> projectPdf;
            case "spring" -> springPdf;
            default -> "";
        };
        if (file.isBlank() || !Files.isRegularFile(Path.of(file))) {
            telegram.send(chat, "Файл не найден. Проверь /pdf project или /pdf spring и пути в настройках запуска.");
            return;
        }
        telegram.document(chat, Path.of(file));
    }

    private void hint(long chat, String args, Instant now) throws IOException, InterruptedException {
        String[] request = store.write(state -> {
            Lesson lesson = submittingLesson(state, now);
            if (lesson == null || lesson.currentTask().type().equals("Собеседование") && lesson.lastAttempt.isBlank()) {
                return null;
            }
            if (!args.isBlank()) {
                lesson.lastAttempt = args;
            }
            if (lesson.lastAttempt.isBlank()) {
                return null;
            }
            lesson.hints++;
            return new String[] {lesson.currentTask().prompt(), LearningCatalog.gradingCriterion(lesson.currentTask()),
                    projectContext(state, lesson.currentTask()), lesson.lastAttempt, Integer.toString(lesson.hints)};
        });
        if (request == null) {
            telegram.send(chat, "Сначала начни занятие и опиши попытку. На собеседовании сначала ответь через /submit.");
            return;
        }
        if (!model.enabled()) {
            telegram.send(chat, "Выбери один простой пример и сравни ожидаемый результат с фактическим. "
                    + "Покажи конкретное расхождение и что уже проверил.");
            return;
        }
        var task = new Task("Подсказка", "", 0, request[0], request[1], "", null, null);
        try {
            String answer = model.hint(task, request[2], request[3], Integer.parseInt(request[4]));
            telegram.sendFormatted(chat, answer);
            store.write(state -> {
                DayMemory.remember(state, now, "/hint " + args, answer, null);
                return null;
            });
        } catch (IOException failure) {
            telegram.send(chat, "Подсказка сейчас недоступна. Проверь один граничный случай и запиши, где расходится результат.");
        }
    }

    private void vacancy(long chat, String args) throws IOException, InterruptedException {
        if (args.length() < 30) {
            telegram.send(chat, "Пришли текст требований вакансии командой /vacancy. Можно взять одну Java-стажировку.");
            return;
        }
        try {
            telegram.sendFormatted(chat, model.career(args));
        } catch (IOException failure) {
            telegram.send(chat, careerPrompt());
        }
    }

    /**
     * The lesson an answer belongs to. In free mode the day's lesson accepts answers without pressing
     * «Начать», and yesterday's unfinished lesson (debt) comes first.
     */
    private static Lesson submittingLesson(MentorState state, Instant now) {
        return state.lessons.stream().filter(lesson -> !lesson.paused && lesson.currentTask() != null
                && lesson.end().isAfter(now) && (lesson.flexibleEnd != null ? !now.isBefore(lesson.start)
                        : lesson.started && !now.isBefore(lesson.start.minus(Planner.BEFORE))))
                .findFirst().orElse(null);
    }

    private static Project findProject(MentorState state, String id) {
        return state.projects.stream().filter(project -> project.id().equals(id)).findFirst().orElse(null);
    }

    private static String projectContext(MentorState state, Task task) {
        Project project = findProject(state, task.projectId());
        if (project == null || project.closed()) return "";
        return project.name() + ". " + project.description()
                + (LearningCatalog.T04B.equals(project.id()) ? "\n" + LearningCatalog.T04B_REQUIREMENTS
                        + "\n" + LearningCatalog.T03_TRACE : "");
    }

    private static boolean validCloneUrl(String value) {
        return value.length() <= 400 && (value.matches("ssh://[^\\s]+")
                || value.matches("[^\\s@]+@[^\\s:]+:[^\\s]+"));
    }

    private static void validateDeadline(MentorState state, LocalDate deadline, Instant now) {
        if (deadline.isBefore(Planner.date(state, now))) {
            throw new IllegalArgumentException("Укажи актуальную дату дедлайна.");
        }
    }

    private static LocalTime timeInput(String value) {
        LocalTime time = LocalTime.parse(value);
        if (!value.matches("[0-9]{2}:[0-9]{2}") || time.isAfter(LocalTime.of(22, 0))) {
            throw new IllegalArgumentException("Начало двухчасового окна — в формате ЧЧ:ММ, до 22:00 включительно.");
        }
        return time;
    }

    private static String movePrompt(Lesson lesson) {
        if (lesson.flexibleEnd != null) return "Этот урок доступен весь день. Если нужен выходной, используй /skip.";
        if (lesson.moves > 0 || lesson.started) {
            return "Перенос недоступен. Проверь текущий шаг через /today.";
        }
        return "Сначала попробуй начать с 10 минут.\n" + lesson.tasks.getFirst().prompt()
                + "\nДля переноса: /move " + lesson.id + " ГГГГ-ММ-ДД ЧЧ:ММ причина.";
    }

    private static String taskText(Lesson lesson) {
        Task task = lesson.currentTask();
        if (task == null) {
            return MentorTone.finished(lesson.id) + "\nИтог в /report.";
        }
        if (lesson.pending != null) {
            return "Текущая попытка ожидает проверки. Если проверка недоступна, можно пройти /selfcheck.";
        }
        return task.type() + " · " + task.minutes() + " мин · шаг " + (lesson.position + 1) + " из " + lesson.tasks.size()
                + "\n" + LearningCatalog.card(task)
                + "\n\nОтвет просто пиши сюда — я сам пойму, что это сдача.";
    }

    private static String draftText(MentorState state, Instant now) {
        var chosen = Planner.reservedAlgorithms(state, now);
        var topics = new java.util.HashSet<String>();
        int index = Math.toIntExact(state.lessons.stream().mapToLong(lesson -> lesson.id).max().orElse(0));
        var result = new java.util.StringJoiner("\n");
        for (var entry : state.draft.entrySet()) {
            List<Task> tasks = LearningCatalog.day(state, entry.getKey(), index++, chosen, topics);
            result.add(entry.getKey() + " " + entry.getValue() + " · "
                    + shortTitle(tasks.getFirst()) + " · " + shortTitle(tasks.get(1)));
        }
        return result.toString();
    }

    private static String upcoming(MentorState state, Instant now) {
        LocalDate today = Planner.date(state, now);
        LocalDate until = today.plusDays(7);
        return state.lessons.stream().filter(lesson -> !lesson.paused)
                .filter(lesson -> {
                    LocalDate day = Planner.date(state, lesson.start);
                    return !day.isBefore(today) && day.isBefore(until);
                })
                .sorted(Comparator.comparing(lesson -> lesson.start)).limit(7)
                .map(lesson -> "#" + lesson.id + " · " + Planner.label(state, lesson)
                        + (Planner.date(state, lesson.start).equals(today) ? " · сегодня" : "")
                        + (lesson.currentTask() == null ? " · зачтён" : !lesson.end().isAfter(now) ? " · окно закончилось" : "")
                        + (lesson.strict ? " · Фокус " + Planner.time(state, lesson.start.minus(Planner.BEFORE))
                        + "–" + Planner.time(state, lesson.end()) : "") + "\n" + agenda(lesson))
                .collect(Collectors.joining("\n\n"));
    }

    private static String agenda(Lesson lesson) {
        return shortTitle(lesson.tasks.getFirst()) + " · "
                + shortTitle(lesson.tasks.get(1)) + " · интервью"
                + "\nДоп. практика (" + lesson.tasks.getLast().minutes() + " мин). " + lesson.tasks.getLast().prompt();
    }

    private static String shortTitle(Task task) {
        return task.prompt().split("\\.(?=\\s)|\\R", 2)[0];
    }

    private static String skillName(String id) {
        return switch (id) {
            case "mvc" -> "Spring MVC";
            case "di" -> "DI";
            case "interfaces" -> "интерфейсы";
            case "jpa" -> "JPA";
            case "atomicity" -> "атомарность";
            case "sql" -> "SQL";
            case "transactions" -> "транзакции";
            case "collections" -> "HashMap и равенство";
            case "dto" -> "DTO и валидация";
            case "http-auth" -> "HTTP-аутентификация";
            case "tests" -> "тестирование";
            case "streams" -> "Stream API";
            case "exceptions" -> "исключения";
            default -> id;
        };
    }

    private static LocalDate weekStart(MentorState state, Instant now) {
        return Planner.date(state, now).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String careerPrompt() {
        return "Шаг к стажировке: выбери вакансию, выпиши три требования и выбери навык, который можешь показать на проекте. "
                + "Запиши результат через /career.";
    }

    private static Button[] lessonButtons(long id, Instant start) {
        String suffix = ":" + id + ":" + start.getEpochSecond();
        return new Button[] {new Button("Начать", "start" + suffix), new Button("Материалы", "lesson" + suffix),
                new Button("Перенести", "move" + suffix), new Button("Пропустить день", "skip" + suffix)};
    }
}
