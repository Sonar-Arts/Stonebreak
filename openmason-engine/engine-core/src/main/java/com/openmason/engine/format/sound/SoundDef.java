package com.openmason.engine.format.sound;

import java.util.Objects;

/**
 * One sound binding in an SBO/SBE manifest {@code sounds[]} section
 * (SBO 1.7+ / SBE 1.4+).
 *
 * <p>A def binds an <em>event</em> name (see {@link SoundData} for the
 * standard block events; names are free-form so assets may declare custom
 * events) to a single audio sample. Several defs may share an event — the
 * runtime picks one at random per trigger for natural variation.
 *
 * <p>The sample lives in exactly one of two places:
 * <ul>
 *   <li><b>Embedded</b> — {@code filename} is a ZIP entry path inside the
 *       archive (under {@code sounds/}); {@code checksum} is the SHA-256 of
 *       those bytes ("" for tool-side stubs, recomputed on save).</li>
 *   <li><b>Referenced</b> — {@code resourcePath} is an absolute classpath
 *       resource in the consuming game module (e.g.
 *       {@code /sounds/GrassWalk.wav}); nothing is embedded. This lets many
 *       assets share one shipped sample without duplicating audio bytes.</li>
 * </ul>
 *
 * <p><b>Pitch variation</b>: {@code variation} is a per-def switch for the
 * classic noise-alteration algorithm (originally the hardcoded walking-sound
 * behaviour): when {@code true}, every playback draws a random pitch from
 * {@code [pitchMin, pitchMax]} so repeated triggers don't sound identical;
 * when {@code false}, the sample plays at its natural pitch (1.0) and the
 * range is ignored. The manifest defaults are {@code variation=true} with a
 * 0.9–1.1 range — exactly the legacy ±10% algorithm.
 *
 * @param event        event name this sample plays for (never blank)
 * @param filename     ZIP entry path of the embedded sample, or null when
 *                     the def references a classpath resource instead
 * @param checksum     SHA-256 of the embedded bytes; null when not embedded
 * @param resourcePath classpath resource path of a shared sample, or null
 *                     when the sample is embedded
 * @param volume       base gain, {@code > 0} (typically 0..1)
 * @param pitchMin     lower bound of the random pitch range ({@code > 0})
 * @param pitchMax     upper bound of the random pitch range
 *                     ({@code >= pitchMin})
 * @param variation    whether the pitch-variation algorithm runs per playback
 */
public record SoundDef(
        String event,
        String filename,
        String checksum,
        String resourcePath,
        float volume,
        float pitchMin,
        float pitchMax,
        boolean variation
) {
    public SoundDef {
        Objects.requireNonNull(event, "sound event cannot be null");
        if (event.isBlank()) {
            throw new IllegalArgumentException("sound event cannot be blank");
        }
        boolean embedded = filename != null && !filename.isBlank();
        boolean referenced = resourcePath != null && !resourcePath.isBlank();
        if (embedded == referenced) {
            throw new IllegalArgumentException(
                    "sound def for event '" + event + "' must carry exactly one of"
                            + " an embedded file or a resource path (got file="
                            + filename + ", resource=" + resourcePath + ")");
        }
        if (embedded && checksum == null) {
            throw new IllegalArgumentException(
                    "embedded sound def for event '" + event + "' requires a checksum"
                            + " (use \"\" for tool-side stubs)");
        }
        if (!(volume > 0f)) {
            throw new IllegalArgumentException(
                    "sound volume must be > 0, got " + volume);
        }
        if (!(pitchMin > 0f) || pitchMax < pitchMin) {
            throw new IllegalArgumentException(
                    "sound pitch range must satisfy 0 < pitchMin <= pitchMax, got ["
                            + pitchMin + ", " + pitchMax + "]");
        }
    }

    /** True when the sample bytes are embedded in the archive. */
    public boolean isEmbedded() {
        return filename != null && !filename.isBlank();
    }
}
