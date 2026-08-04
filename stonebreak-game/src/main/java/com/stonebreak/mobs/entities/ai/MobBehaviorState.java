package com.stonebreak.mobs.entities.ai;

/**
 * The shared behaviour-state vocabulary for all mob AIs.
 *
 * <p>Every mob's AI reports one of these states; the renderer, save system and
 * network replication all key off this single enum instead of per-mob copies.
 * A mob that doesn't use a state simply never enters it (its weight/chance is
 * zero in the AI config) — chickens never GRAZE, cows never WING_FLAP.
 *
 * <p>Constant names are load-bearing: the save system persists {@code name()}
 * and {@link com.stonebreak.mobs.sbe.MobStateMapping} maps each state to the
 * SBE animation clip name replicated to multiplayer clients.
 */
public enum MobBehaviorState {
    /** Standing still. */
    IDLE,
    /** Walking toward a wander (or flee) target. */
    WANDERING,
    /** Head down, eating — stationary. */
    GRAZING,
    /** Transient one-shot gesture (wing flap) played while idle. */
    WING_FLAP,
    /** Airborne. */
    FLYING,
    /** In water, under its own power — floating, paddling. */
    SWIMMING;

    /**
     * Whether the state's clip plays through once rather than looping. One-shot clips are sampled
     * from the state timer so they finish and hold, instead of looping a gesture forever.
     */
    public boolean isOneShot() {
        return this == WING_FLAP;
    }
}
