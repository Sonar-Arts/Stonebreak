package com.stonebreak.mobs.entities;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The extent of a mob's rendered model in its rest pose, in model space (unscaled, origin as
 * authored) — what a hitbox must cover for a hit to land wherever the player sees the mob.
 *
 * <p>Measured from the same SBE geometry the renderer draws, so a model change moves the hitbox
 * with it. Only indexed vertices count, as for {@link SbeModelGeometry#restMinY()}.
 */
public record ModelBounds(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {

    /** Measured once per model variant; an absent value means no model is registered. */
    private static final Map<String, Optional<ModelBounds>> CACHE = new ConcurrentHashMap<>();

    /** The bounds of the registered SBE model {@code objectId} in {@code variant}, or null without one. */
    public static ModelBounds of(String objectId, String variant) {
        if (objectId == null) {
            return null;
        }
        return CACHE.computeIfAbsent(objectId + '#' + variant, key -> {
            SbeEntityAsset asset = SbeEntityRegistry.get(objectId);
            SbeModelGeometry geometry = asset == null ? null : asset.geometryFor(variant);
            return Optional.ofNullable(geometry == null ? null : of(geometry));
        }).orElse(null);
    }

    /** The bounds of {@code geometry}'s rest pose, or null when it draws nothing. */
    public static ModelBounds of(SbeModelGeometry geometry) {
        float[] v = geometry.vertices();
        int[] indices = geometry.indices();
        if (indices.length == 0) {
            return null;
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int index : indices) {
            float x = v[index * 3], y = v[index * 3 + 1], z = v[index * 3 + 2];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return new ModelBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    /**
     * The world box around the model as the renderer places it: scaled, turned by
     * {@code yawDegrees} about Y (as {@code SbePoseSolver} turns it), and with its feet on the
     * ground plane {@code feetY} (the ground-anchoring contract of {@code SbeRenderSupport}).
     * Turning a box makes it no longer axis-aligned, so this is the tightest axis-aligned box
     * around the turned one: exact at the four cardinal headings, a little loose between them.
     */
    public Entity.BoundingBox placed(float x, float feetY, float z, float yawDegrees,
                                     float scaleX, float scaleY, float scaleZ) {
        double yaw = Math.toRadians(yawDegrees);
        float cos = (float) Math.cos(yaw);
        float sin = (float) Math.sin(yaw);
        float lowX = Float.POSITIVE_INFINITY, lowZ = Float.POSITIVE_INFINITY;
        float highX = Float.NEGATIVE_INFINITY, highZ = Float.NEGATIVE_INFINITY;
        for (float mx : new float[]{minX * scaleX, maxX * scaleX}) {
            for (float mz : new float[]{minZ * scaleZ, maxZ * scaleZ}) {
                float wx = cos * mx + sin * mz; // JOML rotateY
                float wz = -sin * mx + cos * mz;
                lowX = Math.min(lowX, wx); highX = Math.max(highX, wx);
                lowZ = Math.min(lowZ, wz); highZ = Math.max(highZ, wz);
            }
        }
        return new Entity.BoundingBox(x + lowX, feetY, z + lowZ,
                x + highX, feetY + (maxY - minY) * scaleY, z + highZ);
    }
}
