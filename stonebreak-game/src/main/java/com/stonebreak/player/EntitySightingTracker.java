package com.stonebreak.player;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.EntityManager;
import org.joml.Vector3f;

import java.util.List;

/**
 * Throttled sighter that records texture variants for nearby entities within the
 * player's FOV. Drives the "variants seen" dimension of the Entity Glossary.
 *
 * <p>Checks every 0.5s. For each glossary-type entity within 16 blocks and roughly
 * in front of the camera (dot ≥ 0.6, ~53° cone), records the variant. No raycast
 * occlusion — cheapest reasonable approximation.
 *
 * <p>Deliberately lives outside EntityManager.update() — that method also runs in
 * the server world (ServerLevel) and must not record sightings.
 */
public class EntitySightingTracker {

    private static final float CHECK_INTERVAL = 0.5f;
    private static final float MAX_DISTANCE_SQUARED = 256f; // 16 blocks
    private static final float FOV_DOT_THRESHOLD = 0.6f; // ~53° cone

    private float timer;

    public void update(float deltaTime) {
        timer += deltaTime;
        if (timer < CHECK_INTERVAL) return;
        timer = 0f;

        Player player = Game.getPlayer();
        if (player == null) return;

        Camera camera = player.getCamera();
        Vector3f feet = player.getPosition();
        Vector3f cameraPos = new Vector3f(feet.x, feet.y + com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET, feet.z);
        Vector3f front = camera.getFront();
        float frontLen = front.length();
        if (frontLen < 0.001f) return;
        frontLen = 1f / frontLen;

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        for (LivingEntity entity : entityManager.getLivingEntities()) {
            EntityType type = entity.getType();
            if (type != EntityType.COW && type != EntityType.SHEEP && type != EntityType.CHICKEN) continue;

            // Check distance
            Vector3f pos = entity.getPosition();
            float dx = pos.x - cameraPos.x;
            float dy = pos.y - cameraPos.y;
            float dz = pos.z - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE_SQUARED) continue;

            // Check FOV cone
            float dot = (dx * front.x + dy * front.y + dz * front.z) * frontLen;
            if (dot < FOV_DOT_THRESHOLD) continue;

            // Resolve variant name
            String variant = resolveVariant(entity, type);
            if (variant != null) {
                player.getEntityDiscoveries().recordVariantSeen(type, variant);
            }
        }
    }

    private static String resolveVariant(LivingEntity entity, EntityType type) {
        return switch (type) {
            case COW -> entity instanceof Cow cow ? cow.getTextureVariant() : "default";
            case SHEEP -> entity instanceof com.stonebreak.mobs.sheep.Sheep sheep
                ? sheep.getTextureVariant() : "default";
            case CHICKEN -> "default";
            default -> null;
        };
    }
}
