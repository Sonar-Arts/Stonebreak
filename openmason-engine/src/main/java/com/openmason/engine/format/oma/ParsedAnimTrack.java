package com.openmason.engine.format.oma;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * Keyframe track for one model part within an {@link ParsedAnimClip}.
 *
 * <p>Keyframes are held immutable and sorted ascending by {@link ParsedKeyframe#time()}.
 * The track is keyed by {@code partId}, which matches the OMO model's part UUID;
 * {@code partName} is retained for diagnostics and as a fallback match key.
 *
 * @param partId    UUID of the model part this track drives
 * @param partName  human-readable part name (diagnostic / fallback)
 * @param keyframes time-sorted, immutable keyframe list
 */
public record ParsedAnimTrack(
        String partId,
        String partName,
        List<ParsedKeyframe> keyframes
) {
    public ParsedAnimTrack {
        if (keyframes == null) {
            keyframes = Collections.emptyList();
        } else {
            List<ParsedKeyframe> sorted = new ArrayList<>(keyframes);
            sorted.sort(Comparator.comparingDouble(ParsedKeyframe::time));
            keyframes = Collections.unmodifiableList(sorted);
        }
    }

    /** True when this track has no keyframes to sample. */
    public boolean isEmpty() {
        return keyframes.isEmpty();
    }
}
