package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Locks in the two compatibility contracts the shared mob framework relies on:
 * the SBE clip-name round trip used by multiplayer animation replication, and
 * the enum constant names persisted by the save system.
 */
class MobStateMappingTest {

    /** Replication round trip: behaviorState(sbeState(s)) == s for every state. */
    @Test
    void sbeClipNamesRoundTrip() {
        for (MobBehaviorState state : MobBehaviorState.values()) {
            String clipName = MobStateMapping.sbeState(state);
            assertNotNull(clipName, state + " must map to a clip name");
            assertEquals(state, MobStateMapping.behaviorState(clipName),
                    "round trip for " + state);
        }
    }

    /** Clip names are authored in the .sbe files — exact casing is load-bearing. */
    @Test
    void clipNamesMatchSbeAuthoring() {
        assertEquals("Idle", MobStateMapping.sbeState(MobBehaviorState.IDLE));
        assertEquals("Walking", MobStateMapping.sbeState(MobBehaviorState.WANDERING));
        assertEquals("Grazing", MobStateMapping.sbeState(MobBehaviorState.GRAZING));
        assertEquals("Wingflap", MobStateMapping.sbeState(MobBehaviorState.WING_FLAP));
    }

    /** Unknown or null replicated names fall back to IDLE instead of throwing. */
    @Test
    void unknownClipNamesFallBackToIdle() {
        assertEquals(MobBehaviorState.IDLE, MobStateMapping.behaviorState(null));
        assertEquals(MobBehaviorState.IDLE, MobStateMapping.behaviorState("NoSuchClip"));
    }

    /**
     * Save compatibility: EntitySerializer persists {@code name()} and restores
     * via {@code valueOf}, and pre-framework saves wrote these exact names from
     * the old per-mob enums. Renaming a constant breaks old worlds.
     */
    @Test
    void savedStateNamesAreStable() {
        assertEquals(MobBehaviorState.IDLE, MobBehaviorState.valueOf("IDLE"));
        assertEquals(MobBehaviorState.WANDERING, MobBehaviorState.valueOf("WANDERING"));
        assertEquals(MobBehaviorState.GRAZING, MobBehaviorState.valueOf("GRAZING"));
        assertEquals(MobBehaviorState.WING_FLAP, MobBehaviorState.valueOf("WING_FLAP"));
    }
}
