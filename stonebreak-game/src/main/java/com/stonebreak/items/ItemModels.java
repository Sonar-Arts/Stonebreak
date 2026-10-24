package com.stonebreak.items;

import org.joml.Matrix4f;

/**
 * Items drawn as a 3D entity model (an SBE) instead of their sprite: the inventory icon and the
 * first-person hand show the model itself, shrunk to fit. Each entry says which model and how to
 * pose it in each place.
 */
public final class ItemModels {

    /**
     * @param sbeObjectId  the SBE asset (entity registry object id)
     * @param iconCenterY  model height the icon camera aims at
     * @param iconRadius   half the icon camera's ortho span, in model units (the model's reach)
     * @param handScale    model scale in the hand's item space
     * @param handYawDeg   model yaw in the hand
     */
    public record ItemModel(String sbeObjectId, float iconCenterY, float iconRadius,
                            float handScale, float handYawDeg) {

        /** Model transform in the first-person hand's item space (centred, shrunk, turned). */
        public Matrix4f handTransform(Matrix4f dest) {
            return dest.identity()
                    .rotateY((float) Math.toRadians(handYawDeg))
                    .scale(handScale)
                    .translate(0f, -iconCenterY, 0f);
        }
    }

    /** The dinghy: about 3.8 blocks oar tip to oar tip, so the icon frames ±2. */
    private static final ItemModel DINGHY = new ItemModel("stonebreak:dinghy", 0.35f, 2.0f, 0.13f, 90f);

    private ItemModels() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The model {@code item} is drawn as, or null when it draws its sprite. */
    public static ItemModel of(Item item) {
        return item == ItemType.DINGHY ? DINGHY : null;
    }
}
