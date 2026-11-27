package mentor;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain Java start-up (no Spring since 2026-11-27): builds the objects by hand and runs the four schedules on
 * three threads, as spring.task.scheduling.pool.size=3 did. Saves the ~30 MB of classes Spring loaded.
 *
 * <p>{@code --check} opens the database, reads the state and writes it to JSON again without saving: a self-test
 * for a new build (especially a native image) on a copy of the production database.
 */
public final class MentorApplication {
    private static final Logger LOG = LoggerFactory.getLogger(MentorApplication.class);

    private MentorApplication() {}

    public static void main(String[] args) throws Exception {
        Env env = Env.system();
        ObjectMapper json = JsonMapper.builder().build();
        Database db = database(env);
        if (args.length > 0 && args[0].equals("--check")) {
            int status = check(db, json, args.length > 1 && args[1].equals("--net")
                    ? env.get("TELEGRAM_API_BASE", "https://api.telegram.org") : "");
            db.close();
            System.exit(status);
        }
        StateStore store = new StateStore(db, json);
        Clock clock = Clock.systemUTC();
        TelegramApi telegram = new TelegramApi(json, env.get("TELEGRAM_API_BASE", "https://api.telegram.org"),
                env.get("TELEGRAM_BOT_TOKEN"), env.number("TELEGRAM_OWNER_ID", 0));
        MentorModel model = MentorModel.fromEnv(json, env);
        MentorBot bot = new MentorBot(store, new Planner(), telegram, model, clock, env.get("MENTOR_PROJECT_PDF"),
                env.get("MENTOR_SPRING_PDF"), env.get("MENTOR_T04B_SSH")).configure(env);
        bot.applyStudyReset();

        ScheduledExecutorService schedules = Executors.newScheduledThreadPool(3, named());
        every(schedules, "poll", bot::poll, 0, 1000);
        every(schedules, "reminders", bot::reminders, 0, 8000);
        every(schedules, "review", bot::review, 0, 5000);
        every(schedules, "plan", bot::refreshAiPlan, 5000, 60_000);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Interrupts a running answer, like Spring did: poll() then keeps the update for redelivery.
            schedules.shutdownNow();
            try {
                schedules.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            db.close();
        }, "shutdown"));
        LOG.info("Наставник запущен");
    }

    static Database database(Env env) {
        String url = env.get("MENTOR_DB_URL", Env.DEFAULT_DB_URL);
        // H2's own exit hook would close the file under a write still running; the shutdown hook closes it last.
        if (url.startsWith("jdbc:h2:file:") && !url.toUpperCase().contains("DB_CLOSE_ON_EXIT")) url += ";DB_CLOSE_ON_EXIT=FALSE";
        return new Database(url, env.get("MENTOR_DB_USER", "sa"), env.get("MENTOR_DB_PASSWORD"));
    }

    /**
     * 0 when the stored state survives a read and a write unchanged and the main code paths work on it: the
     * planner, the /today answer and the AI plan request (JSON of records). Changes nothing in the database except
     * creating an empty state in an empty one. With {@code net} also makes one HTTPS request (no token): TLS works.
     */
    static int check(Database db, ObjectMapper json, String net) {
        try {
            var store = new StateStore(db, json);
            String payload = store.payload();
            MentorState state = json.readValue(payload, MentorState.class);
            String again = json.writeValueAsString(state);
            if (!again.equals(payload)) {
                int at = 0;
                while (at < Math.min(again.length(), payload.length()) && again.charAt(at) == payload.charAt(at)) at++;
                System.out.println("ОШИБКА: состояние после чтения и записи отличается с символа " + at + ": …"
                        + payload.substring(Math.max(0, at - 60), Math.min(payload.length(), at + 60)) + "…");
                return 1;
            }
            System.out.println("OK: состояние читается без потерь (" + payload.length() + " символов, уроков: "
                    + state.lessons.size() + ", навыков: " + state.skills.size() + ")");
            var now = java.time.Instant.now();
            var planner = new Planner();
            var bot = new MentorBot(store, planner, new TelegramApi(json, "http://127.0.0.1:1", "", 0),
                    new MentorModel(json, "http://127.0.0.1:1", "", ""), Clock.systemUTC(), "", "", "");
            // store.read: whatever the planner and the answer change stays in memory and is never saved
            String today = store.read(copy -> {
                planner.evaluate(copy, now);
                return bot.respond(copy, "/today", "", now).text();
            });
            String plan = store.read(copy -> json.writeValueAsString(planner.planRequest(copy, now)));
            System.out.println("OK: /today — " + today.length() + " символов, запрос плана — " + plan.length() + " символов");
            if (!net.isBlank()) {
                var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
                var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(net)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
                System.out.println("OK: HTTPS " + net + " → " + response.statusCode());
            }
            return 0;
        } catch (Exception failure) {
            System.out.println("ОШИБКА: " + failure);
            return 1;
        }
    }

    private static void every(ScheduledExecutorService schedules, String name, Runnable task, long delay, long period) {
        schedules.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Throwable failure) { // a thrown task would otherwise never run again
                LOG.warn("[{}] сбой ({})", name, failure.getClass().getSimpleName());
            }
        }, delay, period, TimeUnit.MILLISECONDS);
    }

    private static java.util.concurrent.ThreadFactory named() {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "mentor-" + count.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
