package com.stonebreak.network.packet.player;

/**
 * Bit layout of the movement/action flags (16 bits since protocol 22) carried by {@link PlayerStateC2S} /
 * {@link PlayerStateS2C}. Lets remote players render real movement/action clips instead of
 * displacement-guessed walk/idle. Bits without an authored clip yet (sneak/swim —
 * {@code SB_Player.sbe} currently ships idle/walking/sprinting/jumping/attacking) still replicate so
 * the clips light up the moment they're authored.
 */
public final class PlayerStateFlags {

    public static final int SPRINTING = 1;
    public static final int SNEAKING = 1 << 1;  // reserved — no sneak mechanic yet
    public static final int SWIMMING = 1 << 2;
    public static final int AIRBORNE = 1 << 3;
    public static final int ON_GROUND = 1 << 4;
    public static final int ATTACKING = 1 << 5;
    /** Body touching water (not just eyes) — drives remote splash/ripple triggering. */
    public static final int PARTIALLY_IN_WATER = 1 << 6;
    /**
     * Holding the attack on a block that takes time to break (issue #344). The server plays the
     * mining "hit" sounds to nearby players while this is set for the block it was told about
     * ({@code BlockBreakStartC2S}); clearing it is how the server learns mining stopped.
     */
    public static final int MINING = 1 << 7;
    /** Flying (creative-style flight or spectator): no fall damage, no cactus contact (#343). */
    public static final int FLYING = 1 << 8;
    /**
     * Inside a dodge's invincibility window (#343). The server negates combat damage for the
     * dodge's i-frame time after this rises, at most once per dodge cooldown.
     */
    public static final int DODGING = 1 << 9;
    /** Rage at T3: melee hits heal by lifesteal; honoured for Berserkers only (#343). */
    public static final int LIFESTEAL = 1 << 10;

    private PlayerStateFlags() {}

    public static boolean has(short flags, int bit) {
        return (flags & bit) != 0;
    }
}
