package com.stonebreak.items.held;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.format.sbo.SBOFormat;
import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.gameplay.SboGameplayCodec;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Held-object grips as data (#458): every drawable held kind has a default grip in {@code held/held_grips.json};
 * an object's SBO may override it with a {@code held} record ({@link SboGameplay.HeldData}, edited in the SBO
 * editor's Stonebreak "Held" section). No grip constants live in code.
 */
public final class HeldGrips {

    private static final Logger logger = LoggerFactory.getLogger(HeldGrips.class);

    /** The defaults file (game resources). */
    public static final String DEFAULTS_RESOURCE = "/held/held_grips.json";
    /** Its {@code format} value. */
    public static final String FORMAT = "stonebreak.heldGrips";

    private static volatile Map<HeldKind, HeldGrip> defaults;
    /** Per-object override cache; {@code Optional.empty()} = the object has no {@code held} record. */
    private static final Map<Item, Optional<HeldGrip>> OVERRIDES = new ConcurrentHashMap<>();

    private HeldGrips() {
    }

    /** The grip {@code item} is held with; null when it is not drawable in a hand. */
    public static HeldGrip of(Item item) {
        HeldKind kind = HeldKind.of(item);
        if (kind == HeldKind.NONE) return null;
        return OVERRIDES.computeIfAbsent(item, HeldGrips::lookupOverride).orElseGet(() -> defaultFor(kind));
    }

    /** The default grip of {@code kind} (throws for {@link HeldKind#NONE}). */
    public static HeldGrip defaultFor(HeldKind kind) {
        HeldGrip grip = defaults().get(kind);
        if (grip == null) throw new IllegalArgumentException("no default grip for " + kind);
        return grip;
    }

    /** The SBO {@code held} record of {@code item}, if its manifest carries one. */
    public static Optional<SboGameplay.HeldData> heldData(Item item) {
        SBOFormat.Document manifest = manifestOf(item);
        return manifest == null ? Optional.empty() : Optional.ofNullable(SboGameplay.of(manifest).held());
    }

    /** Whether {@code type}'s SBO declares it a mining tool or a weapon (held like a handle: {@link HeldKind#TOOL}). */
    static boolean isToolOrWeapon(ItemType type) {
        try {
            SBOFormat.Document manifest = manifestOf(type);
            if (manifest == null) return false;
            SboGameplay g = SboGameplay.of(manifest);
            return g.hasTool() || g.hasWeapon();
        } catch (RuntimeException ex) {
            logger.warn("Failed to read the tool/weapon data of {}: {}", type, ex.getMessage());
            return false;
        }
    }

    private static Optional<HeldGrip> lookupOverride(Item item) {
        try {
            return heldData(item).map(HeldGrip::of);
        } catch (RuntimeException ex) {
            logger.warn("Failed to read the held grip of {}: {}", item, ex.getMessage());
            return Optional.empty();
        }
    }

    private static SBOFormat.Document manifestOf(Item item) {
        if (item instanceof ItemType type) {
            String objectId = ItemType.objectIdFor(type);
            if (objectId == null) return null;
            return ItemRegistry.getInstance().get(objectId)
                    .map(e -> e.sboData() != null ? e.sboData().manifest() : null).orElse(null);
        }
        if (item instanceof BlockType block) {
            return BlockRegistry.getInstance().getById(block.getId())
                    .map(e -> e.sboData() != null ? e.sboData().manifest() : null).orElse(null);
        }
        return null;
    }

    private static Map<HeldKind, HeldGrip> defaults() {
        Map<HeldKind, HeldGrip> d = defaults;
        if (d == null) {
            synchronized (HeldGrips.class) {
                d = defaults;
                if (d == null) {
                    try (InputStream in = HeldGrips.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
                        if (in == null) throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing");
                        d = parseDefaults(new ObjectMapper().readTree(in));
                    } catch (IOException e) {
                        throw new UncheckedIOException(DEFAULTS_RESOURCE + " is unreadable", e);
                    }
                    defaults = d;
                }
            }
        }
        return d;
    }

    /**
     * The defaults document: {@code {"format": "stonebreak.heldGrips", "version": 1, "kinds": {"block": held,
     * "tool": held, "item": held, "model": held}}}, every drawable kind present. Throws when malformed.
     */
    static Map<HeldKind, HeldGrip> parseDefaults(JsonNode root) {
        if (root == null || !FORMAT.equals(root.path("format").asText())) {
            throw new IllegalStateException(DEFAULTS_RESOURCE + ": format must be " + FORMAT);
        }
        if (root.path("version").asInt(0) != 1) {
            throw new IllegalStateException(DEFAULTS_RESOURCE + ": unsupported version " + root.path("version"));
        }
        JsonNode kinds = root.path("kinds");
        Map<HeldKind, HeldGrip> out = new EnumMap<>(HeldKind.class);
        for (HeldKind kind : HeldKind.values()) {
            if (kind == HeldKind.NONE) continue;
            JsonNode entry = kinds.get(kind.name().toLowerCase(Locale.ROOT));
            if (entry == null) throw new IllegalStateException(DEFAULTS_RESOURCE + ": no grip for " + kind);
            try {
                out.put(kind, HeldGrip.of(SboGameplayCodec.heldOf(entry)));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + ": " + kind + ": " + ex.getMessage(), ex);
            }
        }
        return Map.copyOf(out);
    }
}
