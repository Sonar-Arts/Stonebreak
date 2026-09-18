package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.StatusView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The statuses on one combatant. A negative remaining time means "until consumed" (GUARDING, SURGE)
 * and never ticks down.
 */
final class StatusSet {

    private record Entry(float remaining, int stacks) {}

    // EnumMap: iteration order is the enum order, so views and expiry events are deterministic.
    private final Map<BattleStatus, Entry> active = new EnumMap<>(BattleStatus.class);

    boolean has(BattleStatus status) {
        return active.containsKey(status);
    }

    /** Applies or refreshes a status. {@code seconds < 0} = until consumed. */
    void apply(BattleStatus status, float seconds, int stacks) {
        active.put(status, new Entry(seconds, Math.max(1, stacks)));
    }

    /** Removes a status; returns whether it was present. */
    boolean remove(BattleStatus status) {
        return active.remove(status) != null;
    }

    void clear() {
        active.clear();
    }

    /** Seconds until the next timed status runs out; infinite when none is timed. */
    float timeToNextExpiry() {
        float next = Float.POSITIVE_INFINITY;
        for (Entry e : active.values()) {
            if (e.remaining() >= 0f) next = Math.min(next, e.remaining());
        }
        return next;
    }

    /** Ticks the timed statuses and returns the ones that just expired (already removed). */
    List<BattleStatus> advance(float dt) {
        List<BattleStatus> expired = null;
        for (Map.Entry<BattleStatus, Entry> e : active.entrySet()) {
            Entry entry = e.getValue();
            if (entry.remaining() < 0f) continue;
            if (dt >= entry.remaining()) {
                if (expired == null) expired = new ArrayList<>(2);
                expired.add(e.getKey());
            } else {
                e.setValue(new Entry(entry.remaining() - dt, entry.stacks()));
            }
        }
        if (expired == null) return List.of();
        for (BattleStatus s : expired) active.remove(s);
        return expired;
    }

    List<StatusView> views() {
        if (active.isEmpty()) return List.of();
        List<StatusView> out = new ArrayList<>(active.size());
        for (Map.Entry<BattleStatus, Entry> e : active.entrySet()) {
            out.add(new StatusView(e.getKey(), e.getValue().remaining(), e.getValue().stacks()));
        }
        return List.copyOf(out);
    }
}
