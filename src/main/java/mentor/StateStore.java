package mentor;

import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class StateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    StateStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        jdbc.execute("CREATE TABLE IF NOT EXISTS mentor_state (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mentor_state WHERE id = 1", Integer.class);
        if (count == 0) {
            jdbc.update("INSERT INTO mentor_state (id, payload) VALUES (1, ?)", json.writeValueAsString(new MentorState()));
        }
    }

    synchronized <T> T read(Function<MentorState, T> operation) {
        return operation.apply(load());
    }

    synchronized <T> T write(Function<MentorState, T> operation) {
        String payload = payload();
        MentorState state = json.readValue(payload, MentorState.class);
        T result = operation.apply(state);
        String updated = json.writeValueAsString(state);
        if (!updated.equals(payload)) {
            jdbc.update("UPDATE mentor_state SET payload = ? WHERE id = 1", updated);
        }
        return result;
    }

    private MentorState load() {
        return json.readValue(payload(), MentorState.class);
    }

    private String payload() {
        return jdbc.queryForObject("SELECT payload FROM mentor_state WHERE id = 1", String.class);
    }
}
