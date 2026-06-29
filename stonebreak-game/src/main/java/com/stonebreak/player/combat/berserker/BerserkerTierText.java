package com.stonebreak.player.combat.berserker;

import com.stonebreak.player.combat.RageTier;

/**
 * Live, tier-aware one-line summaries of what each Berserker ability does <em>right now</em>,
 * for HUD display alongside the Rage indicator. The static {@code ClassAbility} description
 * (Character Sheet) covers all four tiers; this is the dynamic counterpart that tells the
 * player exactly what they'd get if they cast at their current Rage tier.
 */
public final class BerserkerTierText {

    private BerserkerTierText() {}

    public static String rampageBonus(RageTier tier) {
        return switch (tier) {
            case NONE -> "Rampage: short charge, moderate damage";
            case T1   -> "Rampage: extended charge range";
            case T2   -> "Rampage: extended range + burning trail";
            case T3   -> "Rampage: full-width cleave — stagger & knockback";
        };
    }

    public static String skullCrusherBonus(RageTier tier) {
        return switch (tier) {
            case NONE -> "Skull Crusher: heavy single-target slam";
            case T1   -> "Skull Crusher: slam + radiating shockwave";
            case T2   -> "Skull Crusher: shockwave that also stuns";
            case T3   -> "Skull Crusher: leaves an Armor Break crater";
        };
    }
}
