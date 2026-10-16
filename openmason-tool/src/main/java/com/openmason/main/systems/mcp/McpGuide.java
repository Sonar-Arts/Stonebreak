package com.openmason.main.systems.mcp;

import com.openmason.api.knowledge.GuideTopic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code describe_api} guide: lazily-cached markdown topics contributed by the plugins
 * (#379; the built-in ones are {@code /mcp/guide/<topic>.md} resources), each budgeted to a few
 * hundred tokens so reading one is always cheap.
 */
public final class McpGuide {

    /** The built-in topics, in the order the built-in plugins register them. */
    public static final List<String> BUILTIN_TOPICS = List.of(
            "overview", "parts", "face_textures", "bones", "attachments",
            "animation", "scripting", "texture_editor", "recipes", "assets", "saving", "ui_editor");

    private final Map<String, GuideTopic> topics = new LinkedHashMap<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public McpGuide(List<GuideTopic> topics) {
        for (GuideTopic t : topics) {
            this.topics.put(t.id(), t);
        }
    }

    /** The built-in topics only (headless tests). */
    public static McpGuide builtin() {
        return new McpGuide(BuiltinKnowledge.guideTopics());
    }

    /** Topic names, in contribution order. */
    public List<String> topics() {
        return List.copyOf(topics.keySet());
    }

    /** Guide text for a topic (default "overview"). Unknown topics teach the list. */
    public String topic(String name) {
        String key = name == null || name.isBlank() ? "overview" : name.trim().toLowerCase();
        GuideTopic topic = topics.get(key);
        if (topic == null) {
            throw McpErrors.invalidEnum("topic", key, topics());
        }
        return cache.computeIfAbsent(key, k -> topic.markdown().get());
    }
}
