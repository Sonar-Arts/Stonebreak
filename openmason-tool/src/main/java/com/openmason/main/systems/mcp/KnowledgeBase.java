package com.openmason.main.systems.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distilled domain knowledge for LLM assistants working in Open Mason —
 * modelling, texturing and animation craft plus tool best practices — served
 * from classpath markdown packs at {@code /mcp/kb/<pack>.md}.
 *
 * <p>Same lazy-cache pattern as {@link McpGuide}, plus a section search:
 * packs split on {@code ##} headings, sections scored by query-token hits.
 */
public final class KnowledgeBase {

    public static final List<String> PACKS = List.of(
            "modeling_fundamentals", "texturing_pixel_art", "animation_practices",
            "tool_workflows", "pitfalls", "formats_conventions");

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private KnowledgeBase() {
    }

    /** One-line summaries for the no-args listing. */
    public static String index() {
        StringBuilder sb = new StringBuilder(
                "Knowledge packs (call knowledge {topic:\"<name>\"} for one, "
                        + "{query:\"...\"} to search across all):\n");
        for (String pack : PACKS) {
            sb.append("- ").append(pack).append(": ").append(firstLine(pack)).append('\n');
        }
        return sb.toString();
    }

    /** Full pack text. */
    public static String pack(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!PACKS.contains(key)) {
            throw McpErrors.invalidEnum("topic", name, PACKS);
        }
        return CACHE.computeIfAbsent(key, KnowledgeBase::load);
    }

    /**
     * Search all packs: returns the best-matching {@code ##} sections
     * (max {@code limit}), each prefixed with its pack name.
     */
    public static String search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return index();
        }
        String[] tokens = query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        record Hit(int score, String pack, String section) {
        }
        List<Hit> hits = new ArrayList<>();
        for (String pack : PACKS) {
            for (String section : sections(pack(pack))) {
                String lower = section.toLowerCase(Locale.ROOT);
                int score = 0;
                for (String token : tokens) {
                    if (token.length() < 2) {
                        continue;
                    }
                    int idx = 0;
                    while ((idx = lower.indexOf(token, idx)) >= 0) {
                        score++;
                        idx += token.length();
                    }
                }
                if (score > 0) {
                    hits.add(new Hit(score, pack, section));
                }
            }
        }
        if (hits.isEmpty()) {
            return "No knowledge sections match '" + query + "'. Packs: " + PACKS
                    + " — try a topic directly.";
        }
        hits.sort((a, b) -> Integer.compare(b.score, a.score));
        StringBuilder sb = new StringBuilder();
        int max = Math.max(1, Math.min(limit, 8));
        for (int i = 0; i < Math.min(max, hits.size()); i++) {
            Hit hit = hits.get(i);
            sb.append("[").append(hit.pack).append("]\n").append(hit.section.strip())
                    .append("\n\n");
        }
        return sb.toString().strip();
    }

    private static List<String> sections(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("## ") && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static String firstLine(String pack) {
        String text = pack(pack);
        for (String line : text.split("\n")) {
            String stripped = line.replaceFirst("^#+\\s*", "").strip();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return "";
    }

    private static String load(String key) {
        String path = "/mcp/kb/" + key + ".md";
        try (InputStream in = KnowledgeBase.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing knowledge resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read knowledge resource " + path, e);
        }
    }
}
