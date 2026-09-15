package com.openmason.main.systems.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal OpenAI-compatible chat client over JDK {@link HttpClient}: model
 * discovery ({@code GET /v1/models}) and blocking SSE streaming
 * ({@code POST /v1/chat/completions}) with cooperative cancel.
 *
 * <p>All calls block — the assistant worker thread owns them; the UI thread
 * never touches this class.
 */
public final class LlmClient {

    /** Receives decoded deltas on the calling (worker) thread. */
    public interface StreamListener {
        void onDelta(StreamDelta delta);
    }

    /** Cancel handle: flips a flag the read loop polls, then closes the stream. */
    public static final class StreamHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile InputStream stream;

        public void cancel() {
            cancelled.set(true);
            InputStream s = stream;
            if (s != null) {
                try {
                    s.close(); // aborts the request; vLLM/SGLang stop generating on disconnect
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper;
    private final SseParser parser;

    public LlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.parser = new SseParser(mapper);
    }

    /** Ids served at the endpoint (vLLM/SGLang serve one). Throws on unreachable. */
    public List<String> probeModels(String endpoint, String apiKey) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripSlash(endpoint) + "/models"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET /models -> HTTP " + response.statusCode());
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode model : mapper.readTree(response.body()).path("data")) {
                String id = model.path("id").asText(null);
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /**
     * Stream a chat completion, delivering deltas to {@code listener} until
     * the stream ends, errors, or {@code handle.cancel()} is called.
     *
     * @param body complete request body except {@code stream} (set here)
     */
    public void streamChat(String endpoint, String apiKey, ObjectNode body,
                           StreamListener listener, StreamHandle handle) throws IOException {
        body.put("stream", true);
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripSlash(endpoint) + "/chat/completions"))
                .timeout(Duration.ofMinutes(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (response.statusCode() != 200) {
            String error;
            try (InputStream in = response.body()) {
                error = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            }
            throw new IOException("HTTP " + response.statusCode() + ": " + compactError(error));
        }
        try (InputStream in = response.body();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            handle.stream = in;
            String line;
            while ((line = reader.readLine()) != null) {
                if (handle.isCancelled()) {
                    return;
                }
                if (SseParser.isDone(line)) {
                    return;
                }
                for (StreamDelta delta : parser.parse(line)) {
                    listener.onDelta(delta);
                }
            }
        } catch (IOException e) {
            if (handle.isCancelled()) {
                return; // cancel closes the stream — not an error
            }
            throw e;
        } finally {
            handle.stream = null;
        }
    }

    /** Build the tools array node from prebuilt function specs. */
    public ArrayNode toolsArray(List<ObjectNode> specs) {
        ArrayNode array = mapper.createArrayNode();
        specs.forEach(array::add);
        return array;
    }

    private String compactError(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            String message = node.path("error").path("message").asText(null);
            if (message == null) {
                message = node.path("message").asText(null);
            }
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    private static String stripSlash(String endpoint) {
        String e = endpoint == null ? "" : endpoint.strip();
        return e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
    }
}
