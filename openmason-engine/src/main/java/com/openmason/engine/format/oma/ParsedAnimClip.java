package com.openmason.engine.format.oma;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable animation clip decoded from an {@code .omanim} archive.
 *
 * <p>A clip is a named, fixed-duration set of per-part {@link ParsedAnimTrack}s.
 * Tracks are keyed by {@code partId} (the OMO part UUID); {@link #trackByPartId()}
 * exposes a lookup map built once at construction.
 *
 * @param name     clip name (e.g. {@code "Cow_Idle"})
 * @param fps      authoring frame rate
 * @param duration clip length in seconds
 * @param loop     whether playback should wrap at {@code duration}
 * @param tracks   per-part keyframe tracks
 */
public record ParsedAnimClip(
        String name,
        float fps,
        float duration,
        boolean loop,
        List<ParsedAnimTrack> tracks
) {
    public ParsedAnimClip {
        tracks = tracks == null ? Collections.emptyList() : List.copyOf(tracks);
    }

    /** Lookup of tracks by their {@code partId}. Insertion-ordered. */
    public Map<String, ParsedAnimTrack> trackByPartId() {
        Map<String, ParsedAnimTrack> map = new LinkedHashMap<>();
        for (ParsedAnimTrack track : tracks) {
            map.put(track.partId(), track);
        }
        return map;
    }

    /** The {@code partId}s this clip animates. */
    public List<String> requiredParts() {
        List<String> ids = new ArrayList<>(tracks.size());
        for (ParsedAnimTrack track : tracks) {
            ids.add(track.partId());
        }
        return ids;
    }
}
