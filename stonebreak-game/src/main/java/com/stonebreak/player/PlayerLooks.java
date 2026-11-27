package com.stonebreak.player;

import com.stonebreak.config.Settings;
import com.stonebreak.mobs.sbe.BodyMounts;
import com.stonebreak.mobs.sbe.Clothing;
import com.stonebreak.player.appearance.AppearanceMounts;
import com.stonebreak.player.appearance.CosmeticCatalog;
import com.stonebreak.player.appearance.PlayerAppearance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The local side of the player's look (#459): the options the Looks tab lists (from the {@link CosmeticCatalog}), the
 * look this machine prefers ({@code Settings.preferredLook}: what character creation starts from and what a new
 * character on any server begins with) and the preview body that shows it. The authoritative look of a player in a
 * world is the one the server keeps with the player; choosing an option while in a world also asks the server to
 * change it ({@link #setChangeSink}).
 */
public final class PlayerLooks {
    /** Equipment slot names (catalog slots; also the mount names on a body). */
    public static final String HAT_MOUNT = "hat";
    public static final String HAIR_MOUNT = "hair";
    public static final String ACCESSORY_MOUNT = "accessory";

    public static final String HAT_SOCKET = Clothing.HAT_SOCKET;
    public static final String HAIR_SOCKET = "Hair";
    public static final String ACCESSORY_SOCKET = Clothing.EARRING_SOCKET;
    public static final String NO_HAT_ID = CosmeticCatalog.NONE;
    public static final String NO_HAIR_ID = CosmeticCatalog.NONE;
    public static final String NO_ACCESSORY_ID = CosmeticCatalog.NONE;

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

    public static final List<CosmeticOption> HAT_OPTIONS = options(HAT_MOUNT);
    public static final List<CosmeticOption> HAIR_OPTIONS = options(HAIR_MOUNT);
    public static final List<CosmeticOption> ACCESSORY_OPTIONS = options(ACCESSORY_MOUNT);

    /** The UI preview's body (character creation, inventory): the look being chosen. */
    private static final BodyMounts PREVIEW = new BodyMounts();

    /** Where an in-world change goes (the session's {@code PlayerAppearanceC2S}); null outside a world. */
    private static volatile Consumer<PlayerAppearance> changeSink;

    private PlayerLooks() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** "None", then the slot's items, from the catalog. */
    private static List<CosmeticOption> options(String slot) {
        CosmeticCatalog catalog = CosmeticCatalog.get();
        CosmeticCatalog.Slot s = catalog.slot(slot);
        List<CosmeticOption> out = new ArrayList<>();
        out.add(new CosmeticOption(CosmeticCatalog.NONE, s != null ? s.noneName() : "None", null, null));
        for (CosmeticCatalog.Item item : catalog.itemsFor(slot)) {
            out.add(new CosmeticOption(item.id(), item.name(), item.asset(), catalog.socketOf(item), item.variant()));
        }
        return List.copyOf(out);
    }

    /** The mounts the player previews draw. */
    public static BodyMounts previewMounts() {
        return PREVIEW;
    }

    /** This machine's preferred look (the catalog's defaults when none was chosen or it cannot be read). */
    public static PlayerAppearance preferred() {
        String json = Settings.getInstance().getPreferredLook();
        if (json != null) {
            try {
                return PlayerAppearance.readJson(json).withDefaults(CosmeticCatalog.get());
            } catch (RuntimeException e) {
                System.err.println("[LOOK] Ignoring an unreadable preferred look: " + e.getMessage());
            }
        }
        return PlayerAppearance.defaults();
    }

    /**
     * The look the Looks tab shows and edits: in a world, the one the server keeps for us (it may differ from this
     * machine's preference, e.g. an older save); otherwise the preferred look.
     */
    public static PlayerAppearance current() {
        Consumer<PlayerAppearance> sink = changeSink;
        if (sink != null) {
            com.stonebreak.network.client.ClientWorldView view = com.stonebreak.network.MultiplayerSession.getClient();
            if (view != null) {
                com.stonebreak.player.appearance.KnownLooks.Entry own =
                        com.stonebreak.player.appearance.KnownLooks.get(view.localPlayerId());
                if (own != null) return own.look();
            }
        }
        return preferred();
    }

    /** A character-creation blob with the preferred look added as its {@code appearance} object (#459). */
    public static byte[] withLook(byte[] creationJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(creationJson);
            if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) return creationJson;
            obj.set("appearance", mapper.readTree(preferred().toJson()));
            return mapper.writeValueAsBytes(obj);
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("[LOOK] Could not add the look to the character: " + e.getMessage());
            return creationJson;
        }
    }

    /** Sets the in-world change channel (null when leaving the world). */
    public static void setChangeSink(Consumer<PlayerAppearance> sink) {
        changeSink = sink;
    }

    public static String getSelectedHatId() {
        return optionFor(current().item(HAT_MOUNT)).id();
    }

    public static String getSelectedHairId() {
        return hairOptionFor(current().item(HAIR_MOUNT)).id();
    }

    public static String getSelectedAccessoryId() {
        return accessoryOptionFor(current().item(ACCESSORY_MOUNT)).id();
    }

    public static void selectAccessory(String accessoryId) {
        choose(ACCESSORY_MOUNT, accessoryOptionFor(accessoryId).id());
    }

    public static void selectHat(String hatId) {
        choose(HAT_MOUNT, optionFor(hatId).id());
    }

    public static void selectHair(String hairId) {
        choose(HAIR_MOUNT, hairOptionFor(hairId).id());
    }

    /**
     * Shows the choice on the preview, keeps it as this machine's preferred look and, in a world, asks the server for
     * it (the server's answer dresses our body; a refused change sends the preview back).
     */
    private static void choose(String slot, String itemId) {
        PlayerAppearance look = current().withItem(slot, itemId);
        Settings settings = Settings.getInstance();
        settings.setPreferredLook(look.toJson());
        settings.saveSettings();
        AppearanceMounts.apply(look, PREVIEW);
        Consumer<PlayerAppearance> sink = changeSink;
        if (sink != null) sink.accept(look);
    }

    /** Shows the preferred look on the preview body (startup, after the look changed elsewhere). */
    public static void applySelectedAppearance() {
        AppearanceMounts.apply(preferred(), PREVIEW);
    }

    /** Puts the preferred look on {@code mounts} (a body before the server told it its own record). */
    public static void applyTo(BodyMounts mounts) {
        AppearanceMounts.apply(preferred(), mounts);
    }

    static void applyHat(BodyMounts mounts, String id) {
        applyOption(mounts, HAT_MOUNT, optionFor(id));
    }

    static void applyHair(BodyMounts mounts, String id) {
        applyOption(mounts, HAIR_MOUNT, hairOptionFor(id));
    }

    static void applyAccessory(BodyMounts mounts, String id) {
        applyOption(mounts, ACCESSORY_MOUNT, accessoryOptionFor(id));
    }

    /** Clears only this slot, including when its asset cannot be loaded. */
    private static void applyOption(BodyMounts mounts, String mount, CosmeticOption option) {
        Clothing.wear(mounts, mount, option.socket(), option.resourcePath(), option.variant());
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
        String key = id == null ? null : id.toUpperCase(Locale.ROOT);
        return options.stream().filter(option -> option.id().equalsIgnoreCase(key))
                .findFirst().orElse(options.getFirst());
    }
}
