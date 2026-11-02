package com.stonebreak.crafting;

import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.items.Item;
import com.stonebreak.items.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item dictionaries ("ore dictionary"): named groups of interchangeable objects, such as
 * {@code stonebreak:wooden_planks}. Membership is declared on each member SBO
 * ({@code "dictionaries": [...]}), so a new plank joins every plank recipe by declaring the tag;
 * recipes, smelting inputs and fuel tables refer to a dictionary as {@code "#id"}.
 *
 * <p>Immutable once built. Member order follows registry order, so it is deterministic.
 */
public final class ItemDictionary {

    private static final Logger logger = LoggerFactory.getLogger(ItemDictionary.class);

    /** No dictionaries at all. */
    public static final ItemDictionary EMPTY = new ItemDictionary(Map.of());

    private final Map<String, List<Item>> members;

    private ItemDictionary(Map<String, List<Item>> members) {
        this.members = members;
    }

    /** Builds the dictionaries from every registered block and item SBO. */
    public static ItemDictionary fromRegistries() {
        Builder b = new Builder();
        for (BlockRegistry.BlockEntry entry : List.copyOf(BlockRegistry.getInstance().all())) {
            b.addFromSbo(entry.objectId(), entry.sboData());
        }
        // A snapshot: resolving an item may first initialise ItemType, which rescans the registry.
        for (ItemRegistry.ItemEntry entry : List.copyOf(ItemRegistry.getInstance().all())) {
            b.addFromSbo(entry.objectId(), entry.sboData());
        }
        ItemDictionary dict = b.build();
        logger.info("ItemDictionary: {} dictionar{} {}", dict.members.size(),
                dict.members.size() == 1 ? "y" : "ies", dict.members.keySet());
        return dict;
    }

    /** The members of {@code dictionaryId} (no {@code #}); empty when unknown. */
    public List<Item> members(String dictionaryId) {
        return members.getOrDefault(dictionaryId, List.of());
    }

    /** Every dictionary id, in first-seen order. */
    public List<String> ids() {
        return List.copyOf(members.keySet());
    }

    /** {@code stonebreak:wooden_planks} → {@code "Wooden Planks"}. */
    public static String displayName(String dictionaryId) {
        return SboGameplay.dictionaryDisplayName(dictionaryId);
    }

    /** Builds a dictionary by hand (tests, tools). */
    public static final class Builder {
        private final Map<String, List<Item>> members = new LinkedHashMap<>();

        public Builder add(String dictionaryId, Item item) {
            if (dictionaryId == null || dictionaryId.isBlank() || item == null) return this;
            List<Item> list = members.computeIfAbsent(dictionaryId.strip(), k -> new ArrayList<>());
            if (!list.contains(item)) list.add(item);
            return this;
        }

        void addFromSbo(String objectId, SBOParseResult sbo) {
            if (sbo == null) return;
            List<String> ids = SboGameplay.of(sbo.manifest()).dictionaries();
            if (ids.isEmpty()) return;
            Item item = ItemResolver.resolve(objectId);
            if (item == null) {
                logger.warn("Dictionary membership declared by unknown objectId {} — ignored", objectId);
                return;
            }
            ids.forEach(id -> add(id, item));
        }

        public ItemDictionary build() {
            Map<String, List<Item>> copy = new LinkedHashMap<>();
            members.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            return new ItemDictionary(Collections.unmodifiableMap(copy));
        }
    }
}
