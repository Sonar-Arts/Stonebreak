package com.stonebreak.player;

import com.stonebreak.config.Settings;
import com.stonebreak.mobs.sbe.EntityAttachments;
import com.stonebreak.mobs.sbe.SbeEntityLoader;

import java.util.List;

/** Independent, settings-persisted hair and clothing choices for the local player. */
public final class PlayerLooks {
    public static final String HAT_SOCKET = "Hatzone";
    public static final String HAIR_SOCKET = "hair";
    public static final String NO_HAT_ID = "NONE";
    public static final String NO_HAIR_ID = "NONE";

    /** A cosmetic and its host socket; the None option has no asset or socket. */
    public record CosmeticOption(String id, String displayName, String resourcePath, String socket) {}

    public static final List<CosmeticOption> HAT_OPTIONS = List.of(
            new CosmeticOption(NO_HAT_ID, "No Hat", null, null),
            new CosmeticOption("TOP_HAT", "Top Hat", "/sbe/Clothing/SB_Tophat.sbe", HAT_SOCKET));

    public static final List<CosmeticOption> HAIR_OPTIONS = List.of(
            new CosmeticOption(NO_HAIR_ID, "No Hair", null, null),
            new CosmeticOption("MALE_HAIR_1", "Male Hair 1", "/sbe/PlayerCustomize/SB_MHair1.sbe", HAIR_SOCKET),
            new CosmeticOption("MALE_HAIR_2", "Male Hair 2", "/sbe/PlayerCustomize/SB_MHair2.sbe", HAIR_SOCKET));

    private PlayerLooks() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String getSelectedHatId() {
        return optionFor(Settings.getInstance().getSelectedHat()).id();
    }

    public static String getSelectedHairId() {
        return hairOptionFor(Settings.getInstance().getSelectedHair()).id();
    }

    public static void selectHat(String hatId) {
        Settings settings = Settings.getInstance();
        settings.setSelectedHat(optionFor(hatId).id());
        settings.saveSettings();
        applyHat(EntityAttachments.LOCAL_PLAYER, settings.getSelectedHat());
    }

    public static void selectHair(String hairId) {
        Settings settings = Settings.getInstance();
        settings.setSelectedHair(hairOptionFor(hairId).id());
        settings.saveSettings();
        applyHair(EntityAttachments.LOCAL_PLAYER, settings.getSelectedHair());
    }

    /** Restores both choices at startup, also populating the character preview. */
    public static void applySelectedAppearance() {
        Settings settings = Settings.getInstance();
        applyHat(EntityAttachments.LOCAL_PLAYER, settings.getSelectedHat());
        applyHair(EntityAttachments.LOCAL_PLAYER, settings.getSelectedHair());
    }

    static void applyHat(Object entityKey, String id) {
        applyOption(entityKey, HAT_SOCKET, optionFor(id));
    }

    static void applyHair(Object entityKey, String id) {
        applyOption(entityKey, HAIR_SOCKET, hairOptionFor(id));
    }

    /** Clears only this slot, including when its asset cannot be loaded. */
    private static void applyOption(Object entityKey, String socket, CosmeticOption option) {
        EntityAttachments.detach(entityKey, socket);
        if (option.resourcePath() == null) return;
        try {
            var asset = SbeEntityLoader.loadAttachableResource(option.resourcePath());
            EntityAttachments.attach(entityKey, socket, asset);
        } catch (Exception e) {
            System.err.println("Failed to load cosmetic asset " + option.resourcePath() + ": " + e.getMessage());
        }
    }

    public static CosmeticOption optionFor(String id) {
        return findOption(HAT_OPTIONS, id);
    }

    public static CosmeticOption hairOptionFor(String id) {
        return findOption(HAIR_OPTIONS, id);
    }

    private static CosmeticOption findOption(List<CosmeticOption> options, String id) {
        return options.stream().filter(option -> option.id().equalsIgnoreCase(id))
                .findFirst().orElse(options.getFirst());
    }
}
