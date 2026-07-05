package com.openmason.main.systems.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily-cached loader for the {@code describe_api} guide topics — markdown
 * files at {@code /mcp/guide/<topic>.md} on the classpath, each budgeted to a
 * few hundred tokens so reading one is always cheap.
 */
public final class McpGuide {

    public static final List<String> TOPICS = List.of(
            "overview", "parts", "face_textures", "bones", "attachments",
            "animation", "scripting", "recipes");

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private McpGuide() {
    }

    /** Guide text for a topic (default "overview"). Unknown topics teach the list. */
    public static String topic(String name) {
        String key = name == null || name.isBlank() ? "overview" : name.trim().toLowerCase();
        if (!TOPICS.contains(key)) {
            throw McpErrors.invalidEnum("topic", key, TOPICS);
        }
        return CACHE.computeIfAbsent(key, McpGuide::load);
    }

    private static String load(String key) {
        String path = "/mcp/guide/" + key + ".md";
        try (InputStream in = McpGuide.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing guide resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read guide resource " + path, e);
        }
    }
}
