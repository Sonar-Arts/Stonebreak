package com.stonebreak.battle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The artists' side of the animation contract, read from the classpath exactly as shipped: the two
 * {@code clips.json} handoffs and the state tables inside the two {@code .sbe} archives. Shared
 * fixture, not a test.
 */
final class ClipCatalog {

    static final String MONK_JSON = "/sbe/Mobs/player-combat/clips.json";
    static final String ARCHON_JSON = "/sbe/Mobs/ice-archon-combat/clips.json";
    static final String MONK_SBE = "/sbe/Mobs/SB_Player.sbe";
    static final String ARCHON_SBE = "/sbe/Mobs/SB_Ice_Archon.sbe";

    /** One authored clip: what the JSON (or the SBE manifest, which carries no cues) says about it. */
    record Authored(String state, float duration, boolean loop, List<Float> cues) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClipCatalog() {}

    /** Monk handoff: {@code clips[].cues[].timeSeconds}. */
    static Map<String, Authored> monkJson() {
        return clips(MONK_JSON, "cues", true);
    }

    /** Archon handoff: {@code clips[].cueSeconds[]}. */
    static Map<String, Authored> archonJson() {
        return clips(ARCHON_JSON, "cueSeconds", false);
    }

    static List<String> monkLegacyStates() {
        List<String> out = new ArrayList<>();
        for (JsonNode n : read(MONK_JSON).path("legacyStatesPreserved")) out.add(n.asText());
        return out;
    }

    /** Archon handoff: {@code legacyAttackImpactsSeconds}, state name → impact time. */
    static Map<String, Float> archonAttackImpacts() {
        Map<String, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : read(ARCHON_JSON).path("legacyAttackImpactsSeconds").properties()) {
            out.put(e.getKey(), (float) e.getValue().asDouble());
        }
        return out;
    }

    /** Every state embedded in an SBE archive (its {@code manifest.json}), which is what the renderer plays. */
    static Map<String, Authored> sbeStates(String resource) {
        try (InputStream in = open(resource); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("manifest.json")) continue;
                Map<String, Authored> out = new LinkedHashMap<>();
                for (JsonNode state : JSON.readTree(zip.readAllBytes()).path("states")) {
                    JsonNode anim = state.path("animation");
                    String name = state.path("name").asText();
                    out.put(name, new Authored(name, (float) anim.path("duration").asDouble(),
                            anim.path("loop").asBoolean(), List.of()));
                }
                return out;
            }
            throw new IllegalStateException(resource + " has no manifest.json");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    private static Map<String, Authored> clips(String resource, String cueField, boolean cueObjects) {
        Map<String, Authored> out = new LinkedHashMap<>();
        for (JsonNode clip : read(resource).path("clips")) {
            List<Float> cues = new ArrayList<>();
            for (JsonNode cue : clip.path(cueField)) {
                cues.add((float) (cueObjects ? cue.path("timeSeconds") : cue).asDouble());
            }
            String state = clip.path("state").asText();
            out.put(state, new Authored(state, (float) clip.path("durationSeconds").asDouble(),
                    clip.path("loop").asBoolean(), List.copyOf(cues)));
        }
        return out;
    }

    private static JsonNode read(String resource) {
        try (InputStream in = open(resource)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = ClipCatalog.class.getResourceAsStream(resource);
        if (in == null) throw new IllegalStateException("missing classpath resource " + resource);
        return in;
    }
}
