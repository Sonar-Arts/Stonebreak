package com.stonebreak.mobs.entities;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A revolving clock for each player, running while they stay in the Shade: every {@code period}
 * seconds it comes round and starts the next lap. Leaving the Shade, dying or disconnecting sets it
 * back to zero.
 */
final class ShadeClock {

    private final float period;
    private final Map<Integer, Float> seconds = new HashMap<>();

    ShadeClock(float period) {
        this.period = period;
    }

    /**
     * Runs every live player's clock now in the Shade on by {@code deltaSeconds}, stops everyone
     * else's, and returns the players whose clock came round.
     */
    List<EntitySpawner.SpawnAnchor> update(List<EntitySpawner.SpawnAnchor> anchors, float deltaSeconds,
                                           Predicate<Vector3f> inShade) {
        Map<Integer, Float> next = new HashMap<>();
        List<EntitySpawner.SpawnAnchor> due = new ArrayList<>();
        for (EntitySpawner.SpawnAnchor anchor : anchors) {
            if (anchor.spawnsMobs() && inShade.test(anchor.position())) {
                float t = seconds(anchor.playerId()) + deltaSeconds;
                if (t >= period) {
                    t -= period;
                    due.add(anchor);
                }
                next.put(anchor.playerId(), t);
            }
        }
        seconds.clear();
        seconds.putAll(next);
        return due;
    }

    /** How far round {@code playerId}'s clock is, 0 if they aren't in the Shade. */
    float seconds(int playerId) {
        return seconds.getOrDefault(playerId, 0f);
    }
}
