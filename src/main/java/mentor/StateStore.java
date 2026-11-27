package mentor;

import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

final class StateStore {
    private final Database db;
    private final ObjectMapper json;

    StateStore(Database db, ObjectMapper json) {
        this.db = db;
        this.json = json;
        db.execute("CREATE TABLE IF NOT EXISTS mentor_state (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
        int count = db.queryInt("SELECT COUNT(*) FROM mentor_state WHERE id = 1");
        if (count == 0) {
            db.update("INSERT INTO mentor_state (id, payload) VALUES (1, ?)", json.writeValueAsString(new MentorState()));
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
            db.update("UPDATE mentor_state SET payload = ? WHERE id = 1", updated);
        }
        return result;
    }

    /** The stored JSON as is (also used by the --check self-test). */
    synchronized String payload() {
        return db.queryString("SELECT payload FROM mentor_state WHERE id = 1");
    }

    private MentorState load() {
        return json.readValue(payload(), MentorState.class);
    }
}
