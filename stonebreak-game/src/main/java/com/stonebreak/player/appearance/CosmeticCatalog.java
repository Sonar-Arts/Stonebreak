package com.stonebreak.player.appearance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The player's appearance slots and the items they take (#459), read from {@code appearance/cosmetics.json} (format
 * {@code stonebreak.cosmetics} 1). GL-free: the server checks every look against it, the client lists the options and
 * puts the chosen items on bodies. No cosmetic constants live in code.
 */
public final class CosmeticCatalog {

    public static final String RESOURCE = "/appearance/cosmetics.json";
    public static final String FORMAT = "stonebreak.cosmetics";
    /** The item id an equipment slot holds when it is empty. */
    public static final String NONE = "NONE";

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** A tint slot and its default colour ({@code 0xRRGGBB}). */
    public record Tint(String name, int defaultRgb) {}

    /** An equipment slot: the socket its items hang on, and what "empty" is called in the UI. */
    public record Slot(String name, String socket, String noneName) {}

    /**
     * An item an equipment slot takes.
     *
     * @param socket its own socket, or null = the slot's
     * @param variant the asset's appearance variant, or null = its default
     */
    public record Item(String id, String slot, String name, String asset, String variant, String socket) {}

    private static volatile CosmeticCatalog shipped;

    private final List<Tint> tints;
    private final List<String> groups;
    private final List<Slot> slots;
    private final Map<String, Item> items;

    private CosmeticCatalog(List<Tint> tints, List<String> groups, List<Slot> slots, Map<String, Item> items) {
        this.tints = List.copyOf(tints);
        this.groups = List.copyOf(groups);
        this.slots = List.copyOf(slots);
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    /** The game's catalog (loaded once). */
    public static CosmeticCatalog get() {
        CosmeticCatalog c = shipped;
        if (c == null) {
            synchronized (CosmeticCatalog.class) {
                c = shipped;
                if (c == null) {
                    try (InputStream in = CosmeticCatalog.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new IllegalStateException(RESOURCE + " is missing");
                        c = shipped = parse(new ObjectMapper().readTree(in));
                    } catch (IOException e) {
                        throw new UncheckedIOException(RESOURCE + " is unreadable", e);
                    }
                }
            }
        }
        return c;
    }

    /** Parses a catalog document; throws {@link IllegalArgumentException} on anything malformed. */
    public static CosmeticCatalog parse(JsonNode root) {
        if (root == null || !FORMAT.equals(root.path("format").asText())) {
            throw new IllegalArgumentException("not a " + FORMAT + " document");
        }
        if (root.path("version").asInt(0) != 1) {
            throw new IllegalArgumentException("unsupported " + FORMAT + " version " + root.path("version"));
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        List<Tint> tints = new ArrayList<>();
        for (JsonNode t : root.path("tints")) {
            String name = required(t, "name");
            tints.add(new Tint(unique(names, name), parseColor(required(t, "default"))));
        }
        List<String> groups = new ArrayList<>();
        for (JsonNode g : root.path("groups")) {
            groups.add(unique(names, g.isTextual() ? g.asText() : required(g, "name")));
        }
        List<Slot> slots = new ArrayList<>();
        for (JsonNode s : root.path("slots")) {
            slots.add(new Slot(unique(names, required(s, "name")), required(s, "socket"),
                    s.path("none").asText("None")));
        }
        Map<String, Item> items = new LinkedHashMap<>();
        for (JsonNode i : root.path("items")) {
            String id = required(i, "id");
            if (id.equalsIgnoreCase(NONE)) throw new IllegalArgumentException("item id " + NONE + " is reserved");
            String slot = required(i, "slot");
            if (slots.stream().noneMatch(s -> s.name().equals(slot))) {
                throw new IllegalArgumentException("item " + id + " names unknown slot " + slot);
            }
            Item item = new Item(id, slot, i.path("name").asText(id), required(i, "asset"),
                    i.hasNonNull("variant") ? i.get("variant").asText() : null,
                    i.hasNonNull("socket") ? i.get("socket").asText() : null);
            if (items.put(id.toUpperCase(Locale.ROOT), item) != null) {
                throw new IllegalArgumentException("duplicate item " + id);
            }
        }
        return new CosmeticCatalog(tints, groups, slots, items);
    }

    private static String required(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IllegalArgumentException("missing " + field + " in " + n);
        }
        return v.asText().strip();
    }

    private static String unique(java.util.Set<String> names, String name) {
        if (!names.add(name)) throw new IllegalArgumentException("duplicate slot name " + name);
        return name;
    }

    /** {@code #rrggbb} to {@code 0xRRGGBB}. */
    public static int parseColor(String text) {
        if (text == null || !COLOR.matcher(text).matches()) {
            throw new IllegalArgumentException("a colour must be #rrggbb, got " + text);
        }
        return Integer.parseInt(text.substring(1), 16);
    }

    public List<Tint> tints() {
        return tints;
    }

    public List<String> groups() {
        return groups;
    }

    public List<Slot> slots() {
        return slots;
    }

    /** Every item, in file order. */
    public List<Item> items() {
        return List.copyOf(items.values());
    }

    /** The items {@code slot} takes, in file order. */
    public List<Item> itemsFor(String slot) {
        return items.values().stream().filter(i -> i.slot().equals(slot)).toList();
    }

    /** The item {@code id} (case-insensitive), or null ({@link #NONE} too). */
    public Item item(String id) {
        return id == null ? null : items.get(id.toUpperCase(Locale.ROOT));
    }

    public Slot slot(String name) {
        for (Slot s : slots) {
            if (s.name().equals(name)) return s;
        }
        return null;
    }

    public Tint tint(String name) {
        for (Tint t : tints) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    /** The socket {@code item} hangs on: its own, else its slot's. */
    public String socketOf(Item item) {
        if (item.socket() != null) return item.socket();
        Slot s = slot(item.slot());
        return s != null ? s.socket() : null;
    }
}
