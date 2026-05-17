package com.stonebreak.mobs.sbe;

import com.openmason.engine.format.oma.ParsedAnimClip;

import java.util.Map;

/**
 * Fully resolved, immutable cow asset decoded from {@code SB_Cow.sbe}.
 *
 * <p>Holds one {@link SbeModelGeometry} per appearance variant and one
 * {@link ParsedAnimClip} per behaviour state. Built once by {@link SbeCowLoader}
 * and shared by every cow in the world.
 *
 * @param variants variant name (e.g. {@code Default}) → resolved geometry
 * @param clips    SBE state name (e.g. {@code Idle}) → animation clip
 */
public record SbeCowAsset(
        Map<String, SbeModelGeometry> variants,
        Map<String, ParsedAnimClip> clips
) {
    /** The variant used when a requested name is unknown. */
    public static final String DEFAULT_VARIANT = "Default";

    public SbeCowAsset {
        variants = variants == null ? Map.of() : Map.copyOf(variants);
        clips = clips == null ? Map.of() : Map.copyOf(clips);
    }

    /**
     * Geometry for a variant, matched case-insensitively. Falls back to
     * {@link #DEFAULT_VARIANT} when the name is unknown.
     */
    public SbeModelGeometry geometryFor(String variantName) {
        if (variantName != null) {
            for (Map.Entry<String, SbeModelGeometry> e : variants.entrySet()) {
                if (e.getKey().equalsIgnoreCase(variantName)) {
                    return e.getValue();
                }
            }
        }
        return variants.get(DEFAULT_VARIANT);
    }

    /** Animation clip for an SBE state name, or {@code null} if absent. */
    public ParsedAnimClip clipFor(String stateName) {
        return clips.get(stateName);
    }
}
