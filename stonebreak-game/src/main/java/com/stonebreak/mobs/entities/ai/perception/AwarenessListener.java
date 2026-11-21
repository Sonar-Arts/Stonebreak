package com.stonebreak.mobs.entities.ai.perception;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController.AwarenessState;

/**
 * Told whenever a mob's awareness state changes (#321), so side effects — the pack shout, sounds,
 * the debug log — react to the change instead of polling every mob every tick.
 */
@FunctionalInterface
public interface AwarenessListener {

    /**
     * @param byShout whether a packmate's shout caused this change (such alerts do not shout on)
     */
    void onStateChanged(LivingEntity mob, AwarenessState from, AwarenessState to, boolean byShout);
}
