package com.openmason.engine.format.oma;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Per-clip layering metadata for animation mixing.
 *
 * <p>A {@code BASE} clip drives the whole model (locomotion: idle, walk). An
 * {@code OVERLAY} clip plays on top of a base clip and takes over only the
 * parts named in {@link #maskParts()} (e.g. an attack owning the arms while
 * walk keeps the legs), blending in and out over the fade durations.
 *
 * <p>Parts are masked by <em>name</em>, not id: part ids are per-model UUIDs
 * that drift between models, while names are the stable identity the runtime
 * already rebinds tracks with. An empty mask means "all parts".
 *
 * @param type            BASE or OVERLAY
 * @param maskParts       part names the clip owns when used as an overlay;
 *                        empty = all parts
 * @param fadeInSeconds   blend-in duration when the overlay starts
 * @param fadeOutSeconds  blend-out duration at the natural end of a
 *                        non-looping overlay (and the ramp used on early exit)
 * @param priority        ordering among simultaneous overlays — higher wins
 *                        contested parts
 */
public record AnimLayerMeta(
        LayerType type,
        List<String> maskParts,
        float fadeInSeconds,
        float fadeOutSeconds,
        int priority
) {
    public enum LayerType {
        BASE, OVERLAY;

        /** Case-insensitive parse falling back to {@link #BASE}. */
        public static LayerType fromString(String name) {
            if (name == null || name.isBlank()) return BASE;
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return BASE;
            }
        }
    }

    /** Default fade applied when a clip doesn't author one. */
    public static final float DEFAULT_FADE_SECONDS = 0.1f;

    private static final AnimLayerMeta BASE_META =
            new AnimLayerMeta(LayerType.BASE, List.of(),
                    DEFAULT_FADE_SECONDS, DEFAULT_FADE_SECONDS, 0);

    public AnimLayerMeta {
        if (type == null) type = LayerType.BASE;
        maskParts = maskParts == null ? Collections.emptyList() : List.copyOf(maskParts);
        if (fadeInSeconds < 0f) fadeInSeconds = 0f;
        if (fadeOutSeconds < 0f) fadeOutSeconds = 0f;
    }

    /** The default metadata for clips with no authored layer: a full-body BASE. */
    public static AnimLayerMeta base() {
        return BASE_META;
    }

    /**
     * Whether this clip's mask covers the given part. Matches by name,
     * case-insensitively, with the id accepted as a fallback key; an empty
     * mask covers everything.
     */
    public boolean masksPart(String partId, String partName) {
        if (maskParts.isEmpty()) return true;
        for (String mask : maskParts) {
            if (partName != null && mask.equalsIgnoreCase(partName)) return true;
            if (partId != null && mask.equals(partId)) return true;
        }
        return false;
    }
}
