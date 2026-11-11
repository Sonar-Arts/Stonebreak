package com.stonebreak.gameplay;

import com.openmason.engine.format.sbo.SBOFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Stonebreak's game data in an SBO (#383): the {@code "stonebreak:gameplay"} manifest extension
 * ({@link SboGameplayCodec}). Before SBO 1.11 these sections were top-level manifest fields
 * ({@code gameProperties}, {@code recipes}, {@code smeltingRecipes}, {@code fuel}, {@code drops},
 * {@code tool}, {@code weapon}); such files still read through the codec's legacy fields, keep their
 * layout on disk, and move to the extension the next time a Stonebreak tool saves them.
 *
 * <p>Every section is optional (null = absent). The rules that interpret the data live in the game:
 * {@code ToolMiningRules} (mining), {@code BlockDropTables} (drops), {@code WeaponRules},
 * {@code RecipeLoader} / {@code SmeltingRecipeLoader}.
 *
 * @param gameProperties  block/item properties (numeric id, hardness, atlas tile, stacking, material...)
 * @param recipes         shaped crafting recipes whose output is this SBO's object
 * @param smeltingRecipes furnace recipes whose output is this SBO's object
 * @param fuel            how long this SBO's own item burns as furnace fuel
 * @param drops           what breaking this block yields (present = authoritative, even empty)
 * @param tool            the item's mining-tool data
 * @param weapon          the item's melee-weapon data
 * @param dictionaries    ids of the item dictionaries this object belongs to (never null), e.g.
 *                        {@code stonebreak:wooden_planks}; recipes name one as {@code #id}
 * @param fuelTable       burn times for other objects / whole dictionaries (the furnace's)
 */
public record SboGameplay(
        GameProperties gameProperties,
        RecipeData recipes,
        SmeltingRecipeData smeltingRecipes,
        FuelData fuel,
        DropData drops,
        ToolData tool,
        WeaponData weapon,
        List<String> dictionaries,
        FuelTableData fuelTable,
        List<String> connectTags,
        MultipartData multipart
) {

    /** Normalises {@code dictionaries}: never null, no blanks, no duplicates, order kept. */
    public SboGameplay {
        dictionaries = normaliseDictionaries(dictionaries);
        connectTags = normaliseTags(connectTags);
    }

    /** The legacy seven-part constructor: no dictionaries, no fuel table, no shape data. */
    public SboGameplay(GameProperties gameProperties, RecipeData recipes, SmeltingRecipeData smeltingRecipes,
                       FuelData fuel, DropData drops, ToolData tool, WeaponData weapon) {
        this(gameProperties, recipes, smeltingRecipes, fuel, drops, tool, weapon, List.of(), null, List.of(), null);
    }

    /** Before shape data: dictionaries and a fuel table, no connect tags or multipart. */
    public SboGameplay(GameProperties gameProperties, RecipeData recipes, SmeltingRecipeData smeltingRecipes,
                       FuelData fuel, DropData drops, ToolData tool, WeaponData weapon,
                       List<String> dictionaries, FuelTableData fuelTable) {
        this(gameProperties, recipes, smeltingRecipes, fuel, drops, tool, weapon, dictionaries, fuelTable,
                List.of(), null);
    }

    private static List<String> normaliseTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        var out = new java.util.LinkedHashSet<String>();
        for (String tag : tags) {
            if (tag == null) continue;
            String t = tag.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }

    private static List<String> normaliseDictionaries(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var out = new java.util.LinkedHashSet<String>();
        for (String id : ids) {
            if (id == null) continue;
            String t = id.strip();
            if (t.startsWith(DICTIONARY_PREFIX)) t = t.substring(DICTIONARY_PREFIX.length()).strip();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }

    /**
     * Prefix that marks an ingredient reference (recipe slot, smelting input, fuel-table entry) as a
     * dictionary rather than one specific object: {@code "#stonebreak:wooden_planks"}.
     */
    public static final String DICTIONARY_PREFIX = "#";

    /** A dictionary id for people: {@code stonebreak:wooden_planks} (or {@code #...}) → {@code "Wooden Planks"}. */
    public static String dictionaryDisplayName(String dictionaryId) {
        String id = dictionaryId == null ? "" : dictionaryId.strip();
        if (id.startsWith(DICTIONARY_PREFIX)) id = id.substring(DICTIONARY_PREFIX.length());
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder();
        for (String word : path.split("[_\\s]+")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /** No game data. */
    public static final SboGameplay NONE = new SboGameplay(null, null, null, null, null, null, null);

    /** The game data of {@code manifest} (extension, else legacy fields); {@link #NONE} when it has none. */
    public static SboGameplay of(SBOFormat.Document manifest) {
        return manifest == null ? NONE : manifest.extension(SboGameplayCodec.INSTANCE).orElse(NONE);
    }

    /** {@code manifest} carrying this data in the extension (none at all = no extension). */
    public SBOFormat.Document applyTo(SBOFormat.Document manifest) {
        return manifest.withExtension(SboGameplayCodec.INSTANCE, isEmpty() ? null : this);
    }

    /** Sets this data on the parameters of a new SBO. */
    public void applyTo(SBOFormat.ExportParameters params) {
        params.setExtension(SboGameplayCodec.INSTANCE, isEmpty() ? null : this);
    }

    /** The game data an export is being given so far ({@link #NONE} when none). */
    public static SboGameplay of(SBOFormat.ExportParameters params) {
        return params.getExtension(SboGameplayCodec.INSTANCE).orElse(NONE);
    }

    public boolean isEmpty() {
        return gameProperties == null && recipes == null && smeltingRecipes == null && fuel == null
                && drops == null && tool == null && weapon == null && dictionaries.isEmpty() && fuelTable == null
                && connectTags.isEmpty() && multipart == null;
    }

    public SboGameplay withGameProperties(GameProperties v) {
        return new SboGameplay(v, recipes, smeltingRecipes, fuel, drops, tool, weapon, dictionaries,
                fuelTable, connectTags, multipart);
    }

    public SboGameplay withRecipes(RecipeData v) {
        return new SboGameplay(gameProperties, v, smeltingRecipes, fuel, drops, tool, weapon, dictionaries,
                fuelTable, connectTags, multipart);
    }

    public SboGameplay withSmeltingRecipes(SmeltingRecipeData v) {
        return new SboGameplay(gameProperties, recipes, v, fuel, drops, tool, weapon, dictionaries,
                fuelTable, connectTags, multipart);
    }

    public SboGameplay withFuel(FuelData v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, v, drops, tool, weapon, dictionaries,
                fuelTable, connectTags, multipart);
    }

    public SboGameplay withDrops(DropData v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, fuel, v, tool, weapon, dictionaries,
                fuelTable, connectTags, multipart);
    }

    public SboGameplay withTool(ToolData v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, fuel, drops, v, weapon, dictionaries,
                fuelTable, connectTags, multipart);
    }

    public SboGameplay withWeapon(WeaponData v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, fuel, drops, tool, v, dictionaries,
                fuelTable, connectTags, multipart);
    }

    /** Replaces the dictionaries this object belongs to (null/empty = none). */
    public SboGameplay withDictionaries(List<String> v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, fuel, drops, tool, weapon, v,
                fuelTable, connectTags, multipart);
    }

    public SboGameplay withFuelTable(FuelTableData v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, fuel, drops, tool, weapon, dictionaries,
                v, connectTags, multipart);
    }

    /** Replaces the tags neighbours' multipart conditions can test this block for. */
    public SboGameplay withConnectTags(List<String> v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, fuel, drops, tool, weapon, dictionaries,
                fuelTable, v, multipart);
    }

    /** Replaces the neighbour-driven shape (null/empty = none). */
    public SboGameplay withMultipart(MultipartData v) {
        return new SboGameplay(gameProperties, recipes, smeltingRecipes, fuel, drops, tool, weapon, dictionaries,
                fuelTable, connectTags, v == null || v.parts().isEmpty() ? null : v);
    }

    /** True when this SBO's shape depends on its neighbours. */
    public boolean hasMultipart() {
        return multipart != null && !multipart.parts().isEmpty();
    }

    /** True when this object is a member of at least one dictionary. */
    public boolean hasDictionaries() {
        return !dictionaries.isEmpty();
    }

    /** True when this SBO carries a fuel table (present but empty = no extra fuels). */
    public boolean hasFuelTable() {
        return fuelTable != null;
    }

    /** True when this SBO declares one or more crafting recipes. */
    public boolean hasRecipes() {
        return recipes != null && recipes.shaped() != null && !recipes.shaped().isEmpty();
    }

    /** True when this SBO declares one or more smelting recipes. */
    public boolean hasSmeltingRecipes() {
        return smeltingRecipes != null && smeltingRecipes.recipes() != null && !smeltingRecipes.recipes().isEmpty();
    }

    /** True when this SBO declares fuel data. */
    public boolean hasFuel() {
        return fuel != null && fuel.burnTicks() > 0;
    }

    /** True when this SBO carries a drop table (present but empty = drops nothing). */
    public boolean hasDrops() {
        return drops != null;
    }

    /** True when this SBO declares itself a mining tool. */
    public boolean hasTool() {
        return tool != null;
    }

    /** True when this SBO declares itself a weapon. */
    public boolean hasWeapon() {
        return weapon != null;
    }

    /**
     * Optional melee-weapon descriptor embedded in an item SBO manifest (1.10+).
     *
     * <p>The group and damage type are free-form, lowercased names so the
     * format stays game-agnostic; the game maps them onto its own enums.
     *
     * @param group        how the weapon is wielded ({@code one_handed},
     *                     {@code two_handed}, {@code ranged}, ...); never blank
     * @param damageType   kind of damage a hit deals ({@code slashing},
     *                     {@code piercing}, {@code blunt}, ...); never blank
     * @param damage       base damage per hit, {@code >= 0}
     * @param swingSeconds base seconds between hits, {@code > 0}
     */
    public record WeaponData(String group, String damageType, float damage, float swingSeconds) {

        public WeaponData {
            group = normalizeName(group, "group");
            damageType = normalizeName(damageType, "damageType");
            if (!Float.isFinite(damage) || damage < 0f) {
                throw new IllegalArgumentException("damage must be >= 0, got " + damage);
            }
            if (!Float.isFinite(swingSeconds) || swingSeconds <= 0f) {
                throw new IllegalArgumentException("swingSeconds must be > 0, got " + swingSeconds);
            }
        }

        private static String normalizeName(String value, String field) {
            Objects.requireNonNull(value, field + " cannot be null");
            String n = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (n.isEmpty()) {
                throw new IllegalArgumentException(field + " cannot be blank");
            }
            return n;
        }
    }

    /**
     * Optional mining-tool descriptor embedded in an item SBO manifest (1.9+).
     *
     * <p>Declares which block materials the tool breaks efficiently and by how
     * much. The rule (a block is sped up when its {@link GameProperties#material()}
     * is in {@code materials} and {@code tier >= block.requiredTier}) lives in
     * {@link com.stonebreak.items.ToolMiningRules}; this record is data only.
     *
     * @param toolClass       free-form tool kind ({@code pickaxe}, {@code axe},
     *                        {@code shovel}, ...); lowercased, never blank
     * @param tier            tool tier, {@code >= 0} (see {@code ToolMiningRules.TIER_NAMES})
     * @param speedMultiplier hardness multiplier on matching blocks, {@code > 0}
     * @param materials       block materials this tool is effective on;
     *                        lowercased, de-duplicated, never null, may be empty
     * @param durability      optional uses before breaking ({@code >= 1});
     *                        {@code null} = unspecified. Not read by the game yet.
     * @param attackDamage    optional melee damage ({@code >= 0}); {@code null}
     *                        = unspecified. Superseded by {@link WeaponData#damage()}
     *                        (1.10); not read by the game.
     */
    public record ToolData(
            String toolClass,
            int tier,
            float speedMultiplier,
            List<String> materials,
            Integer durability,
            Float attackDamage
    ) {
        public ToolData {
            Objects.requireNonNull(toolClass, "toolClass cannot be null");
            toolClass = toolClass.trim().toLowerCase(java.util.Locale.ROOT);
            if (toolClass.isEmpty()) {
                throw new IllegalArgumentException("toolClass cannot be blank");
            }
            if (tier < 0) {
                throw new IllegalArgumentException("tier must be >= 0, got " + tier);
            }
            if (!Float.isFinite(speedMultiplier) || speedMultiplier <= 0f) {
                throw new IllegalArgumentException("speedMultiplier must be > 0, got " + speedMultiplier);
            }
            java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
            if (materials != null) {
                for (String m : materials) {
                    String n = normalizeMaterial(m);
                    if (n != null) normalized.add(n);
                }
            }
            materials = List.copyOf(normalized);
            if (durability != null && durability < 1) {
                throw new IllegalArgumentException("durability must be >= 1, got " + durability);
            }
            if (attackDamage != null && (!Float.isFinite(attackDamage) || attackDamage < 0f)) {
                throw new IllegalArgumentException("attackDamage must be >= 0, got " + attackDamage);
            }
        }
    }

    /** Lowercased, trimmed material name, or {@code null} when blank (1.9+). */
    public static String normalizeMaterial(String material) {
        if (material == null) return null;
        String m = material.trim().toLowerCase(java.util.Locale.ROOT);
        return m.isEmpty() ? null : m;
    }

    /**
     * Optional drop table embedded in a block SBO manifest (format version 1.8+).
     *
     * <p>Describes what the block yields when broken. {@code drops} is the
     * default list, used whenever no tool override matches the breaking tool
     * (including breaking by hand). {@code toolOverrides} replace — not
     * augment — the default list when the breaking tool's {@code objectId}
     * matches; the first matching override wins.
     *
     * <p>Presence of this section is authoritative: an empty {@code drops} list
     * means the block drops nothing. Blocks whose manifest has no section fall
     * back to the game's built-in rule.
     *
     * @param drops         default drop entries; never null but may be empty
     * @param toolOverrides per-tool replacement lists; never null but may be empty
     */
    public record DropData(List<DropEntry> drops, List<ToolDropOverride> toolOverrides) {
        public DropData {
            drops = drops == null ? Collections.emptyList() : List.copyOf(drops);
            toolOverrides = toolOverrides == null ? Collections.emptyList() : List.copyOf(toolOverrides);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ToolDropOverride o : toolOverrides) {
                if (!seen.add(o.toolObjectId())) {
                    throw new IllegalArgumentException("duplicate tool override: " + o.toolObjectId());
                }
            }
        }

        /** Convenience: an explicitly empty table ("drops nothing"). */
        public static DropData nothing() {
            return new DropData(Collections.emptyList(), Collections.emptyList());
        }

        /**
         * Resolves the drop list that applies when the block is broken with the
         * given tool: the matching override's list, or the default list when
         * {@code toolObjectId} is null or has no override.
         */
        public List<DropEntry> dropsFor(String toolObjectId) {
            if (toolObjectId != null) {
                for (ToolDropOverride o : toolOverrides) {
                    if (o.toolObjectId().equals(toolObjectId)) return o.drops();
                }
            }
            return drops;
        }

        /** True when both the default list and every override are empty. */
        public boolean isEmpty() {
            if (!drops.isEmpty()) return false;
            for (ToolDropOverride o : toolOverrides) {
                if (!o.drops().isEmpty()) return false;
            }
            return true;
        }
    }

    /**
     * One drop line (1.8+): an item or block, an inclusive count range and a
     * probability that the line yields anything at all.
     *
     * @param objectId SBO {@code objectId} of the dropped item or block
     *                 (e.g. {@code "stonebreak:clay_chunk"})
     * @param minCount minimum count when the line fires (>= 0)
     * @param maxCount maximum count when the line fires (>= minCount)
     * @param chance   probability in {@code [0, 1]} that the line fires
     */
    public record DropEntry(String objectId, int minCount, int maxCount, float chance) {
        public DropEntry {
            Objects.requireNonNull(objectId, "objectId cannot be null");
            if (objectId.isBlank()) {
                throw new IllegalArgumentException("objectId cannot be blank");
            }
            if (minCount < 0) {
                throw new IllegalArgumentException("minCount must be >= 0, got " + minCount);
            }
            if (maxCount < minCount) {
                throw new IllegalArgumentException(
                        "maxCount must be >= minCount, got " + maxCount + " < " + minCount);
            }
            if (Float.isNaN(chance) || chance < 0f || chance > 1f) {
                throw new IllegalArgumentException("chance must be in [0,1], got " + chance);
            }
        }

        /** A guaranteed drop of exactly {@code count}. */
        public static DropEntry of(String objectId, int count) {
            return new DropEntry(objectId, count, count, 1f);
        }
    }

    /**
     * A per-tool replacement for the default drop list (1.8+).
     *
     * @param toolObjectId SBO {@code objectId} of the breaking tool
     *                     (e.g. {@code "stonebreak:stone_shovel"})
     * @param drops        drop lines used instead of the defaults; never null,
     *                     may be empty (= this tool yields nothing)
     */
    public record ToolDropOverride(String toolObjectId, List<DropEntry> drops) {
        public ToolDropOverride {
            Objects.requireNonNull(toolObjectId, "toolObjectId cannot be null");
            if (toolObjectId.isBlank()) {
                throw new IllegalArgumentException("toolObjectId cannot be blank");
            }
            drops = drops == null ? Collections.emptyList() : List.copyOf(drops);
        }
    }

    /**
     * Optional smelting recipe block embedded in an SBO manifest (format version 1.5+).
     *
     * <p>Like {@link RecipeData}, smelting recipes are declared on the SBO that
     * <em>produces</em> the output. Each entry references its input by SBO
     * {@code objectId}; the output is implicit (the SBO's own object) and may
     * be produced in {@code outputCount} copies per smelt.
     *
     * @param recipes list of smelting recipes; never null but may be empty
     */
    public record SmeltingRecipeData(List<SmeltingRecipeEntry> recipes) {
        public SmeltingRecipeData {
            recipes = recipes == null ? Collections.emptyList() : List.copyOf(recipes);
        }
    }

    /**
     * One smelting recipe (1.5+).
     *
     * <p>The output is implicit: the SBO that owns this recipe.
     *
     * @param inputObjectId SBO {@code objectId} of the input ingredient
     *                      (e.g. {@code "stonebreak:cobblestone"})
     * @param outputCount   number of output items produced per smelt (>= 1)
     */
    public record SmeltingRecipeEntry(
            String inputObjectId,
            int outputCount
    ) {
        public SmeltingRecipeEntry {
            Objects.requireNonNull(inputObjectId, "inputObjectId cannot be null");
            if (inputObjectId.isBlank()) {
                throw new IllegalArgumentException("inputObjectId cannot be blank");
            }
            if (outputCount < 1) {
                throw new IllegalArgumentException("outputCount must be >= 1, got " + outputCount);
            }
        }
    }

    /**
     * Optional fuel descriptor embedded in an SBO manifest (format version 1.5+).
     *
     * <p>Declares that the SBO's own item burns as furnace fuel for the given
     * number of game-ticks per unit.
     *
     * @param burnTicks burn time per unit, in game-ticks (>= 1)
     */
    public record FuelData(int burnTicks) {
        public FuelData {
            if (burnTicks < 1) {
                throw new IllegalArgumentException("burnTicks must be >= 1, got " + burnTicks);
            }
        }
    }

    /**
     * Fuel table: burn times for objects that do not declare their own {@link FuelData}. Lets one
     * SBO (the furnace) make a whole dictionary burnable, e.g. {@code "#stonebreak:logs"} → 300.
     * An object's own {@code fuel} always takes precedence over a table entry.
     *
     * @param entries fuel entries; never null but may be empty
     */
    public record FuelTableData(List<FuelTableEntry> entries) {
        public FuelTableData {
            entries = entries == null ? Collections.emptyList() : List.copyOf(entries);
        }
    }

    /**
     * One fuel-table entry.
     *
     * @param ingredient an objectId, or {@code #}-prefixed dictionary id; never blank
     * @param burnTicks  burn time per unit, in game-ticks (>= 1)
     */
    public record FuelTableEntry(String ingredient, int burnTicks) {
        public FuelTableEntry {
            if (ingredient == null || ingredient.isBlank()) {
                throw new IllegalArgumentException("fuel table ingredient must not be blank");
            }
            if (burnTicks < 1) {
                throw new IllegalArgumentException("burnTicks must be >= 1, got " + burnTicks);
            }
            ingredient = ingredient.strip();
        }
    }

    /**
     * Optional crafting recipe block embedded in an SBO manifest (format version 1.4+).
     *
     * <p>Recipes declared here produce the SBO's own object as output. The game
     * scans every SBO at startup, reads this block (when present), and registers
     * the resulting {@code Recipe} entries with its {@code CraftingManager}.
     *
     * <p>Currently only shaped recipes are supported. Field is forward-compatible:
     * future shapeless / smelting / etc. variants would be added as additional
     * sibling lists, leaving {@code shaped} alone.
     *
     * @param shaped list of shaped recipes; never null but may be empty
     */
    public record RecipeData(List<ShapedRecipe> shaped) {
        public RecipeData {
            shaped = shaped == null ? Collections.emptyList() : List.copyOf(shaped);
        }
    }

    /**
     * One shaped crafting recipe (1.4+).
     *
     * <p>The output is implicit: the SBO that owns this recipe. {@code outputCount}
     * specifies how many copies of that output are produced.
     *
     * <p>{@code pattern} is a row-major flat list of length {@code width * height}.
     * Each slot is either an SBO {@code objectId} (e.g. {@code "stonebreak:oak_log"})
     * or an empty string for an empty cell. The game resolves these to
     * {@code ItemStack} instances at recipe-load time.
     *
     * @param width       horizontal extent of the pattern (1..3)
     * @param height      vertical extent of the pattern (1..3)
     * @param pattern     row-major slot list of length {@code width * height};
     *                    each entry is an objectId or {@code ""} for empty
     * @param outputCount number of output items produced per craft (>= 1)
     */
    public record ShapedRecipe(
            int width,
            int height,
            List<String> pattern,
            int outputCount
    ) {
        public ShapedRecipe {
            if (width < 1 || width > 3) {
                throw new IllegalArgumentException("recipe width must be 1..3, got " + width);
            }
            if (height < 1 || height > 3) {
                throw new IllegalArgumentException("recipe height must be 1..3, got " + height);
            }
            Objects.requireNonNull(pattern, "pattern cannot be null");
            if (pattern.size() != width * height) {
                throw new IllegalArgumentException(
                        "pattern size " + pattern.size() + " does not match "
                                + width + "x" + height + " (= " + (width * height) + ")");
            }
            if (outputCount < 1) {
                throw new IllegalArgumentException("outputCount must be >= 1, got " + outputCount);
            }
            // Normalize null entries to empty strings for safety; copy for immutability.
            List<String> normalized = new ArrayList<>(pattern.size());
            for (String slot : pattern) {
                normalized.add(slot == null ? "" : slot);
            }
            pattern = List.copyOf(normalized);
        }
    }

    /**
     * Stonebreak block/item properties ({@code gameProperties}; SBO 1.1+, a game extension since 1.11).
     *
     * <p>This block lets the game populate its block/item registry directly from
     * the SBO file rather than relying on an enforced enum. All fields are
     * primitive or nullable so the registry can fall back to defaults when an
     * older 1.0 SBO is encountered.
     *
     * <p>Field semantics:
     * <ul>
     *   <li>{@code numericId} - stable numeric ID used by the chunk save format.
     *       Must be unique across the registry. Required for blocks (so saves are
     *       portable); items use a separate ID space (1000+).</li>
     *   <li>{@code hardness} - seconds to break with bare hands. Use
     *       {@link Float#POSITIVE_INFINITY} for unbreakable.</li>
     *   <li>{@code solid} - whether the block has collision geometry.</li>
     *   <li>{@code breakable} - whether players can break the block.</li>
     *   <li>{@code atlasX}, {@code atlasY} - texture atlas coords for the
     *       inventory/hotbar icon (legacy field; -1 means "no icon").</li>
     *   <li>{@code flower} - whether water flow can break this block.</li>
     *   <li>{@code stackable} - whether the block supports layered stacking
     *       (e.g. snow).</li>
     *   <li>{@code maxStackSize} - inventory stack size (default 64 for blocks).</li>
     *   <li>{@code category} - item category for inventory grouping ("BLOCKS",
     *       "TOOLS", "MATERIALS", etc.).</li>
     *   <li>{@code placeable} - whether the item can be placed in the world as a
     *       block. True for all blocks; false for tool/material items.</li>
     *   <li>{@code material} (1.9+) - block material name ({@code stone},
     *       {@code wood}, {@code dirt}, ...) that {@link ToolData#materials()}
     *       lists refer to. Lowercased; {@code null} = no material, so no tool
     *       speeds the block up.</li>
     *   <li>{@code requiredTier} (1.9+) - minimum {@link ToolData#tier()} for a
     *       tool to speed this block up; {@code 0} = any tier.</li>
     * </ul>
     */
    public record GameProperties(
            int numericId,
            float hardness,
            boolean solid,
            boolean breakable,
            int atlasX,
            int atlasY,
            boolean flower,
            boolean stackable,
            int maxStackSize,
            String category,
            boolean placeable,
            String material,
            int requiredTier
    ) {
        public GameProperties {
            // category may be null; categoryOrDefault() defaults it.
            material = normalizeMaterial(material);
            requiredTier = Math.max(0, requiredTier);
        }

        /** Copy with the 1.9 mining fields replaced. */
        public GameProperties withMining(String newMaterial, int newRequiredTier) {
            return new GameProperties(numericId, hardness, solid, breakable, atlasX, atlasY,
                    flower, stackable, maxStackSize, category, placeable, newMaterial, newRequiredTier);
        }

        /** Convenience: returns the category or "BLOCKS" if absent. */
        public String categoryOrDefault() {
            return category != null && !category.isBlank() ? category : "BLOCKS";
        }
    }
}
