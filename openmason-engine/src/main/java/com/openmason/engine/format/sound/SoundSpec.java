package com.openmason.engine.format.sound;

import java.util.Objects;

/**
 * One sound binding supplied to the SBO/SBE serializers at export time.
 * The export-input twin of {@link SoundDef}: instead of an archive entry
 * path + checksum it names either a source file on disk to embed, or a
 * classpath resource path to reference without embedding.
 *
 * @param event        event name the sample plays for (never blank)
 * @param sourcePath   absolute path of an audio file to embed, or null when
 *                     referencing a classpath resource instead
 * @param resourcePath absolute classpath resource path (e.g.
 *                     {@code /sounds/GrassWalk.wav}), or null when embedding
 * @param volume       base gain, {@code > 0}
 * @param pitchMin     lower bound of the random pitch range ({@code > 0})
 * @param pitchMax     upper bound of the random pitch range
 *                     ({@code >= pitchMin})
 * @param variation    per-def switch for the pitch-variation algorithm: when
 *                     true each playback draws a random pitch from the range;
 *                     when false the sample plays at its natural pitch and
 *                     the range is ignored (see {@link SoundDef})
 */
public record SoundSpec(
        String event,
        String sourcePath,
        String resourcePath,
        float volume,
        float pitchMin,
        float pitchMax,
        boolean variation
) {
    public SoundSpec {
        Objects.requireNonNull(event, "sound event cannot be null");
        if (event.isBlank()) {
            throw new IllegalArgumentException("sound event cannot be blank");
        }
        boolean embeds = sourcePath != null && !sourcePath.isBlank();
        boolean references = resourcePath != null && !resourcePath.isBlank();
        if (embeds == references) {
            throw new IllegalArgumentException(
                    "sound spec for event '" + event + "' must supply exactly one of"
                            + " a source file to embed or a resource path to reference");
        }
        if (!(volume > 0f)) {
            throw new IllegalArgumentException("sound volume must be > 0, got " + volume);
        }
        if (!(pitchMin > 0f) || pitchMax < pitchMin) {
            throw new IllegalArgumentException(
                    "sound pitch range must satisfy 0 < pitchMin <= pitchMax, got ["
                            + pitchMin + ", " + pitchMax + "]");
        }
    }

    /** True when this spec embeds a source file (vs. referencing a resource). */
    public boolean embeds() {
        return sourcePath != null && !sourcePath.isBlank();
    }
}
