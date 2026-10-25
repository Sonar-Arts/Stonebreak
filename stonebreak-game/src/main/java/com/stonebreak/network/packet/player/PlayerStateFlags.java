package com.stonebreak.network.packet.player;

/**
 * Bit layout of the movement/action flags byte carried by {@link PlayerStateC2S} /
 * {@link PlayerStateS2C}. Lets remote players render real movement/action clips instead of
 * displacement-guessed walk/idle, and tells the server what mob hearing needs (#321). Bits without
 * an authored clip yet (stealth/swim —
 * {@code SB_Player.sbe} currently ships idle/walking/sprinting/jumping/attacking) still replicate so
 * the clips light up the moment they're authored.
 */
public final class PlayerStateFlags {

    public static final int SPRINTING = 1;
    /** Fully stealthed (#321): stealth is the sneak mechanic, so it took the reserved sneak bit. */
    public static final int STEALTHED = 1 << 1;
    public static final int SWIMMING = 1 << 2;
    public static final int AIRBORNE = 1 << 3;
    public static final int ON_GROUND = 1 << 4;
    public static final int ATTACKING = 1 << 5;
    /** Body touching water (not just eyes) — drives remote splash/ripple triggering. */
    public static final int PARTIALLY_IN_WATER = 1 << 6;
    /** Inside a dodge's noise window — the scuff mobs can hear (#321). */
    public static final int DODGE_NOISE = 1 << 7;

    private PlayerStateFlags() {}

    public static boolean has(byte flags, int bit) {
        return (flags & bit) != 0;
    }
}
