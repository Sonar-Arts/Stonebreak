package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.goose.Goose;

/**
 * Resolves an entity's current SBE animation-state name from its AI behaviour state, for any
 * SBE-driven mob. Server-safe (no OpenGL / rendering dependencies) so the authoritative side can
 * compute the state to replicate via {@code EntityAnimS2C}; the client maps it back onto a shadow
 * mob's AI through {@code MobStateMapping.behaviorState} (or {@code GooseStateMapping.behaviorState}
 * for the goose, whose flight states live outside the shared vocabulary).
 */
public final class EntityAnimResolver {

    private EntityAnimResolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The SBE animation-state name for {@code entity}, or {@code null} if the entity has no
     * AI-driven animation state (drops, projectiles, remote players, etc.).
     */
    public static String sbeState(Entity entity) {
        // The goose carries its own AI (no shared MobAI), so it resolves through its
        // own mapping — mirroring the dedicated goose branch in EntityRenderer.
        if (entity instanceof Goose goose) {
            return GooseStateMapping.sbeState(goose.getGooseAI().getCurrentState());
        }
        if (entity instanceof LivingEntity living && living.getAI() != null) {
            return MobStateMapping.sbeState(living.getAI().getCurrentState());
        }
        return null;
    }
}
