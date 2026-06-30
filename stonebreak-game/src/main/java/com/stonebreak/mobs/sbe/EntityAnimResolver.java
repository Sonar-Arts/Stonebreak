package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.chicken.Chicken;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.sheep.Sheep;

/**
 * Resolves an entity's current SBE animation-state name from its AI behaviour state, for any
 * SBE-driven mob. Server-safe (no OpenGL / rendering dependencies) so the authoritative side can
 * compute the state to replicate via {@code EntityAnimS2C}; the client maps it back onto a shadow
 * mob's AI through the per-mob {@code *StateMapping.behaviorState}.
 */
public final class EntityAnimResolver {

    private EntityAnimResolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The SBE animation-state name for {@code entity}, or {@code null} if the entity has no
     * AI-driven animation state (drops, projectiles, etc.).
     */
    public static String sbeState(Entity entity) {
        if (entity instanceof Cow cow) {
            return cow.getAI() != null ? CowStateMapping.sbeState(cow.getAI().getCurrentState()) : null;
        }
        if (entity instanceof Sheep sheep) {
            return sheep.getAI() != null ? SheepStateMapping.sbeState(sheep.getAI().getCurrentState()) : null;
        }
        if (entity instanceof Chicken chicken) {
            return chicken.getAI() != null ? ChickenStateMapping.sbeState(chicken.getAI().getCurrentState()) : null;
        }
        return null;
    }
}
