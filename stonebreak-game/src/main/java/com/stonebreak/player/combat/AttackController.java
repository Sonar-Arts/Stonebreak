package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.ATTACK_ANIMATION_DURATION;

/**
 * Owns the player attack swing: the animation state the renderer reads, and the
 * hit cooldown that gates melee attacks.
 *
 * <p>A weapon swing ({@link #tryBeginSwing}) starts both: the animation plays for
 * the weapon's swing time and no new swing can start until it has elapsed.
 * Block-breaking and interaction swings ({@link #startAttackAnimation}) are
 * cosmetic only — they play the short default animation and never block attacks.
 */
public class AttackController {

    private boolean attacking;
    private float animationTime;
    private float animationBaseSeconds = ATTACK_ANIMATION_DURATION;

    private boolean coolingDown;
    private float cooldownTime;
    private float cooldownBaseSeconds;

    // Scales swing duration; >1 = faster swings (e.g. Berserker Rage T2 attack-speed bonus).
    private float animationSpeedMultiplier = 1.0f;

    public void update(float deltaTime) {
        if (coolingDown) {
            cooldownTime += deltaTime;
            if (cooldownTime >= cooldownBaseSeconds / animationSpeedMultiplier) {
                coolingDown = false;
                cooldownTime = 0.0f;
            }
        }
        if (!attacking) return;
        animationTime += deltaTime;
        if (animationTime >= animationDuration()) {
            attacking = false;
            animationTime = 0.0f;
        }
    }

    /** Sets the swing-speed multiplier (1.0 = normal, >1.0 = faster). Clamped to a sane minimum. */
    public void setAnimationSpeedMultiplier(float multiplier) {
        this.animationSpeedMultiplier = Math.max(0.1f, multiplier);
    }

    private float animationDuration() {
        return animationBaseSeconds / animationSpeedMultiplier;
    }

    /** True when a new weapon swing may start. */
    public boolean isReady() {
        return !coolingDown;
    }

    /**
     * Starts a weapon swing lasting {@code swingSeconds} (before the speed
     * multiplier) if the previous one has finished; restarts the animation so it
     * matches the swing. Returns {@code false}, changing nothing, while cooling down.
     */
    public boolean tryBeginSwing(float swingSeconds) {
        if (coolingDown) {
            return false;
        }
        coolingDown = true;
        cooldownTime = 0.0f;
        cooldownBaseSeconds = Math.max(0.01f, swingSeconds);
        attacking = true;
        animationTime = 0.0f;
        animationBaseSeconds = cooldownBaseSeconds;
        return true;
    }

    /** Plays the default cosmetic swing (interactions) unless a swing is already playing. */
    public void startAttackAnimation() {
        if (!attacking) {
            attacking = true;
            animationTime = 0.0f;
            animationBaseSeconds = ATTACK_ANIMATION_DURATION;
        }
    }

    /** Plays the default cosmetic swing for a block-break tick unless a swing is already playing. */
    public void beginAttackForBreak() {
        startAttackAnimation();
    }

    public boolean isAttacking() {
        return attacking;
    }

    public float getAnimationProgress() {
        if (!attacking) return 0.0f;
        float progress = Math.min(animationTime / animationDuration(), 1.0f);
        return (float) (1.0 - Math.pow(1.0 - progress, 2.0));
    }

    public float getRawAnimationProgress() {
        if (!attacking) return 0.0f;
        return Math.min(animationTime / animationDuration(), 1.0f);
    }

    public void reset() {
        attacking = false;
        animationTime = 0.0f;
        animationBaseSeconds = ATTACK_ANIMATION_DURATION;
        coolingDown = false;
        cooldownTime = 0.0f;
    }
}
