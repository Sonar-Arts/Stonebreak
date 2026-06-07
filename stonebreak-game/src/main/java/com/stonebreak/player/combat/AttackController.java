package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.ATTACK_ANIMATION_DURATION;

/**
 * Owns the player attack-swing animation state. Triggered by block-break attempts and
 * by explicit startAttackAnimation() calls; advances per-frame until the animation
 * duration elapses. Exposes raw and eased progress for the renderer.
 */
public class AttackController {

    private boolean attacking;
    private float animationTime;

    // Scales swing duration; >1 = faster swings (e.g. Berserker Rage T2 attack-speed bonus).
    private float animationSpeedMultiplier = 1.0f;

    public void update(float deltaTime) {
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
        return ATTACK_ANIMATION_DURATION / animationSpeedMultiplier;
    }

    public void startAttackAnimation() {
        if (!attacking) {
            attacking = true;
            animationTime = 0.0f;
        }
    }

    /** Force-starts/restarts the animation regardless of current state (used when breaking a block). */
    public void beginAttackForBreak() {
        if (!attacking) {
            attacking = true;
            animationTime = 0.0f;
        }
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
    }
}
