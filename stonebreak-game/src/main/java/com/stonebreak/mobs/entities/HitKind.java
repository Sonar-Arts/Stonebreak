package com.stonebreak.mobs.entities;

/**
 * What kind of player attack produced a hit. The server validates each kind by its own rules
 * (melee: reach, swing cooldown and the held weapon; ability: the class's ability profile;
 * projectile: simulated server-side, never claimed by a client), and echoes the kind back with
 * the hit result so the attacker's client knows which on-hit effects to run (Rage and
 * lifesteal for melee, Quarry for arrows).
 *
 * <p>The wire id is the ordinal.
 */
public enum HitKind {
    /** A swing resolved by {@code Player.attackEntity}. */
    MELEE,
    /** A class ability's direct hit (Rampage, Skull Crusher, Culling Shot). */
    ABILITY,
    /** A server-simulated projectile or zone (arrow, fire bolt, Null Spike, Leyline). */
    PROJECTILE;

    private static final HitKind[] VALUES = values();

    public byte wireId() {
        return (byte) ordinal();
    }

    /** The kind with wire id {@code id}, or null for an unknown id. */
    public static HitKind fromWire(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }
}
