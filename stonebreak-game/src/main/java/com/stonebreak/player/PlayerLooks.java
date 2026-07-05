package com.stonebreak.player;

import com.stonebreak.config.Settings;
import com.stonebreak.mobs.sbe.EntityAttachments;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;

import java.util.List;

/**
 * Cosmetic appearance options for the local player, driven by the Looks tab
 * of the character creation screen. Each hat option is an attachable asset
 * mounted on the player model's {@value #HAT_SOCKET} socket via
 * {@link EntityAttachments}; the selection persists in {@link Settings} so it
 * survives restarts. Visual-only — hats are neither saved with worlds nor
 * replicated to other players (attachment system v1).
 */
public final class PlayerLooks {

    /** Socket authored on SB_Player.sbe's head part that hats mount to. */
    public static final String HAT_SOCKET = "Hatzone";

    /** Upright socket on SB_Player.sbe's head crown that hair mounts to. */
    public static final String HAIR_SOCKET = "hair";

    public static final String NO_HAT_ID = "NONE";

    /**
     * One selectable head cosmetic: settings id, UI label, classpath asset
     * (null = bare head), and the head socket it mounts on (null when none).
     * Hats and hair use different sockets so each sits with its own authored
     * orientation.
     */
    public record HatOption(String id, String displayName, String resourcePath, String socket) {}

    public static final List<HatOption> HAT_OPTIONS = List.of(
            new HatOption(NO_HAT_ID, "None", null, null),
            new HatOption("TOP_HAT", "Top Hat", "/sbe/Clothing/SB_Tophat.sbe", HAT_SOCKET),
            new HatOption("MALE_HAIR_1", "Male Hair 1", "/sbe/PlayerCustomize/SB_MHair1.sbe", HAIR_SOCKET));

    private PlayerLooks() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String getSelectedHatId() {
        return Settings.getInstance().getSelectedHat();
    }

    /** Selects a hat by id, persists it, and applies it to the local player. */
    public static void selectHat(String hatId) {
        Settings settings = Settings.getInstance();
        settings.setSelectedHat(optionFor(hatId).id());
        settings.saveSettings();
        applySelectedHat();
    }

    /**
     * Mounts the settings-selected cosmetic on the local player. Selection is
     * single-choice, so every cosmetic socket is cleared first and then the
     * chosen asset is mounted on its own socket ({@value #HAT_SOCKET} for hats,
     * {@value #HAIR_SOCKET} for hair) — {@value #NO_HAT_ID} leaves the head
     * bare. Called once at startup and whenever the selection changes; an asset
     * that fails to load leaves the head bare rather than a stale attachment.
     */
    public static void applySelectedHat() {
        HatOption option = optionFor(Settings.getInstance().getSelectedHat());
        EntityAttachments.detach(EntityAttachments.LOCAL_PLAYER, HAT_SOCKET);
        EntityAttachments.detach(EntityAttachments.LOCAL_PLAYER, HAIR_SOCKET);
        if (option.resourcePath() == null || option.socket() == null) {
            return;
        }
        try {
            SbeEntityAsset asset = SbeEntityLoader.loadAttachableResource(option.resourcePath());
            EntityAttachments.attach(EntityAttachments.LOCAL_PLAYER, option.socket(), asset);
        } catch (Exception e) {
            System.err.println("Failed to load cosmetic asset " + option.resourcePath()
                    + ": " + e.getMessage());
        }
    }

    /** The option with the given id (case-insensitive); NONE when unknown. */
    public static HatOption optionFor(String hatId) {
        for (HatOption option : HAT_OPTIONS) {
            if (option.id().equalsIgnoreCase(hatId)) {
                return option;
            }
        }
        return HAT_OPTIONS.getFirst();
    }
}
