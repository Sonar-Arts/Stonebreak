package com.stonebreak.crafting;

import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.items.Item;

import java.util.List;
import java.util.Objects;

/**
 * What a recipe slot, smelting input or fuel entry accepts: one specific object, or any member
 * of an {@link ItemDictionary} (written {@code "#stonebreak:wooden_planks"} in an SBO).
 */
public sealed interface Ingredient {

    /** True when {@code item} satisfies this ingredient. */
    boolean test(Item item);

    /** The items this ingredient accepts, in display order; never empty. */
    List<Item> displayItems();

    /** The item shown when one representative is needed. */
    default Item representative() {
        return displayItems().getFirst();
    }

    /** Exactly one object, compared by singleton identity. */
    record Single(Item item) implements Ingredient {
        public Single {
            Objects.requireNonNull(item, "item");
        }

        @Override
        public boolean test(Item candidate) {
            return candidate == item;
        }

        @Override
        public List<Item> displayItems() {
            return List.of(item);
        }
    }

    /** Any member of dictionary {@code id} (stored without the {@code #}). */
    record Dictionary(String id, List<Item> members) implements Ingredient {
        public Dictionary {
            Objects.requireNonNull(id, "id");
            members = List.copyOf(members);
            if (members.isEmpty()) {
                throw new IllegalArgumentException("dictionary '" + id + "' has no members");
            }
        }

        @Override
        public boolean test(Item candidate) {
            return candidate != null && members.contains(candidate);
        }

        @Override
        public List<Item> displayItems() {
            return members;
        }

        /** e.g. {@code "Wooden Planks"}. */
        public String displayName() {
            return ItemDictionary.displayName(id);
        }
    }

    /** True when an SBO ingredient string names a dictionary ({@code #id}). */
    static boolean isDictionaryRef(String ref) {
        return ref != null && ref.strip().startsWith(SboGameplay.DICTIONARY_PREFIX);
    }

    /**
     * Parses an SBO ingredient string: {@code "#id"} → {@link Dictionary}, anything else →
     * {@link Single} via {@link ItemResolver}.
     *
     * @return the ingredient, or {@code null} when the object or dictionary is unknown/empty
     */
    static Ingredient parse(String ref, ItemDictionary dictionary) {
        if (ref == null || ref.isBlank()) return null;
        String t = ref.strip();
        if (isDictionaryRef(t)) {
            String id = t.substring(SboGameplay.DICTIONARY_PREFIX.length()).strip();
            List<Item> members = dictionary.members(id);
            return members.isEmpty() ? null : new Dictionary(id, members);
        }
        Item item = ItemResolver.resolve(t);
        return item == null ? null : new Single(item);
    }
}
