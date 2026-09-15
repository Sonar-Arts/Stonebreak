package com.openmason.main.systems.assistant.libalex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal stdio MCP client for libalex: spawns the configured command, speaks
 * newline-delimited JSON-RPC 2.0 (initialize 30s, calls 120s), keeps a small
 * stderr ring for diagnostics, idles out after 10 minutes, and dies with the
 * app (daemon reader + shutdown hook).
 *
 * <p>All calls are synchronized — the assistant/knowledge tools issue one
 * recall at a time; libalex is not a hot path.
 */
public final class LibalexClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LibalexClient.class);
    private static final long INIT_TIMEOUT_S = 30;
    private static final long CALL_TIMEOUT_S = 120;
    private static final long IDLE_SHUTDOWN_MS = 10 * 60_000;

    private final LibalexDetector.LaunchConfig config;
    private final ObjectMapper mapper;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> inFlight = new ConcurrentHashMap<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private Process process;
    private Writer stdin;
    private long lastUseMillis;

    public LibalexClient(LibalexDetector.LaunchConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "libalex-shutdown"));
    }

    /** Call libalex_recall and return its text content. */
    public synchronized String recall(String query, String collection, int limit) throws Exception {
        ensureStarted();
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("query", query);
        if (collection != null && !collection.isBlank()) {
            arguments.put("collection", collection);
        }
        arguments.put("k", Math.max(1, Math.min(limit, 10)));
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "libalex_recall");
        params.set("arguments", arguments);
        JsonNode result = call("tools/call", params, CALL_TIMEOUT_S);
        StringBuilder sb = new StringBuilder();
        for (JsonNode content : result.path("content")) {
            if ("text".equals(content.path("type").asText())) {
                sb.append(content.path("text").asText()).append('\n');
            }
        }
        String text = sb.toString().strip();
        return text.isEmpty() ? result.toString() : text;
    }

    // ------------------------------------------------------------- lifecycle

    private void ensureStarted() throws Exception {
        maybeIdleShutdown();
        lastUseMillis = System.currentTimeMillis();
        if (process != null && process.isAlive()) {
            return;
        }
        logger.info("Starting libalex: {}", config.commandLine());
        ProcessBuilder builder = new ProcessBuilder(config.commandLine());
        builder.redirectErrorStream(false);
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("libalex is configured but failed to start: "
                    + e.getMessage());
        }
        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = new Thread(this::readLoop, "libalex-stdout");
        reader.setDaemon(true);
        reader.start();
        Thread errReader = new Thread(this::errLoop, "libalex-stderr");
        errReader.setDaemon(true);
        errReader.start();

        try {
            ObjectNode initParams = mapper.createObjectNode();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.putObject("capabilities");
            ObjectNode clientInfo = initParams.putObject("clientInfo");
            clientInfo.put("name", "open-mason");
            clientInfo.put("version", "1.0");
            call("initialize", initParams, INIT_TIMEOUT_S);
            // Spec: notify initialized (no response expected).
            ObjectNode note = mapper.createObjectNode();
            note.put("jsonrpc", "2.0");
            note.put("method", "notifications/initialized");
            writeLine(note);
        } catch (Exception e) {
            close();
            throw new IllegalStateException("libalex is configured but failed its MCP handshake: "
                    + e.getMessage() + stderrSuffix());
        }
    }

    private void maybeIdleShutdown() {
        if (process != null && process.isAlive() && lastUseMillis > 0
                && System.currentTimeMillis() - lastUseMillis > IDLE_SHUTDOWN_MS) {
            logger.info("libalex idle — stopping child process");
            close();
        }
    }

    @Override
    public synchronized void close() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
            stdin = null;
        }
        inFlight.values().forEach(f ->
                f.completeExceptionally(new IOException("libalex closed")));
        inFlight.clear();
    }

    // -------------------------------------------------------------- rpc core

    private JsonNode call(String method, ObjectNode params, long timeoutSeconds) throws Exception {
        long id = nextId.getAndIncrement();
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        inFlight.put(id, future);
        writeLine(request);
        try {
            JsonNode response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            JsonNode error = response.path("error");
            if (error.isObject()) {
                throw new IllegalStateException("libalex error: "
                        + error.path("message").asText(error.toString()));
            }
            return response.path("result");
        } catch (TimeoutException e) {
            inFlight.remove(id);
            throw new IllegalStateException("libalex call timed out after " + timeoutSeconds
                    + "s" + stderrSuffix());
        }
    }

    private void writeLine(ObjectNode node) throws IOException {
        stdin.write(mapper.writeValueAsString(node));
        stdin.write('\n');
        stdin.flush();
    }

    private void readLoop() {
        Process p = process;
        if (p == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode node = mapper.readTree(line);
                    long id = node.path("id").asLong(-1);
                    CompletableFuture<JsonNode> future = inFlight.remove(id);
                    if (future != null) {
                        future.complete(node);
                    }
                } catch (Exception ignored) {
                    // non-JSON stdout noise — ignore
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private void errLoop() {
        Process p = process;
        if (p == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > 40) {
                        stderrTail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private String stderrSuffix() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            return " | stderr: " + String.join(" / ",
                    stderrTail.stream().skip(Math.max(0, stderrTail.size() - 5)).toList());
        }
    }
}
