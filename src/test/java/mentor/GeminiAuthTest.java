package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Gemini gets its key both as Bearer (the OpenAI-compatible endpoint requires it) and as x-goog-api-key. */
class GeminiAuthTest {
    @Test
    void geminiKeyGoesInBothHeaders() throws Exception {
        var json = JsonMapper.builder().build();
        var googleKey = new AtomicReference<String>();
        var bearer = new AtomicReference<String>("none");
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/gemini/chat/completions", exchange -> {
            googleKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            bearer.set(auth == null ? "none" : auth);
            exchange.getRequestBody().readAllBytes();
            byte[] body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                    Map.of("content", "Жрал с удовольствием."))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            var model = new MentorModel(json, base + "/openrouter", "gemini:gemini-flash-latest", "openrouter-key");
            TestFields.set(model, "geminiKey", "AQ.test-key");
            TestFields.set(model, "geminiBase", base + "/gemini");
            var reply = model.talk("ты все лимиты сожрал", List.of(), "заметки", "", false);
            assertThat(reply.answer()).contains("Жрал");
            assertThat(googleKey.get()).isEqualTo("AQ.test-key");
            assertThat(bearer.get()).isEqualTo("Bearer AQ.test-key");
        } finally {
            server.stop(0);
        }
    }
}
