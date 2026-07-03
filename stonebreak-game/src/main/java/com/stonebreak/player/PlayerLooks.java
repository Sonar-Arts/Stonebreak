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

    public static final String NO_HAT_ID = "NONE";

    /** One selectable hat: settings id, UI label, classpath asset (null = bare head). */
    public record HatOption(String id, String displayName, String resourcePath) {}

    public static final List<HatOption> HAT_OPTIONS = List.of(
            new HatOption(NO_HAT_ID, "None", null),
            new HatOption("TOP_HAT", "Top Hat", "/sbe/Clothing/SB_Tophat.sbe"));

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
     * Mounts the settings-selected hat on the local player's hat socket (or
     * clears the socket for {@value #NO_HAT_ID}). Called once at startup and
     * whenever the selection changes; a hat whose asset fails to load falls
     * back to bare head rather than leaving a stale attachment.
     */
    public static void applySelectedHat() {
        HatOption option = optionFor(Settings.getInstance().getSelectedHat());
        if (option.resourcePath() == null) {
            EntityAttachments.detach(EntityAttachments.LOCAL_PLAYER, HAT_SOCKET);
            return;
        }
        try {
            SbeEntityAsset asset = SbeEntityLoader.loadAttachableResource(option.resourcePath());
            EntityAttachments.attach(EntityAttachments.LOCAL_PLAYER, HAT_SOCKET, asset);
        } catch (Exception e) {
            System.err.println("Failed to load hat asset " + option.resourcePath()
                    + ": " + e.getMessage());
            EntityAttachments.detach(EntityAttachments.LOCAL_PLAYER, HAT_SOCKET);
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
