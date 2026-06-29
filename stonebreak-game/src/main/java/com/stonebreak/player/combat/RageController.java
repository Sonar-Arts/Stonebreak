package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.RAGE_COMBAT_TIMEOUT;
import static com.stonebreak.player.PlayerConstants.RAGE_DECAY_PER_SECOND;
import static com.stonebreak.player.PlayerConstants.RAGE_GAIN_PER_HIT_DEALT;
import static com.stonebreak.player.PlayerConstants.RAGE_GAIN_PER_HIT_RECEIVED;
import static com.stonebreak.player.PlayerConstants.RAGE_MAX;
import static com.stonebreak.player.PlayerConstants.RAGE_T1_THRESHOLD;
import static com.stonebreak.player.PlayerConstants.RAGE_T2_THRESHOLD;
import static com.stonebreak.player.PlayerConstants.RAGE_T3_THRESHOLD;

/**
 * Tracks the Berserker's Rage resource. Rage builds from dealing and receiving melee
 * hits, escalates through three discrete thresholds (T1/T2/T3), and decays rapidly once
 * combat has been idle for {@link com.stonebreak.player.PlayerConstants#RAGE_COMBAT_TIMEOUT}.
 */
public class RageController {

    private static final float EPSILON = 0.01f;
    private static final float[] THRESHOLDS = { RAGE_T1_THRESHOLD, RAGE_T2_THRESHOLD, RAGE_T3_THRESHOLD };

    private float rage;
    private float timeSinceCombat;

    public void onMeleeHitDealt() {
        gainRage(RAGE_GAIN_PER_HIT_DEALT);
    }

    public void onHitReceived() {
        gainRage(RAGE_GAIN_PER_HIT_RECEIVED);
    }

    private void gainRage(float amount) {
        rage = Math.min(RAGE_MAX, rage + amount);
        timeSinceCombat = 0f;
    }

    public void update(float deltaTime) {
        timeSinceCombat += deltaTime;
        if (timeSinceCombat >= RAGE_COMBAT_TIMEOUT) {
            rage = Math.max(0f, rage - RAGE_DECAY_PER_SECOND * deltaTime);
        }
    }

    /** Returns the current discrete Rage tier (T0/NONE through T3). */
    public RageTier getTier() {
        if (rage >= RAGE_T3_THRESHOLD) return RageTier.T3;
        if (rage >= RAGE_T2_THRESHOLD) return RageTier.T2;
        if (rage >= RAGE_T1_THRESHOLD) return RageTier.T1;
        return RageTier.NONE;
    }

    /**
     * Consumes one Rage threshold to pay for an ability cast (T3 → T2 → T1 → T0).
     * Returns the tier that was active <em>before</em> consumption — abilities scale
     * off this value, per the Berserker design. No-op at T0 (still castable, no bonus).
     */
    public RageTier consumeThresholdForCast() {
        RageTier tierAtCast = getTier();
        if (tierAtCast != RageTier.NONE) {
            float thresholdFloor = THRESHOLDS[tierAtCast.ordinal() - 1];
            rage = Math.min(rage, thresholdFloor - EPSILON);
        }
        return tierAtCast;
    }

    public float getRage() { return rage; }
    public float getMaxRage() { return RAGE_MAX; }
}
