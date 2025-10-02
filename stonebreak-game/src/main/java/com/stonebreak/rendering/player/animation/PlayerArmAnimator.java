package com.stonebreak.rendering.player.animation;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;

/**
 * Handles all animation logic for the player's arm, including walking, idle sway, 
 * breathing, and attack animations.
 */
public class PlayerArmAnimator {
    
    private static final float WALK_CYCLE_SPEED = 6.0f;
    private static final float IDLE_SWAY_X_SPEED = 1.2f;
    private static final float IDLE_SWAY_Y_SPEED = 1.5f;
    private static final float BREATHING_SPEED = 2.0f;
    
    private static final float WALK_SWAY_Y_AMPLITUDE = 0.02f;
    private static final float WALK_SWAY_Z_AMPLITUDE = 0.01f;
    private static final float IDLE_SWAY_X_AMPLITUDE = 0.003f;
    private static final float IDLE_SWAY_Y_AMPLITUDE = 0.002f;
    private static final float BREATHING_Y_AMPLITUDE = 0.008f;
    
    private static final float WALKING_THRESHOLD = 0.5f;
    
    /**
     * Applies all appropriate animations to the arm transformation matrix.
     */
    public void applyAnimations(Matrix4f armTransform, Player player) {
        applyAnimations(armTransform, player, null);
    }

    /**
     * Applies all appropriate animations to the arm transformation matrix with item context.
     */
    public void applyAnimations(Matrix4f armTransform, Player player, com.stonebreak.items.ItemStack heldItem) {
        float totalTime = Game.getInstance().getTotalTimeElapsed();
        boolean isWalking = isPlayerWalking(player);

        // Apply base positioning
        applyBasePosition(armTransform);

        // Apply movement-based animations
        if (isWalking) {
            applyWalkingAnimation(armTransform, totalTime);
        } else {
            applyIdleAnimation(armTransform, totalTime);
        }

        // Apply breathing animation (always active)
        applyBreathingAnimation(armTransform, totalTime);

        // Apply base rotations for Minecraft-style positioning
        applyBaseRotations(armTransform);

        // Apply attack animation if attacking
        if (player.isAttacking()) {
            applyAttackAnimation(armTransform, player.getAttackAnimationProgress(), heldItem);
        }
    }
    
    /**
     * Determines if the player is currently walking based on velocity.
     */
    private boolean isPlayerWalking(Player player) {
        Vector3f velocity = player.getVelocity();
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        return horizontalSpeed > WALKING_THRESHOLD;
    }
    
    /**
     * Applies the base arm position in Minecraft style.
     */
    private void applyBasePosition(Matrix4f armTransform) {
        float baseX = 0.56f;  // Right side positioning like Minecraft
        float baseY = -0.48f; // Slightly lower position for natural look
        float baseZ = -0.65f; // Closer to camera for better visibility
        
        armTransform.translate(baseX, baseY, baseZ);
    }
    
    /**
     * Applies walking animation with arm swaying.
     */
    private void applyWalkingAnimation(Matrix4f armTransform, float totalTime) {
        float walkCycleTime = totalTime * WALK_CYCLE_SPEED;
        
        // Primary walking swing motion (up and down)
        float walkSwayY = (float) Math.sin(walkCycleTime) * WALK_SWAY_Y_AMPLITUDE;
        
        // Secondary walking motion (slight forward/back)
        float walkSwayZ = (float) Math.cos(walkCycleTime) * WALK_SWAY_Z_AMPLITUDE;
        
        armTransform.translate(0.0f, walkSwayY, walkSwayZ);
    }
    
    /**
     * Applies gentle idle sway animation when standing still.
     */
    private void applyIdleAnimation(Matrix4f armTransform, float totalTime) {
        float swayX = (float) Math.sin(totalTime * IDLE_SWAY_X_SPEED) * IDLE_SWAY_X_AMPLITUDE;
        float swayY = (float) Math.cos(totalTime * IDLE_SWAY_Y_SPEED) * IDLE_SWAY_Y_AMPLITUDE;
        
        armTransform.translate(swayX, swayY, 0.0f);
    }
    
    /**
     * Applies breathing-like movement that's always active.
     */
    private void applyBreathingAnimation(Matrix4f armTransform, float totalTime) {
        float breatheY = (float) Math.sin(totalTime * BREATHING_SPEED) * BREATHING_Y_AMPLITUDE;
        armTransform.translate(0.0f, breatheY, 0.0f);
    }
    
    /**
     * Applies base rotations for Minecraft-style arm positioning.
     */
    private void applyBaseRotations(Matrix4f armTransform) {
        armTransform.rotate((float) Math.toRadians(-10.0f), 0.0f, 1.0f, 0.0f); // Slight inward rotation
        armTransform.rotate((float) Math.toRadians(5.0f), 1.0f, 0.0f, 0.0f);   // Slight downward tilt
    }
    
    /**
     * Applies appropriate attack animation based on held item type.
     */
    private void applyAttackAnimation(Matrix4f armTransform, float attackProgress, com.stonebreak.items.ItemStack heldItem) {
        // Determine animation type based on held item
        if (heldItem != null && !heldItem.isEmpty()) {
            if (heldItem.isPlaceable()) {
                // Use old diagonal swing for blocks
                applyBlockAttackAnimation(armTransform, attackProgress);
            } else {
                // Use new TotalMiner-style overhead swing for items (tools, materials)
                applyItemAttackAnimation(armTransform, attackProgress);
            }
        } else {
            // Default to block animation when no item is held
            applyBlockAttackAnimation(armTransform, attackProgress);
        }
    }

    /**
     * Applies original diagonal swing animation for blocks.
     */
    private void applyBlockAttackAnimation(Matrix4f armTransform, float attackProgress) {
        float progress = 1.0f - attackProgress; // Reverse the progress

        // Original diagonal swing motion towards center of screen
        float swingAngle = (float) (Math.sin(progress * Math.PI) * 45.0f);
        float diagonalAngle = (float) (Math.sin(progress * Math.PI) * 30.0f);
        float swingLift = (float) (Math.sin(progress * Math.PI * 0.5f) * 0.08f);

        // Apply original diagonal swing rotation
        armTransform.rotate((float) Math.toRadians(-swingAngle * 0.7f), 1.0f, 0.0f, 0.0f);
        armTransform.rotate((float) Math.toRadians(-diagonalAngle), 0.0f, 1.0f, 0.0f);
        armTransform.rotate((float) Math.toRadians(swingAngle * 0.2f), 0.0f, 0.0f, 1.0f);

        // Translate towards center of screen during swing
        armTransform.translate(progress * -0.1f, swingLift, progress * -0.05f);
    }

    /**
     * Applies smooth, gentle swing animation for items (tools, materials).
     * Less violent and more fluid than the original overhead swing.
     */
    private void applyItemAttackAnimation(Matrix4f armTransform, float attackProgress) {
        float progress = 1.0f - attackProgress; // Reverse the progress for natural swing

        // Smooth swing with gentle arc motion
        // Use smoother easing functions for more fluid motion
        float smoothProgress = (float) (1.0f - Math.cos(progress * Math.PI)) * 0.5f; // Smooth ease-in-out

        // Forward swing motion - starts high and swings forward/down
        float windupAngle = smoothProgress * 35.0f; // Positive angle to start high/back
        float swingAngle = (float) (Math.sin(progress * Math.PI) * -65.0f); // Negative angle to swing forward/down

        // Enhanced motion for large outward swing
        float verticalMotion = (float) (Math.sin(progress * Math.PI) * 0.2f); // Increased for more dramatic motion
        float forwardMotion = (float) (Math.sin(progress * Math.PI) * 0.25f); // Increased for more forward reach
        float outwardMotion = (float) (Math.sin(progress * Math.PI) * 0.3f); // New outward translation

        // Apply smooth rotation (X-axis for vertical swing)
        armTransform.rotate((float) Math.toRadians(windupAngle + swingAngle), 1.0f, 0.0f, 0.0f);

        // No Y-axis rotation - outward motion achieved purely through translation

        // Apply smooth translation with large outward swing motion (negative X for outward)
        armTransform.translate(-outwardMotion, verticalMotion, forwardMotion);

        // Minimal twist for subtle realism (Z-axis)
        float swingTwist = (float) (Math.sin(progress * Math.PI) * 3.0f); // Reduced from 8.0f to 3.0f
        armTransform.rotate((float) Math.toRadians(swingTwist), 0.0f, 0.0f, 1.0f);
    }
    
    /**
     * Applies positioning and scaling adjustments for item rendering.
     */
    public void applyItemTransform(Matrix4f armTransform) {
        armTransform.scale(0.4f); // Larger scale for better visibility
        armTransform.translate(-0.3f, 0.15f, 0.3f); // Adjust position for item in hand
        
        // Apply Minecraft-style item rotation
        armTransform.rotate((float) Math.toRadians(20.0f), 1.0f, 0.0f, 0.0f);
        armTransform.rotate((float) Math.toRadians(-30.0f), 0.0f, 1.0f, 0.0f);
        armTransform.rotate((float) Math.toRadians(10.0f), 0.0f, 0.0f, 1.0f);
    }
}