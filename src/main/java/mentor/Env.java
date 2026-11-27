package mentor;

import java.util.Map;

/**
 * The bot's settings, read straight from environment variables (systemd's EnvironmentFile on the server).
 * Replaces Spring's application.properties since 2026-11-27; the variable names are unchanged.
 */
final class Env {
    static final String DEFAULT_DB_URL = "jdbc:h2:file:./data/mentor;CACHE_SIZE=2048";

    private final Map<String, String> values;

    Env(Map<String, String> values) {
        this.values = values;
    }

    static Env system() {
        return new Env(System.getenv());
    }

    String get(String name, String fallback) {
        String value = values.get(name);
        return value == null ? fallback : value;
    }

    String get(String name) {
        return get(name, "");
    }

    long number(String name, long fallback) {
        String value = get(name).strip();
        return value.isEmpty() ? fallback : Long.parseLong(value);
    }
}
