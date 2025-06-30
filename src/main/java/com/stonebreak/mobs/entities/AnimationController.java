package com.stonebreak.mobs.entities;

import org.joml.Vector3f;
import com.stonebreak.mobs.cow.Cow;

import java.util.HashMap;
import java.util.Map;

/**
 * Controls entity animations and handles smooth transitions between animation states.
 * Provides a centralized system for managing entity animations with blending support.
 */
public class AnimationController {
    // Animation state tracking
    private final Entity entity;
    private final Map<String, AnimationState> activeAnimations;
    private String currentAnimationName;
    private float transitionTime;
    private float transitionDuration;
    
    // Animation timing
    private float totalAnimationTime;
    
    /**
     * Creates a new animation controller for the specified entity.
     */
    public AnimationController(Entity entity) {
        this.entity = entity;
        this.activeAnimations = new HashMap<>();
        this.currentAnimationName = "idle";
        this.transitionTime = 0.0f;
        this.transitionDuration = 0.5f; // Default transition duration
        this.totalAnimationTime = 0.0f;
    }
    
    /**
     * Updates all active animations.
     */
    public void updateAnimations(float deltaTime) {
        totalAnimationTime += deltaTime;
        
        // Update transition time
        if (isTransitioning()) {
            transitionTime += deltaTime;
        }
        
        // Update active animation states
        for (AnimationState state : activeAnimations.values()) {
            state.update(deltaTime);
        }
        
        // Apply entity-specific animations
        if (entity instanceof Cow cow) {
            updateCowAnimations(cow, deltaTime);
        }
    }
    
    /**
     * Plays an animation with optional looping.
     */
    public void playAnimation(String animationName, boolean loop) {
        playAnimation(animationName, loop, 0.5f);
    }
    
    /**
     * Plays an animation with custom transition duration.
     */
    public void playAnimation(String animationName, boolean loop, float transitionDuration) {
        if (!animationName.equals(currentAnimationName)) {
            // Start transition to new animation
            currentAnimationName = animationName;
            this.transitionDuration = transitionDuration;
            transitionTime = 0.0f;
            
            // Create or update animation state
            AnimationState state = activeAnimations.get(animationName);
            if (state == null) {
                state = new AnimationState(animationName, loop);
                activeAnimations.put(animationName, state);
            }
            state.setLooping(loop);
            state.reset();
        }
    }
    
    /**
     * Stops an animation.
     */
    public void stopAnimation(String animationName) {
        activeAnimations.remove(animationName);
        
        if (animationName.equals(currentAnimationName)) {
            // Switch to default idle animation
            playAnimation("idle", true);
        }
    }
    
    /**
     * Blends between two animations.
     */
    public void blendAnimations(String fromAnimation, String toAnimation, float blendFactor) {
        // This would implement animation blending in a full system
        // For now, we'll use simple transition logic
        if (blendFactor >= 0.5f) {
            playAnimation(toAnimation, true);
        } else {
            playAnimation(fromAnimation, true);
        }
    }
    
    /**
     * Updates cow-specific animations.
     */
    public void updateCowAnimations(Cow cow, float deltaTime) {
        // Determine current animation based on cow's behavior state
        String targetAnimation = getCowAnimationName(cow);
        
        // Play appropriate animation
        playAnimation(targetAnimation, true);
        
        // Apply animation effects
        applyCowAnimationEffects(cow, targetAnimation, totalAnimationTime);
    }
    
    /**
     * Gets the animation name for a cow's current state.
     */
    private String getCowAnimationName(Cow cow) {
        return switch (cow.getAI().getCurrentState()) {
            case WANDERING -> "walking";
            case GRAZING -> "grazing";
            case IDLE -> "idle";
        };
    }
    
    /**
     * Applies visual effects based on cow animation.
     */
    private void applyCowAnimationEffects(Cow cow, String animationName, float time) {
        switch (animationName) {
            case "walking" -> {
                // Create subtle bouncing effect during walking
                float walkBounce = (float) Math.sin(time * 6.0f) * 0.02f;
                Vector3f currentPos = cow.getPosition();
                currentPos.y += walkBounce;
            }
            case "grazing" -> {
                // Lower the cow slightly when grazing
                Vector3f grazingPos = cow.getPosition();
                grazingPos.y -= 0.05f;
            }
            case "idle" -> {
                // Subtle breathing effect
                float breathingEffect = (float) Math.sin(time * 2.0f) * 0.01f;
                Vector3f idlePos = cow.getPosition();
                idlePos.y += breathingEffect;
            }
        }
    }
    
    /**
     * Checks if currently transitioning between animations.
     */
    public boolean isTransitioning() {
        return transitionTime < transitionDuration;
    }
    
    /**
     * Gets the current transition progress (0.0 to 1.0).
     */
    public float getTransitionProgress() {
        return Math.min(transitionTime / transitionDuration, 1.0f);
    }
    
    /**
     * Gets the current animation name.
     */
    public String getCurrentAnimationName() {
        return currentAnimationName;
    }
    
    /**
     * Gets the total animation time.
     */
    public float getTotalAnimationTime() {
        return totalAnimationTime;
    }
    
    /**
     * Represents the state of a single animation.
     */
    private static class AnimationState {
        private boolean looping;
        private float time;
        private final float duration;
        private boolean playing;
        
        public AnimationState(String name, boolean looping) {
            this.looping = looping;
            this.time = 0.0f;
            this.duration = 1.0f; // Default duration
            this.playing = true;
        }
        
        public void update(float deltaTime) {
            if (playing) {
                time += deltaTime;
                
                if (looping && time >= duration) {
                    time = time % duration; // Loop the animation
                } else if (!looping && time >= duration) {
                    playing = false; // Stop non-looping animation
                }
            }
        }
        
        public void reset() {
            time = 0.0f;
            playing = true;
        }
        
        public void setLooping(boolean looping) {
            this.looping = looping;
        }
    }
}