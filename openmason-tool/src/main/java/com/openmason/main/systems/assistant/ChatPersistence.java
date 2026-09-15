package com.openmason.main.systems.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Chat transcript autosave under {@code ~/.openmason/chats/}: one JSON file
 * per conversation, rewritten after each completed turn. Images are not
 * persisted (their placeholder text is).
 */
public final class ChatPersistence {

    private static final Logger logger = LoggerFactory.getLogger(ChatPersistence.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ObjectMapper mapper;
    private final Path dir;
    private String currentFile;

    public ChatPersistence(ObjectMapper mapper) {
        this(mapper, Path.of(System.getProperty("user.home"), ".openmason", "chats"));
    }

    public ChatPersistence(ObjectMapper mapper, Path dir) {
        this.mapper = mapper;
        this.dir = dir;
    }

    /** Start a new transcript file (next save creates it). */
    public synchronized void startNew() {
        currentFile = null;
    }

    /** Persist the session (best-effort; failures only log). */
    public synchronized void save(ChatSession session) {
        List<ChatMessage> messages = session.snapshot();
        if (messages.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            if (currentFile == null) {
                currentFile = STAMP.format(Instant.now()) + ".json";
            }
            ObjectNode root = mapper.createObjectNode();
            root.put("model", session.modelId());
            ArrayNode array = root.putArray("messages");
            for (ChatMessage m : messages) {
                ObjectNode node = array.addObject();
                node.put("role", m.role.name());
                node.put("at", m.timestampMillis);
                node.put("text", m.text.toString());
                if (m.reasoning.length() > 0) {
                    node.put("reasoning", m.reasoning.toString());
                }
                if (m.toolCallId != null) {
                    node.put("toolCallId", m.toolCallId);
                }
                if (m.notice != null) {
                    node.put("notice", m.notice);
                }
                if (!m.toolCalls.isEmpty()) {
                    ArrayNode calls = node.putArray("toolCalls");
                    for (ChatMessage.ToolCallRecord call : m.toolCalls) {
                        ObjectNode c = calls.addObject();
                        c.put("id", call.id);
                        c.put("name", call.name);
                        c.put("args", call.argumentsJson);
                        c.put("status", call.status.name());
                        if (call.resultText != null) {
                            c.put("result", call.resultText);
                        }
                    }
                }
            }
            Files.writeString(dir.resolve(currentFile),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            logger.warn("Chat autosave failed: {}", e.toString());
        }
    }

    /** Saved transcripts, newest first. */
    public synchronized List<String> listSaved() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .limit(30)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Load a transcript into a fresh session (adopts it as current file). */
    public synchronized ChatSession load(String fileName) throws IOException {
        Path file = dir.resolve(fileName);
        JsonNode root = mapper.readTree(Files.readString(file));
        ChatSession session = new ChatSession();
        session.setModelId(root.path("model").asText(null));
        for (JsonNode node : root.path("messages")) {
            ChatMessage.Role role = ChatMessage.Role.valueOf(node.path("role").asText("USER"));
            ChatMessage m = new ChatMessage(role, node.path("at").asLong(),
                    node.path("toolCallId").asText(null));
            m.text.append(node.path("text").asText(""));
            if (node.hasNonNull("reasoning")) {
                m.reasoning.append(node.path("reasoning").asText());
            }
            if (node.hasNonNull("notice")) {
                m.notice = node.path("notice").asText();
            }
            for (JsonNode c : node.path("toolCalls")) {
                ChatMessage.ToolCallRecord call = new ChatMessage.ToolCallRecord(
                        c.path("id").asText(), c.path("name").asText(), c.path("args").asText());
                call.status = ChatMessage.CallStatus.valueOf(c.path("status").asText("OK"));
                call.resultText = c.path("result").asText(null);
                m.toolCalls.add(call);
            }
            session.add(m);
        }
        currentFile = fileName;
        return session;
    }
}
