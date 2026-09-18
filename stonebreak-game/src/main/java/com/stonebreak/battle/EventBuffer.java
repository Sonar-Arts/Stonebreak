package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects events as they are raised and publishes them once per update, so the HUD, the camera
 * director and audio all see the same immutable list for a frame.
 */
final class EventBuffer {

    private final List<BattleEvent> pending = new ArrayList<>();
    private List<BattleEvent> published = List.of();

    void raise(BattleEvent event) {
        if (event != null) pending.add(event);
    }

    /** Replaces the published list with everything raised since the previous publish. */
    void publish() {
        published = List.copyOf(pending);
        pending.clear();
    }

    List<BattleEvent> published() {
        return published;
    }
}
