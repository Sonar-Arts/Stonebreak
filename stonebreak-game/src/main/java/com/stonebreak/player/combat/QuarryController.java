package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.RANGER_PREY_LOW_HP_FRACTION;
import static com.stonebreak.player.PlayerConstants.RANGER_STUDY_DECAY_INTERVAL;
import static com.stonebreak.player.PlayerConstants.RANGER_STUDY_DECAY_TIMEOUT;
import static com.stonebreak.player.PlayerConstants.RANGER_STUDY_MAX_STACKS;

import com.stonebreak.mobs.entities.LivingEntity;

/**
 * Tracks the Ranger's Quarry mark and Study stacks. The first hit on an enemy marks it
 * as the Quarry (only one at a time — marking a new target replaces the old); subsequent
 * hits and Snare trap triggers build discrete Study stacks up to
 * {@link com.stonebreak.player.PlayerConstants#RANGER_STUDY_MAX_STACKS}. Stacks decay
 * stepwise once the Quarry has not been studied for
 * {@link com.stonebreak.player.PlayerConstants#RANGER_STUDY_DECAY_TIMEOUT} seconds; a
 * decay tick at zero stacks clears the mark entirely.
 */
public class QuarryController {

    private LivingEntity quarry;
    private int studyStacks;
    private float timeSinceStudy;

    /**
     * Registers a player hit on {@code target}. The first hit on a non-Quarry target marks
     * it (replacing any previous Quarry, at zero stacks); hits on the current Quarry build
     * one Study stack each. Either way the decay clock resets.
     */
    public void onPlayerHit(LivingEntity target) {
        if (target == null || !target.isAlive()) return;
        if (target != quarry) {
            quarry = target;
            studyStacks = 0;
        } else {
            studyStacks = Math.min(RANGER_STUDY_MAX_STACKS, studyStacks + 1);
        }
        timeSinceStudy = 0f;
    }

    /**
     * Adds {@code count} Study stacks to {@code target} at once (Snare trap trigger),
     * marking it as the Quarry first if it isn't already. Resets the decay clock.
     */
    public void addStacks(LivingEntity target, int count) {
        if (target == null || !target.isAlive()) return;
        if (target != quarry) {
            quarry = target;
            studyStacks = 0;
        }
        studyStacks = Math.min(RANGER_STUDY_MAX_STACKS, studyStacks + count);
        timeSinceStudy = 0f;
    }

    /** Advances the decay clock and clears the mark if the Quarry has died or despawned. */
    public void update(float deltaTime) {
        if (quarry == null) return;
        if (!quarry.isAlive()) {
            reset();
            return;
        }
        timeSinceStudy += deltaTime;
        while (quarry != null && timeSinceStudy >= RANGER_STUDY_DECAY_TIMEOUT) {
            timeSinceStudy -= RANGER_STUDY_DECAY_INTERVAL;
            if (studyStacks > 0) {
                studyStacks--;
            } else {
                reset();
            }
        }
    }

    /** Clears the Quarry mark and all Study stacks (death, despawn, world reload). */
    public void reset() {
        quarry = null;
        studyStacks = 0;
        timeSinceStudy = 0f;
    }

    /** True at full Study stacks — Marked Prey (through-terrain visibility, execute effects). */
    public boolean isMarkedPrey() {
        return quarry != null && studyStacks >= RANGER_STUDY_MAX_STACKS;
    }

    /** True while the Quarry's health fraction is below the Marked Prey low-HP threshold. */
    public boolean isPreyLowHp() {
        return quarry != null && quarry.getMaxHealth() > 0f
            && quarry.getHealth() / quarry.getMaxHealth() < RANGER_PREY_LOW_HP_FRACTION;
    }

    /** 0..1 progress toward the next decay tick's grace window elapsing (1 = decaying). */
    public float getDecayProgress() {
        if (quarry == null) return 0f;
        return Math.min(1f, timeSinceStudy / RANGER_STUDY_DECAY_TIMEOUT);
    }

    public LivingEntity getQuarry() { return quarry; }
    public int getStudyStacks() { return studyStacks; }
}
