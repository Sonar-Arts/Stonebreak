package com.openmason.main.systems.mcp;

import com.openmason.api.knowledge.KnowledgePack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distilled domain knowledge for LLM assistants working in Open Mason —
 * modelling, texturing and animation craft plus tool best practices — served
 * from markdown packs contributed by the plugins (#379; the built-in ones are
 * {@code /mcp/kb/<pack>.md} resources).
 *
 * <p>Same lazy-cache pattern as {@link McpGuide}, plus a section search:
 * packs split on {@code ##} headings, sections scored by query-token hits.
 */
public final class KnowledgeBase {

    /** The built-in packs, in the order the built-in plugins register them. */
    public static final List<String> BUILTIN_PACKS = List.of(
            "modeling_fundamentals", "texturing_pixel_art", "animation_practices",
            "tool_workflows", "pitfalls", "formats_conventions");

    private final Map<String, KnowledgePack> packs = new LinkedHashMap<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public KnowledgeBase(List<KnowledgePack> packs) {
        for (KnowledgePack p : packs) {
            this.packs.put(p.id(), p);
        }
    }

    /** The built-in packs only (headless tests). */
    public static KnowledgeBase builtin() {
        return new KnowledgeBase(BuiltinKnowledge.knowledgePacks());
    }

    /** Pack names, in contribution order. */
    public List<String> packs() {
        return List.copyOf(packs.keySet());
    }

    /** One-line summaries for the no-args listing. */
    public String index() {
        StringBuilder sb = new StringBuilder(
                "Knowledge packs (call knowledge {topic:\"<name>\"} for one, "
                        + "{query:\"...\"} to search across all):\n");
        for (String pack : packs.keySet()) {
            sb.append("- ").append(pack).append(": ").append(firstLine(pack)).append('\n');
        }
        return sb.toString();
    }

    /** Full pack text. */
    public String pack(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        KnowledgePack pack = packs.get(key);
        if (pack == null) {
            throw McpErrors.invalidEnum("topic", name, packs());
        }
        return cache.computeIfAbsent(key, k -> pack.markdown().get());
    }

    /**
     * Search all packs: returns the best-matching {@code ##} sections
     * (max {@code limit}), each prefixed with its pack name.
     */
    public String search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return index();
        }
        String[] tokens = query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        record Hit(int score, String pack, String section) {
        }
        List<Hit> hits = new ArrayList<>();
        for (String pack : packs.keySet()) {
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
            return "No knowledge sections match '" + query + "'. Packs: " + packs()
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

    private String firstLine(String pack) {
        String text = pack(pack);
        for (String line : text.split("\n")) {
            String stripped = line.replaceFirst("^#+\\s*", "").strip();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return "";
    }
}
