package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Locks in the two compatibility contracts the shared mob framework relies on: the SBE clip-name
 * round trip used by multiplayer animation replication, and the enum constant names persisted by
 * the save system.
 */
class MobStateMappingTest {

    /**
     * Replication round trip. States that share a clip cannot round-trip to themselves — a client
     * shadow only needs the clip — so the contract is that the state we get back maps to the same
     * clip we started from.
     */
    @Test
    void everyStateMapsToAClipThatMapsBackToTheSameClip() {
        for (EntityType type : new EntityType[]{EntityType.COW, EntityType.GOOSE}) {
            for (MobBehaviorState state : MobBehaviorState.values()) {
                String clipName = MobStateMapping.sbeState(type, state);
                assertNotNull(clipName, type + "/" + state + " must map to a clip name");
                MobBehaviorState roundTripped = MobStateMapping.behaviorState(type, clipName);
                assertEquals(clipName, MobStateMapping.sbeState(type, roundTripped),
                        "round trip for " + type + "/" + state);
            }
        }
    }

    /** Ground mobs: clip names are authored in the .sbe files — exact casing is load-bearing. */
    @Test
    void groundMobClipNamesMatchSbeAuthoring() {
        assertEquals("Idle", MobStateMapping.sbeState(EntityType.COW, MobBehaviorState.IDLE));
        assertEquals("Walking", MobStateMapping.sbeState(EntityType.COW, MobBehaviorState.WANDERING));
        assertEquals("Grazing", MobStateMapping.sbeState(EntityType.COW, MobBehaviorState.GRAZING));
        assertEquals("Wingflap", MobStateMapping.sbeState(EntityType.CHICKEN, MobBehaviorState.WING_FLAP));
    }

    /** The goose's asset was authored in lowercase and has a flying clip the others lack. */
    @Test
    void gooseClipNamesMatchItsOwnAuthoring() {
        assertEquals("idle", MobStateMapping.sbeState(EntityType.GOOSE, MobBehaviorState.IDLE));
        assertEquals("walking", MobStateMapping.sbeState(EntityType.GOOSE, MobBehaviorState.WANDERING));
        assertEquals("flying", MobStateMapping.sbeState(EntityType.GOOSE, MobBehaviorState.FLYING));
        assertEquals("idle", MobStateMapping.sbeState(EntityType.GOOSE, MobBehaviorState.SWIMMING),
                "a floating goose renders in its idle pose");

        assertEquals(MobBehaviorState.FLYING, MobStateMapping.behaviorState(EntityType.GOOSE, "flying"));
        assertEquals(MobBehaviorState.WANDERING, MobStateMapping.behaviorState(EntityType.GOOSE, "walking"));
    }

    /** Unknown or null replicated names fall back to IDLE instead of throwing. */
    @Test
    void unknownClipNamesFallBackToIdle() {
        assertEquals(MobBehaviorState.IDLE, MobStateMapping.behaviorState(EntityType.COW, null));
        assertEquals(MobBehaviorState.IDLE, MobStateMapping.behaviorState(EntityType.COW, "NoSuchClip"));
        assertEquals(MobBehaviorState.IDLE, MobStateMapping.behaviorState(EntityType.GOOSE, "NoSuchClip"));
    }

    /**
     * Save compatibility: EntitySerializer persists {@code name()} and restores via
     * {@code valueOf}, and pre-framework saves wrote these exact names from the old per-mob enums.
     * Renaming a constant breaks old worlds.
     */
    @Test
    void savedStateNamesAreStable() {
        assertEquals(MobBehaviorState.IDLE, MobBehaviorState.valueOf("IDLE"));
        assertEquals(MobBehaviorState.WANDERING, MobBehaviorState.valueOf("WANDERING"));
        assertEquals(MobBehaviorState.GRAZING, MobBehaviorState.valueOf("GRAZING"));
        assertEquals(MobBehaviorState.WING_FLAP, MobBehaviorState.valueOf("WING_FLAP"));
    }

    /** Only the wing flap plays once and holds; everything else loops. */
    @Test
    void onlyGesturesAreOneShot() {
        assertEquals(true, MobBehaviorState.WING_FLAP.isOneShot());
        for (MobBehaviorState state : MobBehaviorState.values()) {
            if (state != MobBehaviorState.WING_FLAP) {
                assertEquals(false, state.isOneShot(), state + " should loop");
            }
        }
    }
}
