package com.stonebreak.openmason.assets;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.main.systems.menus.dialogs.SBOObjectIndex;
import com.stonebreak.gameplay.SboGameplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item dictionaries gathered across every SBO on disk: dictionary id → member objectIds. Feeds the
 * "Dictionaries" field (which ids exist), the ingredient picker's Dictionaries filter, and the
 * cycling icon of a {@code #id} recipe slot.
 *
 * <p>Built once on first use like {@link SBOMiningIndex}; {@link #invalidate()} after saving an SBO
 * whose dictionaries changed. {@link #of} is pure so the aggregation is testable without disk.
 */
public final class SBODictionaryIndex {

    private static final Logger logger = LoggerFactory.getLogger(SBODictionaryIndex.class);

    private static volatile SBODictionaryIndex shared;

    private final Map<String, List<String>> members;

    private SBODictionaryIndex(Map<String, List<String>> members) {
        this.members = members;
    }

    /** Aggregates dictionary memberships from parsed manifests (ids sorted, members in input order). */
    public static SBODictionaryIndex of(List<SBOFormat.Document> manifests) {
        Map<String, List<String>> m = new TreeMap<>();
        for (SBOFormat.Document d : manifests) {
            if (d == null || d.objectId() == null) continue;
            for (String id : SboGameplay.of(d).dictionaries()) {
                List<String> list = m.computeIfAbsent(id, k -> new ArrayList<>());
                if (!list.contains(d.objectId())) list.add(d.objectId());
            }
        }
        Map<String, List<String>> frozen = new TreeMap<>();
        m.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new SBODictionaryIndex(Collections.unmodifiableMap(frozen));
    }

    /** The shared index, scanned on first use. */
    public static SBODictionaryIndex shared() {
        SBODictionaryIndex idx = shared;
        if (idx == null) {
            idx = scan();
            shared = idx;
        }
        return idx;
    }

    /** Drops the cached index; the next {@link #shared()} call re-reads the SBOs. */
    public static void invalidate() {
        shared = null;
    }

    /** Every known dictionary id (without {@code #}), sorted. */
    public List<String> ids() {
        return List.copyOf(members.keySet());
    }

    /** Member objectIds of {@code dictionaryId} (with or without {@code #}); empty when unknown. */
    public List<String> members(String dictionaryId) {
        return members.getOrDefault(strip(dictionaryId), List.of());
    }

    /** True when an ingredient string names a dictionary ({@code #id}). */
    public static boolean isRef(String ingredient) {
        return ingredient != null && ingredient.strip().startsWith(SboGameplay.DICTIONARY_PREFIX);
    }

    /** {@code id} → {@code "#id"}. */
    public static String ref(String dictionaryId) {
        return SboGameplay.DICTIONARY_PREFIX + strip(dictionaryId);
    }

    /** {@code "#id"} or {@code "id"} → {@code "id"}. */
    public static String strip(String ref) {
        if (ref == null) return "";
        String t = ref.strip();
        return t.startsWith(SboGameplay.DICTIONARY_PREFIX) ? t.substring(SboGameplay.DICTIONARY_PREFIX.length()).strip() : t;
    }

    /**
     * The objectId to draw for an ingredient: itself, or — for {@code #id} — the member shown at
     * {@code nowMs} (members cycle once per second). {@code null} for an empty dictionary.
     */
    public String displayObjectId(String ingredient, long nowMs) {
        if (!isRef(ingredient)) return ingredient;
        List<String> m = members(ingredient);
        return m.isEmpty() ? null : m.get((int) ((nowMs / 1000L) % m.size()));
    }

    /** Tooltip text for an ingredient string. */
    public String describe(String ingredient) {
        if (!isRef(ingredient)) return ingredient;
        List<String> m = members(ingredient);
        return m.isEmpty()
                ? ingredient + " (unknown dictionary: no SBO declares it)"
                : "Any of " + ingredient + " (" + m.size() + " member" + (m.size() == 1 ? "" : "s") + ")\n"
                        + String.join("\n", m);
    }

    private static SBODictionaryIndex scan() {
        SBOParser parser = new SBOParser();
        List<SBOFormat.Document> manifests = new ArrayList<>();
        for (SBOObjectIndex.Entry e : SBOObjectIndex.listAll()) {
            if (e.sourcePath() == null) continue;
            try {
                manifests.add(parser.parseRaw(e.sourcePath()).manifest());
            } catch (Exception ex) {
                logger.debug("Skipping unreadable SBO {} for the dictionary index: {}", e.sourcePath(), ex.getMessage());
            }
        }
        return of(manifests);
    }
}
