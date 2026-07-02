package com.openmason.engine.format.oma;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless pose layering: fold weighted overlay clips over a base clip,
 * per part. The caller samples/owns nothing stateful here — pass the base
 * track, the overlay frames (clip + already-wrapped time + weight), and the
 * part identity; get the final local pose back.
 *
 * <p>Composition per part:
 * <ol>
 *   <li>Start from the base track's sample (or the rest pose if the base
 *       clip doesn't animate this part).</li>
 *   <li>For each overlay whose {@link AnimLayerMeta#masksPart mask} covers
 *       the part, in ascending {@link AnimLayerMeta#priority() priority}:
 *       {@code pose = lerpPose(pose, overlaySample, weight)} — so the
 *       highest-priority overlay wins contested parts.</li>
 * </ol>
 *
 * <p>Weights combine the caller's envelope (e.g. an early-exit fade) with
 * {@link #clipWeight}, the fade envelope derivable from clip metadata alone.
 */
public final class AnimLayering {

    private AnimLayering() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * One overlay's contribution this frame.
     *
     * @param clip   the overlay clip (its {@code layer()} supplies mask + priority)
     * @param time   clip-relative time, already wrapped/clamped by the caller
     * @param weight blend weight in {@code [0,1]} — typically
     *               {@code envelopeWeight * clipWeight(clip, elapsed)}
     */
    public record OverlayFrame(ParsedAnimClip clip, float time, float weight) {}

    /**
     * Final local pose of one part under a base clip plus overlays.
     *
     * @param restPose  the part's rest pose, returned when nothing animates it
     * @param baseTrack the base clip's track for this part (may be null)
     * @param baseTime  base clip time, already wrapped
     * @param overlays  active overlays; order does not matter (sorted by priority)
     * @param partId    the part's id (fallback mask key)
     * @param partName  the part's name (primary mask key)
     * @return the blended pose; {@code restPose} itself when nothing applies
     */
    public static AnimSampler.PartPose blendPart(AnimSampler.PartPose restPose,
                                                 ParsedAnimTrack baseTrack, float baseTime,
                                                 List<OverlayFrame> overlays,
                                                 String partId, String partName) {
        AnimSampler.PartPose pose = (baseTrack != null && !baseTrack.isEmpty())
                ? AnimSampler.sample(baseTrack, baseTime)
                : restPose;

        if (overlays == null || overlays.isEmpty()) {
            return pose;
        }

        for (OverlayFrame overlay : sortedByPriority(overlays)) {
            if (overlay.weight() <= 0f || overlay.clip() == null) continue;
            AnimLayerMeta layer = overlay.clip().layer();
            if (!layer.masksPart(partId, partName)) continue;

            ParsedAnimTrack track = findTrack(overlay.clip(), partId, partName);
            if (track == null || track.isEmpty()) continue;

            AnimSampler.PartPose overlayPose = AnimSampler.sample(track, overlay.time());
            pose = AnimSampler.lerpPose(pose, overlayPose, Math.min(overlay.weight(), 1f));
        }
        return pose;
    }

    /**
     * The fade envelope derivable from clip metadata alone: ramps in over
     * {@code fadeInSeconds}, and for non-looping clips ramps out over the
     * last {@code fadeOutSeconds} before the clip ends. Looping overlays
     * never fade out here — an early-exit envelope (caller-owned) handles
     * ending them.
     *
     * @param overlay the overlay clip
     * @param elapsed seconds since the overlay started (not wrapped)
     * @return weight in {@code [0,1]}
     */
    public static float clipWeight(ParsedAnimClip overlay, float elapsed) {
        if (overlay == null) return 0f;
        AnimLayerMeta layer = overlay.layer();

        float in = layer.fadeInSeconds() <= 0f ? 1f
                : Math.min(elapsed / layer.fadeInSeconds(), 1f);

        float out = 1f;
        if (!overlay.loop() && overlay.duration() > 0f && layer.fadeOutSeconds() > 0f) {
            float remaining = overlay.duration() - elapsed;
            out = Math.min(remaining / layer.fadeOutSeconds(), 1f);
        }

        return clamp01(Math.min(in, out));
    }

    private static List<OverlayFrame> sortedByPriority(List<OverlayFrame> overlays) {
        if (overlays.size() == 1) return overlays;
        List<OverlayFrame> sorted = new ArrayList<>(overlays);
        sorted.sort((a, b) -> {
            int pa = a.clip() != null ? a.clip().layer().priority() : 0;
            int pb = b.clip() != null ? b.clip().layer().priority() : 0;
            return Integer.compare(pa, pb);
        });
        return sorted;
    }

    /** Track lookup by part id with a name fallback, matching runtime rebind rules. */
    private static ParsedAnimTrack findTrack(ParsedAnimClip clip, String partId, String partName) {
        for (ParsedAnimTrack track : clip.tracks()) {
            if (track.partId() != null && track.partId().equals(partId)) return track;
        }
        if (partName == null) return null;
        for (ParsedAnimTrack track : clip.tracks()) {
            if (partName.equalsIgnoreCase(track.partName())) return track;
        }
        return null;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
