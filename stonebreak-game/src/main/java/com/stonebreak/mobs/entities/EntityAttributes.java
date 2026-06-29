package com.stonebreak.mobs.entities;

import com.stonebreak.player.PlayerConstants;

/**
 * Six-attribute allocation for living creatures (STR/DEX/CON/INT/WIS/CHA).
 * Derives gameplay-equivalent stats (HP, speed, melee damage) so the EntityType
 * enum can drop hardcoded literals while preserving byte-identical values.
 *
 * <p>Constants-only dependencies — safe in enum clinit; never references Game/Player instances.
 */
public record EntityAttributes(
    int str,
    int dex,
    int con,
    int intel,
    int wis,
    int cha
) {
    /** CON × 2 (matches player: CON 10 → 20 HP). */
    public float deriveMaxHealth() {
        return con * PlayerConstants.HEALTH_PER_CON_POINT;
    }

    /** 1.0 + 0.05 × DEX (reproduces current mob speeds exactly). */
    public float deriveMoveSpeed() {
        return 1.0f + 0.05f * dex;
    }

    /**
     * max(0, 1 + modifier(str)) — display-only for passive mobs today;
     * slot for future hostiles. modifier = floor((str - 10) / 2).
     */
    public int deriveMeleeDamage() {
        return Math.max(0, 1 + getModifier(str));
    }

    /** Standard modifier: floor((score - 10) / 2). */
    public static int getModifier(int score) {
        return Math.floorDiv(score - 10, 2);
    }
}
