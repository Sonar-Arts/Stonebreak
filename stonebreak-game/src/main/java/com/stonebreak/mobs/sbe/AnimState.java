package com.stonebreak.mobs.sbe;

import java.util.List;

/**
 * The full animation input for rendering one SBE entity: a base clip (state
 * name + time) plus any number of weighted overlay clips playing on top of it
 * (e.g. an attack owning the arms while walk drives the legs).
 *
 * <p>Overlay masks, fades, and priorities are authored in the {@code .omanim}
 * clip itself ({@code AnimLayerMeta}); the renderer resolves state names to
 * clips and applies per-part precedence. The {@code weight} here is the
 * caller's envelope (e.g. an early-exit fade from {@link OverlayAnimState}) —
 * it multiplies with the clip's own fade-in/out.
 *
 * @param baseState base clip state name (null → rest pose)
 * @param baseTime  elapsed time on the base clip in seconds
 * @param overlays  active overlays; empty for plain single-clip playback
 */
public record AnimState(String baseState, float baseTime, List<Overlay> overlays) {

    /**
     * One active overlay.
     *
     * @param stateName overlay clip state name
     * @param time      seconds since the overlay started (unwrapped — the
     *                  renderer wraps it and derives the clip fade from it)
     * @param weight    caller envelope weight in {@code [0,1]}
     */
    public record Overlay(String stateName, float time, float weight) {}

    public AnimState {
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
    }

    /** Plain single-clip playback — the pre-mixing behavior. */
    public static AnimState single(String stateName, float time) {
        return new AnimState(stateName, time, List.of());
    }

    public boolean hasOverlays() {
        return !overlays.isEmpty();
    }
}
