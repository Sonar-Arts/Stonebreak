package com.stonebreak.network.packet.player;

/**
 * Bit layout of the movement/action flags byte carried by {@link PlayerStateC2S} /
 * {@link PlayerStateS2C}. Lets remote players render real movement/action clips instead of
 * displacement-guessed walk/idle. Bits without an authored clip yet (sprint/sneak/swim —
 * {@code SB_Player.sbe} currently ships idle/walking/jumping/attacking) still replicate so
 * the clips light up the moment they're authored.
 */
public final class PlayerStateFlags {

    public static final int SPRINTING = 1;
    public static final int SNEAKING = 1 << 1;  // reserved — no sneak mechanic yet
    public static final int SWIMMING = 1 << 2;
    public static final int AIRBORNE = 1 << 3;
    public static final int ON_GROUND = 1 << 4;
    public static final int ATTACKING = 1 << 5;

    private PlayerStateFlags() {}

    public static boolean has(byte flags, int bit) {
        return (flags & bit) != 0;
    }
}
