package com.stonebreak.player;

import com.stonebreak.config.Settings;
import com.stonebreak.mobs.sbe.Clothing;
import com.stonebreak.mobs.sbe.EntityAttachments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Independent, settings-persisted hair, hat and accessory choices for the local player. */
public final class PlayerLooks {
    public static final String HAT_SOCKET = Clothing.HAT_SOCKET;
    public static final String HAIR_SOCKET = "hair";
    public static final String ACCESSORY_SOCKET = Clothing.EARRING_SOCKET;
    public static final String NO_HAT_ID = "NONE";
    public static final String NO_HAIR_ID = "NONE";
    public static final String NO_ACCESSORY_ID = "NONE";

    /**
     * A cosmetic, the appearance variant to wear it in, and its host socket; the None option has
     * no asset or socket.
     */
    public record CosmeticOption(String id, String displayName, String resourcePath, String socket,
                                 String variant) {
        public CosmeticOption(String id, String displayName, String resourcePath, String socket) {
            this(id, displayName, resourcePath, socket, null);
        }
    }

    public static final List<CosmeticOption> HAT_OPTIONS = hatOptions();

    public static final List<CosmeticOption> ACCESSORY_OPTIONS = List.of(
            new CosmeticOption(NO_ACCESSORY_ID, "No Accessory", null, null),
            new CosmeticOption("GOLD_EARRING", "Gold Earring", Clothing.GOLD_EARRING, ACCESSORY_SOCKET));

    public static final List<CosmeticOption> HAIR_OPTIONS = List.of(
            new CosmeticOption(NO_HAIR_ID, "No Hair", null, null),
            new CosmeticOption("MALE_HAIR_1", "Male Hair 1", "/sbe/PlayerCustomize/SB_MHair1.sbe", HAIR_SOCKET),
            new CosmeticOption("MALE_HAIR_2", "Male Hair 2", "/sbe/PlayerCustomize/SB_MHair2.sbe", HAIR_SOCKET));

    private PlayerLooks() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Hats, then one bandana option per authored colour. */
    private static List<CosmeticOption> hatOptions() {
        List<CosmeticOption> options = new ArrayList<>(List.of(
                new CosmeticOption(NO_HAT_ID, "No Hat", null, null),
                new CosmeticOption("TOP_HAT", "Top Hat", Clothing.TOP_HAT, HAT_SOCKET),
                new CosmeticOption("PIRATE_HAT", "Pirate Hat", Clothing.PIRATE_HAT, HAT_SOCKET)));
        for (String color : Clothing.BANDANA_COLORS) {
            options.add(new CosmeticOption("BANDANA_" + color.toUpperCase(Locale.ROOT),
                    color + " Bandana", Clothing.BANDANA, HAT_SOCKET, color));
        }
        return List.copyOf(options);
    }

    public static String getSelectedHatId() {
        return optionFor(Settings.getInstance().getSelectedHat()).id();
    }

    public static String getSelectedHairId() {
        return hairOptionFor(Settings.getInstance().getSelectedHair()).id();
    }

    public static String getSelectedAccessoryId() {
        return accessoryOptionFor(Settings.getInstance().getSelectedAccessory()).id();
    }

    public static void selectAccessory(String accessoryId) {
        Settings settings = Settings.getInstance();
        settings.setSelectedAccessory(accessoryOptionFor(accessoryId).id());
        settings.saveSettings();
        applyAccessory(EntityAttachments.LOCAL_PLAYER, settings.getSelectedAccessory());
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

    /** Restores every choice at startup, also populating the character preview. */
    public static void applySelectedAppearance() {
        Settings settings = Settings.getInstance();
        applyHat(EntityAttachments.LOCAL_PLAYER, settings.getSelectedHat());
        applyHair(EntityAttachments.LOCAL_PLAYER, settings.getSelectedHair());
        applyAccessory(EntityAttachments.LOCAL_PLAYER, settings.getSelectedAccessory());
    }

    static void applyAccessory(Object entityKey, String id) {
        applyOption(entityKey, ACCESSORY_SOCKET, accessoryOptionFor(id));
    }

    static void applyHat(Object entityKey, String id) {
        applyOption(entityKey, HAT_SOCKET, optionFor(id));
    }

    static void applyHair(Object entityKey, String id) {
        applyOption(entityKey, HAIR_SOCKET, hairOptionFor(id));
    }

    /** Clears only this slot, including when its asset cannot be loaded. */
    private static void applyOption(Object entityKey, String socket, CosmeticOption option) {
        Clothing.wear(entityKey, socket, option.resourcePath(), option.variant());
    }

    public static CosmeticOption optionFor(String id) {
        return findOption(HAT_OPTIONS, id);
    }

    public static CosmeticOption accessoryOptionFor(String id) {
        return findOption(ACCESSORY_OPTIONS, id);
    }

    public static CosmeticOption hairOptionFor(String id) {
        return findOption(HAIR_OPTIONS, id);
    }

    private static CosmeticOption findOption(List<CosmeticOption> options, String id) {
        return options.stream().filter(option -> option.id().equalsIgnoreCase(id))
                .findFirst().orElse(options.getFirst());
    }
}
