package com.openmason.main.systems.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for OpenAI-compatible SSE chat streams: feed it raw lines, get
 * {@link StreamDelta}s. Stateless per line except the {@code [DONE]} sentinel.
 */
public final class SseParser {

    private final ObjectMapper mapper;

    public SseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** True when the line is the end-of-stream sentinel. */
    public static boolean isDone(String line) {
        return line != null && line.strip().equals("data: [DONE]");
    }

    /**
     * Parse one SSE line into deltas (empty for keep-alives/blank lines/
     * unparseable frames — a broken frame must not kill the stream).
     */
    public List<StreamDelta> parse(String line) {
        List<StreamDelta> out = new ArrayList<>(2);
        if (line == null || !line.startsWith("data:") || isDone(line)) {
            return out;
        }
        String payload = line.substring(5).strip();
        if (payload.isEmpty()) {
            return out;
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (Exception e) {
            return out;
        }
        JsonNode usage = root.path("usage");
        if (usage.isObject() && usage.has("prompt_tokens")) {
            out.add(new StreamDelta.Usage(usage.path("prompt_tokens").asLong(),
                    usage.path("completion_tokens").asLong()));
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            return out;
        }
        JsonNode delta = choice.path("delta");
        JsonNode content = delta.path("content");
        if (content.isTextual() && !content.asText().isEmpty()) {
            out.add(new StreamDelta.Content(content.asText()));
        }
        JsonNode reasoning = delta.path("reasoning_content");
        if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
            out.add(new StreamDelta.Reasoning(reasoning.asText()));
        }
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                out.add(new StreamDelta.ToolCallFragment(
                        call.path("index").asInt(0),
                        textOrNull(call.path("id")),
                        textOrNull(call.path("function").path("name")),
                        textOrNull(call.path("function").path("arguments"))));
            }
        }
        JsonNode finish = choice.path("finish_reason");
        if (finish.isTextual()) {
            out.add(new StreamDelta.Finish(finish.asText()));
        }
        return out;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    /** Accumulates {@link StreamDelta.ToolCallFragment}s into complete calls. */
    public static final class ToolCallAccumulator {

        /** One fully-accumulated call. */
        public record Call(String id, String name, String argumentsJson) {
        }

        private record Partial(StringBuilder id, StringBuilder name, StringBuilder args) {
        }

        private final List<Partial> partials = new ArrayList<>();

        public void accept(StreamDelta.ToolCallFragment fragment) {
            while (partials.size() <= fragment.index()) {
                partials.add(new Partial(new StringBuilder(), new StringBuilder(),
                        new StringBuilder()));
            }
            Partial p = partials.get(fragment.index());
            if (fragment.id() != null) {
                p.id().append(fragment.id());
            }
            if (fragment.name() != null) {
                p.name().append(fragment.name());
            }
            if (fragment.argsFragment() != null) {
                p.args().append(fragment.argsFragment());
            }
        }

        public boolean isEmpty() {
            return partials.isEmpty();
        }

        public List<Call> calls() {
            List<Call> out = new ArrayList<>(partials.size());
            for (int i = 0; i < partials.size(); i++) {
                Partial p = partials.get(i);
                String id = p.id().length() > 0 ? p.id().toString() : "call_" + i;
                out.add(new Call(id, p.name().toString(), p.args().toString()));
            }
            return out;
        }
    }
}
