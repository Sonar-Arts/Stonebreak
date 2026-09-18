package com.stonebreak.battle.camera;

/**
 * What an {@link AnchoredPoint} is measured from. Shots are authored against the actors rather than
 * the world so the library survives a different arena or spawn layout.
 */
public enum CameraAnchor {
    /** The monk; forward = toward the Archon. */
    MONK,
    /** The Archon; forward = toward the monk. */
    ARCHON,
    /** Halfway between the two actors (their live positions when the point follows poses); forward = monk→Archon. */
    MIDPOINT,
    /** Halfway between the two home rings, never moves; forward = monk→Archon. */
    ARENA_CENTRE
}
