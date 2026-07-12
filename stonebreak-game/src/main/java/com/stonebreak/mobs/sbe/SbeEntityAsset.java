package com.stonebreak.mobs.sbe;

import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.sound.SoundData;

import java.util.Map;

/**
 * A fully resolved, immutable entity asset decoded from an {@code .sbe} file.
 *
 * <p>Holds one {@link SbeModelGeometry} per appearance variant and one
 * {@link ParsedAnimClip} per behaviour state, plus the SBE's {@code objectId}
 * by which the registry indexes it. The asset carries no behavioural knowledge
 * of which entity it represents — variant and state names are plain strings
 * keyed by whatever the SBE author chose — so it can back any SBE-driven mob.
 *
 * @param objectId   the SBE manifest object id (e.g. {@code stonebreak:cow})
 * @param variants   variant name (e.g. {@code Default}) → resolved geometry
 * @param clips      SBE state name (e.g. {@code Idle}) → animation clip
 * @param sounds     the SBE's sound bindings (1.4+), or null when it declares
 *                   none; resolved for playback by the audio layer
 * @param soundBytes embedded sound sample bytes keyed by archive entry
 *                   filename (1.4+); empty when no def embeds audio
 */
public record SbeEntityAsset(
        String objectId,
        Map<String, SbeModelGeometry> variants,
        Map<String, ParsedAnimClip> clips,
        SoundData sounds,
        Map<String, byte[]> soundBytes
) {
    /** The variant used when a requested name is unknown. */
    public static final String DEFAULT_VARIANT = "Default";

    public SbeEntityAsset {
        variants = variants == null ? Map.of() : Map.copyOf(variants);
        clips = clips == null ? Map.of() : Map.copyOf(clips);
        soundBytes = soundBytes == null ? Map.of() : Map.copyOf(soundBytes);
    }

    /** Soundless convenience constructor (bare OMO/SBO attachables, tests). */
    public SbeEntityAsset(String objectId,
                          Map<String, SbeModelGeometry> variants,
                          Map<String, ParsedAnimClip> clips) {
        this(objectId, variants, clips, null, Map.of());
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

    /** Animation clip for an SBE state name, or {@code null} if absent or {@code stateName} is null. */
    public ParsedAnimClip clipFor(String stateName) {
        return stateName != null ? clips.get(stateName) : null;
    }

    /** Bytes of an embedded sound sample by entry filename, or null (1.4+). */
    public byte[] soundBytesFor(String filename) {
        return soundBytes.get(filename);
    }
}
