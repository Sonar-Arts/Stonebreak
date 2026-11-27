package com.stonebreak.mobs.sbe;

import com.openmason.engine.entity.model.EntityAsset;

import java.util.List;

/**
 * The wearable cosmetics and the sockets they hang on, shared by every host that wears them — the
 * player's look options and goblin loadouts draw on this one catalog.
 *
 * <p>One asset fits every host because each host's socket carries the fit (position, rotation,
 * scale): a host only needs a socket with the right name.
 */
public final class Clothing {

    /** Top of the head: hats and bandanas. */
    public static final String HAT_SOCKET = "Hatzone";
    /** Side of the head, at the ear. */
    public static final String EARRING_SOCKET = "Earring";

    public static final String TOP_HAT = "/sbe/Clothing/SB_Tophat.sbe";
    public static final String PIRATE_HAT = "/sbe/Clothing/SB_PirateHat.sbe";
    /** One bandana model; its colours are the asset's appearance variants. */
    public static final String BANDANA = "/sbe/Clothing/SB_Bandana.sbe";
    public static final String GOLD_EARRING = "/sbe/Clothing/SB_GoldEarring.sbe";

    /** Variant names authored in {@link #BANDANA}. */
    public static final List<String> BANDANA_COLORS = List.of("Red", "Blue", "Green", "Black");

    private Clothing() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The attach point an attachable model may author (OMO attachment point of this name): where it is worn. */
    public static final String MOUNT_POINT = "mount";

    /**
     * Puts {@code resourcePath}'s {@code variant} on {@code socket} as mount {@code mountName}, replacing whatever
     * that mount held. A null path just clears it, and so does an asset that fails to load — a missing hat must
     * never leave the previous one on. The model is worn by its own {@link #MOUNT_POINT} when it authors one, else
     * by its origin.
     */
    public static void wear(BodyMounts mounts, String mountName, String socket, String resourcePath, String variant) {
        mounts.remove(mountName);
        if (resourcePath == null || socket == null) {
            return;
        }
        try {
            EntityAsset asset = GameSbeAssets.loadAttachableResource(resourcePath);
            String v = variant != null ? variant : EntityAsset.DEFAULT_VARIANT;
            mounts.put(new BodyMounts.Mount(mountName, socket, new AttachmentVisual.Model(asset, v), null,
                    mountPoint(asset, v), null));
        } catch (Exception e) {
            System.err.println("Failed to load cosmetic asset " + resourcePath + ": " + e.getMessage());
        }
    }

    /** {@code wear} with the mount named after its socket (one item per socket: goblin loadouts). */
    public static void wear(BodyMounts mounts, String socket, String resourcePath, String variant) {
        wear(mounts, socket, socket, resourcePath, variant);
    }

    /** The model's own {@link #MOUNT_POINT} (rest model space), or null = its origin. */
    public static org.joml.Matrix4f mountPoint(EntityAsset asset, String variant) {
        com.openmason.engine.entity.model.EntityGeometry g = asset.geometryFor(variant);
        if (g == null) return null;
        for (com.openmason.engine.entity.model.EntityAttachmentPoint p : g.attachmentPoints()) {
            if (MOUNT_POINT.equalsIgnoreCase(p.name())) {
                return new org.joml.Matrix4f().translate(p.localPos())
                        .rotateXYZ((float) Math.toRadians(p.localRotDeg().x), (float) Math.toRadians(p.localRotDeg().y),
                                (float) Math.toRadians(p.localRotDeg().z))
                        .scale(p.localScale());
            }
        }
        return null;
    }
}
