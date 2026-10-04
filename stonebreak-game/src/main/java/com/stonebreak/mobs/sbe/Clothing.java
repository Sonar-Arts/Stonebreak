package com.stonebreak.mobs.sbe;

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

    /**
     * Puts {@code resourcePath}'s {@code variant} on the entity's socket, replacing whatever was
     * there. A null path just clears the socket, and so does an asset that fails to load — a
     * missing hat must never leave the previous one on.
     */
    public static void wear(Object entityKey, String socket, String resourcePath, String variant) {
        EntityAttachments.detach(entityKey, socket);
        if (resourcePath == null) {
            return;
        }
        try {
            SbeEntityAsset asset = SbeEntityLoader.loadAttachableResource(resourcePath);
            EntityAttachments.attach(entityKey, socket, new AttachmentVisual.Model(asset,
                    variant != null ? variant : SbeEntityAsset.DEFAULT_VARIANT));
        } catch (Exception e) {
            System.err.println("Failed to load cosmetic asset " + resourcePath + ": " + e.getMessage());
        }
    }
}
