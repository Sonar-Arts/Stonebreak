package com.openmason.engine.format.sound;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared JSON glue for the manifest {@code sounds[]} section, used by both
 * the SBO and SBE parsers/serializers so the wire shape stays identical
 * across the two formats.
 *
 * <p>Wire shape (one object per {@link SoundDef}):
 * <pre>{@code
 * "sounds": [
 *   {"event": "step",  "resource": "/sounds/GrassWalk.wav",
 *    "volume": 1.0, "pitchMin": 0.9, "pitchMax": 1.1, "variation": true},
 *   {"event": "break", "file": "sounds/break_0.wav", "checksum": "…",
 *    "volume": 1.0, "pitchMin": 0.75, "pitchMax": 0.85, "variation": true}
 * ]
 * }</pre>
 *
 * <p>Absent keys default to the legacy walking-sound behaviour:
 * {@code variation=true} over a 0.9–1.1 pitch range and volume 1.0.
 */
public final class SoundJson {

    private static final Logger logger = LoggerFactory.getLogger(SoundJson.class);

    private SoundJson() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the optional {@code sounds} array from a manifest root node.
     * Invalid entries are skipped with a warning (matching the recipe-block
     * parsing convention); returns null when the section is absent or ends
     * up empty — older files simply lack the key.
     */
    public static SoundData read(JsonNode root) {
        if (root == null || !root.has("sounds") || root.get("sounds").isNull()
                || !root.get("sounds").isArray()) {
            return null;
        }
        List<SoundDef> defs = new ArrayList<>();
        for (JsonNode node : root.get("sounds")) {
            try {
                float pitchMin = node.hasNonNull("pitchMin")
                        ? (float) node.get("pitchMin").asDouble() : 0.9f;
                float pitchMax = node.hasNonNull("pitchMax")
                        ? (float) node.get("pitchMax").asDouble() : 1.1f;
                // A def authoring only one bound shouldn't be dropped because
                // the other bound's default lands below it — pin the range.
                pitchMax = Math.max(pitchMax, pitchMin);
                defs.add(new SoundDef(
                        node.hasNonNull("event") ? node.get("event").asText() : null,
                        node.hasNonNull("file") ? node.get("file").asText() : null,
                        node.hasNonNull("checksum") ? node.get("checksum").asText() : null,
                        node.hasNonNull("resource") ? node.get("resource").asText() : null,
                        node.hasNonNull("volume") ? (float) node.get("volume").asDouble() : 1f,
                        pitchMin,
                        pitchMax,
                        !node.hasNonNull("variation") || node.get("variation").asBoolean()
                ));
            } catch (IllegalArgumentException | NullPointerException ex) {
                logger.warn("Skipping invalid sound def in manifest: {}", ex.getMessage());
            }
        }
        return defs.isEmpty() ? null : new SoundData(defs);
    }

    /** DTO list for Jackson field serialization; null when there is nothing to write. */
    public static List<SoundDefDTO> toDto(SoundData data) {
        if (data == null || data.isEmpty()) return null;
        List<SoundDefDTO> dtos = new ArrayList<>(data.sounds().size());
        for (SoundDef def : data.sounds()) {
            dtos.add(new SoundDefDTO(def));
        }
        return dtos;
    }

    /** DTO mirror of {@link SoundDef}; Jackson serializes the public fields. */
    public static final class SoundDefDTO {
        public String event;
        public String file;
        public String checksum;
        public String resource;
        public float volume;
        public float pitchMin;
        public float pitchMax;
        public boolean variation;

        public SoundDefDTO(SoundDef def) {
            this.event = def.event();
            this.file = def.filename();
            this.checksum = def.checksum();
            this.resource = def.resourcePath();
            this.volume = def.volume();
            this.pitchMin = def.pitchMin();
            this.pitchMax = def.pitchMax();
            this.variation = def.variation();
        }
    }
}
