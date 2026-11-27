package com.stonebreak.player.appearance;

import com.openmason.engine.character.Look;
import com.stonebreak.player.PlayerCharacter;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A player's look (#459): what they wear and the colours they chose, saved with the player on the server (like
 * health) and sent to every client. The wire and save forms are the engine's appearance record ({@link Look}, omlook
 * binary / JSON, OpenMason #162) over the slots of the {@link CosmeticCatalog}: equipment slots hold an item id (or
 * {@link CosmeticCatalog#NONE}), tints a colour, groups a visibility flag. Immutable; the codec is the only part that
 * touches native code.
 *
 * @param items  equipment slot -> item id (upper case) or {@code NONE}
 * @param tints  tint slot -> {@code 0xRRGGBB}
 * @param groups group slot -> visible
 */
public record PlayerAppearance(Map<String, String> items, Map<String, Integer> tints, Map<String, Boolean> groups) {

    /** Most bytes a binary look may take on the wire / in a save. */
    public static final int MAX_BYTES = 1024;

    public PlayerAppearance {
        items = Collections.unmodifiableMap(new TreeMap<>(items));
        tints = Collections.unmodifiableMap(new TreeMap<>(tints));
        groups = Collections.unmodifiableMap(new TreeMap<>(groups));
    }

    /** Every slot of {@code catalog} at its default: empty equipment, default tints, groups visible. */
    public static PlayerAppearance defaults(CosmeticCatalog catalog) {
        Map<String, String> items = new TreeMap<>();
        for (CosmeticCatalog.Slot s : catalog.slots()) items.put(s.name(), CosmeticCatalog.NONE);
        Map<String, Integer> tints = new TreeMap<>();
        for (CosmeticCatalog.Tint t : catalog.tints()) tints.put(t.name(), t.defaultRgb());
        Map<String, Boolean> groups = new TreeMap<>();
        for (String g : catalog.groups()) groups.put(g, true);
        return new PlayerAppearance(items, tints, groups);
    }

    /** {@link #defaults(CosmeticCatalog)} of the game's catalog. */
    public static PlayerAppearance defaults() {
        return defaults(CosmeticCatalog.get());
    }

    /** The item in {@code slot}, {@code NONE} when empty or not set. */
    public String item(String slot) {
        return items.getOrDefault(slot, CosmeticCatalog.NONE);
    }

    /** This look with {@code slot} holding {@code itemId} ({@code null} / {@code NONE} = empty). */
    public PlayerAppearance withItem(String slot, String itemId) {
        Map<String, String> next = new TreeMap<>(items);
        next.put(slot, itemId == null ? CosmeticCatalog.NONE : itemId.toUpperCase(Locale.ROOT));
        return new PlayerAppearance(next, tints, groups);
    }

    /** This look with tint {@code name} set to {@code rgb} ({@code 0xRRGGBB}). */
    public PlayerAppearance withTint(String name, int rgb) {
        Map<String, Integer> next = new TreeMap<>(tints);
        next.put(name, rgb & 0xFFFFFF);
        return new PlayerAppearance(items, next, groups);
    }

    /** Every catalog slot this look does not set, at its default (unknown slots are kept). */
    public PlayerAppearance withDefaults(CosmeticCatalog catalog) {
        PlayerAppearance d = defaults(catalog);
        Map<String, String> i = new TreeMap<>(d.items);
        i.putAll(items);
        Map<String, Integer> t = new TreeMap<>(d.tints);
        t.putAll(tints);
        Map<String, Boolean> g = new TreeMap<>(d.groups);
        g.putAll(groups);
        return new PlayerAppearance(i, t, g);
    }

    /**
     * Why {@code catalog} refuses this look, or null when every slot is one of its slots with a value of the right
     * kind: an equipment slot holds {@code NONE} or an item made for that slot.
     */
    public String problem(CosmeticCatalog catalog) {
        for (Map.Entry<String, String> e : items.entrySet()) {
            if (catalog.slot(e.getKey()) == null) return "unknown equipment slot " + e.getKey();
            if (e.getValue().equals(CosmeticCatalog.NONE)) continue;
            CosmeticCatalog.Item item = catalog.item(e.getValue());
            if (item == null) return "unknown item " + e.getValue();
            if (!item.slot().equals(e.getKey())) return "item " + e.getValue() + " does not fit slot " + e.getKey();
        }
        for (String t : tints.keySet()) {
            if (catalog.tint(t) == null) return "unknown tint " + t;
        }
        for (String g : groups.keySet()) {
            if (!catalog.groups().contains(g)) return "unknown group " + g;
        }
        return null;
    }

    // ---- codec: the engine look record ------------------------------------------------------------------

    /** The binary omlook record. */
    public byte[] toBinary() {
        try (Look look = toLook()) {
            return look.writeBinary();
        }
    }

    /** The JSON omlook record (saves, Settings). */
    public String toJson() {
        try (Look look = toLook()) {
            return new String(look.writeJson(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads a binary or JSON omlook record. A slot of an unexpected kind (a colour in an equipment slot) makes the
     * record invalid: {@link IllegalArgumentException}, as does a malformed or newer record.
     */
    public static PlayerAppearance read(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES * 4) {
            throw new IllegalArgumentException("no look record");
        }
        try (Look look = Look.read(bytes)) {
            Map<String, String> items = new TreeMap<>();
            Map<String, Integer> tints = new TreeMap<>();
            Map<String, Boolean> groups = new TreeMap<>();
            for (Look.Slot s : look.slots()) {
                switch (s.type()) {
                    case Look.STRING -> items.put(s.name(), s.text().isEmpty() ? CosmeticCatalog.NONE
                            : s.text().toUpperCase(Locale.ROOT));
                    case Look.COLOR -> tints.put(s.name(), rgb(s.color()));
                    case Look.BOOL -> groups.put(s.name(), s.flag());
                    default -> throw new IllegalArgumentException("slot " + s.name() + " has an unsupported type");
                }
            }
            return new PlayerAppearance(items, tints, groups);
        }
    }

    /** {@link #read(byte[])} of JSON text. */
    public static PlayerAppearance readJson(String json) {
        return read(json.getBytes(StandardCharsets.UTF_8));
    }

    private Look toLook() {
        Look look = Look.create();
        try {
            look.setCharacter(PlayerCharacter.ASSET_ID);
            items.forEach(look::setString);
            tints.forEach((name, rgb) -> look.setColor(name, ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f,
                    (rgb & 0xFF) / 255f));
            groups.forEach(look::setBool);
            return look;
        } catch (RuntimeException e) {
            look.close();
            throw e;
        }
    }

    private static int rgb(float[] c) {
        int r = Math.round(Math.clamp(c[0], 0f, 1f) * 255f);
        int g = Math.round(Math.clamp(c[1], 0f, 1f) * 255f);
        int b = Math.round(Math.clamp(c[2], 0f, 1f) * 255f);
        return (r << 16) | (g << 8) | b;
    }
}
