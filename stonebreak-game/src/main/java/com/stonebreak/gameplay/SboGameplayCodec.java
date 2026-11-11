package com.stonebreak.gameplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.ext.FormatExtensionCodec;
import com.stonebreak.items.ToolMiningRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code "stonebreak:gameplay"} SBO extension (#383): reads and writes {@link SboGameplay}.
 *
 * <p>The extension object has the same members, with the same shapes, as the top-level fields
 * SBO 1.1–1.10 wrote ({@link #legacyFields()}), so an older file decodes through the same reader.
 * Only {@code gameProperties.renderLayer}/{@code transparent} are left out: they are the format's
 * own {@code rendering} now.
 *
 * <p>Lenient like the old parser: a malformed section degrades to "absent" (logged) instead of
 * failing the whole SBO, and invalid recipe/drop lines are skipped.
 */
public final class SboGameplayCodec implements FormatExtensionCodec<SboGameplay> {

    private static final Logger logger = LoggerFactory.getLogger(SboGameplayCodec.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The extension key. */
    public static final String KEY = "stonebreak:gameplay";

    /** The one codec instance (decoded values are cached per codec). */
    public static final SboGameplayCodec INSTANCE = new SboGameplayCodec();

    /** The top-level fields SBO 1.1–1.10 used for this data. */
    public static final Set<String> LEGACY_FIELDS = Set.of(
            "gameProperties", "recipes", "smeltingRecipes", "fuel", "drops", "tool", "weapon",
            "dictionaries", "fuelTable", "connectTags", "multipart");

    private SboGameplayCodec() {
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Set<String> legacyFields() {
        return LEGACY_FIELDS;
    }

    // ── decode ───────────────────────────────────────────────────────────────

    @Override
    public SboGameplay decode(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(KEY + " must be an object");
        }
        return new SboGameplay(readGameProperties(root), readRecipes(root), readSmelting(root), readFuel(root),
                readDrops(root), readTool(root), readWeapon(root), readDictionaries(root), readFuelTable(root),
                readConnectTags(root), readMultipart(root));
    }

    private static boolean present(JsonNode root, String field) {
        return root.has(field) && !root.get(field).isNull();
    }

    static SboGameplay.GameProperties readGameProperties(JsonNode root) {
        if (!present(root, "gameProperties")) return null;
        var gp = root.get("gameProperties");
        return new SboGameplay.GameProperties(
                gp.has("numericId") ? gp.get("numericId").asInt() : -1,
                gp.has("hardness") ? (float) gp.get("hardness").asDouble() : 1.0f,
                !gp.has("solid") || gp.get("solid").asBoolean(),
                !gp.has("breakable") || gp.get("breakable").asBoolean(),
                gp.has("atlasX") ? gp.get("atlasX").asInt() : -1,
                gp.has("atlasY") ? gp.get("atlasY").asInt() : -1,
                gp.has("flower") && gp.get("flower").asBoolean(),
                gp.has("stackable") && gp.get("stackable").asBoolean(),
                gp.has("maxStackSize") ? gp.get("maxStackSize").asInt() : 64,
                gp.has("category") ? gp.get("category").asText() : null,
                !gp.has("placeable") || gp.get("placeable").asBoolean(),
                gp.hasNonNull("material") ? gp.get("material").asText() : null,
                gp.has("requiredTier") ? readTier(gp.get("requiredTier"), "gameProperties.requiredTier") : 0);
    }

    static SboGameplay.RecipeData readRecipes(JsonNode root) {
        if (!present(root, "recipes")) return null;
        var recipesNode = root.get("recipes");
        List<SboGameplay.ShapedRecipe> shaped = new ArrayList<>();
        if (recipesNode.has("shaped") && recipesNode.get("shaped").isArray()) {
            for (var rNode : recipesNode.get("shaped")) {
                int width = rNode.has("width") ? rNode.get("width").asInt() : 0;
                int height = rNode.has("height") ? rNode.get("height").asInt() : 0;
                int outputCount = rNode.has("outputCount") ? rNode.get("outputCount").asInt() : 1;
                List<String> pattern = new ArrayList<>();
                if (rNode.has("pattern") && rNode.get("pattern").isArray()) {
                    for (var slot : rNode.get("pattern")) {
                        pattern.add(slot.isNull() ? "" : slot.asText());
                    }
                }
                try {
                    shaped.add(new SboGameplay.ShapedRecipe(width, height, pattern, outputCount));
                } catch (IllegalArgumentException ex) {
                    logger.warn("Skipping invalid shaped recipe in manifest: {}", ex.getMessage());
                }
            }
        }
        return shaped.isEmpty() ? null : new SboGameplay.RecipeData(shaped);
    }

    static SboGameplay.SmeltingRecipeData readSmelting(JsonNode root) {
        if (!present(root, "smeltingRecipes")) return null;
        var smeltNode = root.get("smeltingRecipes");
        List<SboGameplay.SmeltingRecipeEntry> list = new ArrayList<>();
        if (smeltNode.has("recipes") && smeltNode.get("recipes").isArray()) {
            for (var rNode : smeltNode.get("recipes")) {
                String input = rNode.has("input") ? rNode.get("input").asText() : "";
                int outputCount = rNode.has("outputCount") ? rNode.get("outputCount").asInt() : 1;
                try {
                    list.add(new SboGameplay.SmeltingRecipeEntry(input, outputCount));
                } catch (IllegalArgumentException ex) {
                    logger.warn("Skipping invalid smelting recipe in manifest: {}", ex.getMessage());
                }
            }
        }
        return list.isEmpty() ? null : new SboGameplay.SmeltingRecipeData(list);
    }

    static List<String> readDictionaries(JsonNode root) {
        if (!present(root, "dictionaries")) return List.of();
        var node = root.get("dictionaries");
        if (!node.isArray()) {
            logger.warn("Ignoring non-array 'dictionaries' in manifest");
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (var id : node) {
            if (id.isTextual()) ids.add(id.asText());
        }
        return ids; // the record strips blanks/duplicates
    }

    static List<String> readConnectTags(JsonNode root) {
        if (!present(root, "connectTags")) return List.of();
        var node = root.get("connectTags");
        if (!node.isArray()) {
            logger.warn("Ignoring non-array 'connectTags' in manifest");
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (var tag : node) {
            if (tag.isTextual()) tags.add(tag.asText());
        }
        return tags; // the record strips blanks/duplicates
    }

    /**
     * The {@code multipart} section. A malformed part is skipped (logged), so one bad row can't
     * strip a block of its whole shape; a section with no valid part reads as absent.
     */
    static MultipartData readMultipart(JsonNode root) {
        if (!present(root, "multipart")) return null;
        var node = root.get("multipart");
        if (!node.isArray()) {
            logger.warn("Ignoring non-array 'multipart' in manifest");
            return null;
        }
        List<MultipartData.Part> parts = new ArrayList<>();
        for (var pNode : node) {
            try {
                parts.add(readPart(pNode));
            } catch (IllegalArgumentException ex) {
                logger.warn("Skipping invalid multipart part in manifest: {}", ex.getMessage());
            }
        }
        return parts.isEmpty() ? null : new MultipartData(parts);
    }

    private static MultipartData.Part readPart(JsonNode pNode) {
        String state = pNode.hasNonNull("state") ? pNode.get("state").asText() : "";
        var when = new java.util.EnumMap<MultipartData.Side, MultipartData.Condition>(MultipartData.Side.class);
        JsonNode whenNode = pNode.get("when");
        if (whenNode != null && whenNode.isObject()) {
            var fields = whenNode.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                MultipartData.Side side = MultipartData.Side.ofKey(field.getKey());
                if (side == null) {
                    throw new IllegalArgumentException("unknown side '" + field.getKey() + "'");
                }
                when.put(side, MultipartData.Condition.parse(field.getValue().asText()));
            }
        }
        List<MultipartData.Box> boxes = new ArrayList<>();
        JsonNode boxesNode = pNode.get("boxes");
        if (boxesNode != null && boxesNode.isArray()) {
            for (var b : boxesNode) {
                if (!b.isArray() || b.size() != 6) {
                    throw new IllegalArgumentException("a box is [minX, minY, minZ, maxX, maxY, maxZ]");
                }
                boxes.add(new MultipartData.Box((float) b.get(0).asDouble(), (float) b.get(1).asDouble(),
                        (float) b.get(2).asDouble(), (float) b.get(3).asDouble(), (float) b.get(4).asDouble(),
                        (float) b.get(5).asDouble()));
            }
        }
        return new MultipartData.Part(state, when, boxes);
    }

    static SboGameplay.FuelTableData readFuelTable(JsonNode root) {
        if (!present(root, "fuelTable")) return null;
        var node = root.get("fuelTable");
        List<SboGameplay.FuelTableEntry> list = new ArrayList<>();
        if (node.has("entries") && node.get("entries").isArray()) {
            for (var e : node.get("entries")) {
                String ingredient = e.has("ingredient") ? e.get("ingredient").asText() : "";
                int burnTicks = e.has("burnTicks") ? e.get("burnTicks").asInt() : 0;
                try {
                    list.add(new SboGameplay.FuelTableEntry(ingredient, burnTicks));
                } catch (IllegalArgumentException ex) {
                    logger.warn("Skipping invalid fuel table entry in manifest: {}", ex.getMessage());
                }
            }
        }
        return new SboGameplay.FuelTableData(list);
    }

    static SboGameplay.FuelData readFuel(JsonNode root) {
        if (!present(root, "fuel")) return null;
        var fuelNode = root.get("fuel");
        int burnTicks = fuelNode.has("burnTicks") ? fuelNode.get("burnTicks").asInt() : 0;
        if (burnTicks <= 0) return null;
        try {
            return new SboGameplay.FuelData(burnTicks);
        } catch (IllegalArgumentException ex) {
            logger.warn("Skipping invalid fuel block in manifest: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * The {@code drops} section: null when absent (or not an object) so the game keeps its default
     * rule; a present section always yields a table, even an empty one ("drops nothing").
     */
    static SboGameplay.DropData readDrops(JsonNode root) {
        if (!present(root, "drops") || !root.get("drops").isObject()) return null;
        var dropsNode = root.get("drops");
        List<SboGameplay.DropEntry> defaults = readDropEntries(dropsNode.get("default"), "default");
        List<SboGameplay.ToolDropOverride> overrides = new ArrayList<>();
        if (dropsNode.has("byTool") && dropsNode.get("byTool").isArray()) {
            for (var oNode : dropsNode.get("byTool")) {
                String tool = oNode.has("tool") ? oNode.get("tool").asText() : "";
                List<SboGameplay.DropEntry> lines = readDropEntries(oNode.get("drops"), "byTool[" + tool + "]");
                try {
                    overrides.add(new SboGameplay.ToolDropOverride(tool, lines));
                } catch (IllegalArgumentException ex) {
                    logger.warn("Skipping invalid tool drop override in manifest: {}", ex.getMessage());
                }
            }
        }
        try {
            return new SboGameplay.DropData(defaults, overrides);
        } catch (IllegalArgumentException ex) {
            logger.warn("Ignoring invalid drops block in manifest: {}", ex.getMessage());
            return null;
        }
    }

    private static List<SboGameplay.DropEntry> readDropEntries(JsonNode arr, String where) {
        List<SboGameplay.DropEntry> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (var dNode : arr) {
            String objectId = dNode.has("objectId") ? dNode.get("objectId").asText() : "";
            int min = dNode.has("min") ? dNode.get("min").asInt() : 1;
            int max = dNode.has("max") ? dNode.get("max").asInt() : min;
            float chance = dNode.has("chance") ? (float) dNode.get("chance").asDouble() : 1f;
            try {
                out.add(new SboGameplay.DropEntry(objectId, min, max, chance));
            } catch (IllegalArgumentException ex) {
                logger.warn("Skipping invalid drop entry ({}) in manifest: {}", where, ex.getMessage());
            }
        }
        return out;
    }

    /** The {@code tool} section; a malformed one degrades to "not a mining tool" (logged). */
    static SboGameplay.ToolData readTool(JsonNode root) {
        if (!present(root, "tool") || !root.get("tool").isObject()) return null;
        var t = root.get("tool");
        List<String> materials = new ArrayList<>();
        if (t.has("materials") && t.get("materials").isArray()) {
            for (var m : t.get("materials")) materials.add(m.asText());
        }
        try {
            return new SboGameplay.ToolData(
                    t.has("toolClass") ? t.get("toolClass").asText() : "",
                    t.has("tier") ? readTier(t.get("tier"), "tool.tier") : 0,
                    t.has("speedMultiplier") ? (float) t.get("speedMultiplier").asDouble() : 1.0f,
                    materials,
                    t.hasNonNull("durability") ? t.get("durability").asInt() : null,
                    t.hasNonNull("attackDamage") ? (float) t.get("attackDamage").asDouble() : null);
        } catch (IllegalArgumentException ex) {
            logger.warn("Ignoring invalid tool block in manifest: {}", ex.getMessage());
            return null;
        }
    }

    /** The {@code weapon} section; a malformed one degrades to "not a weapon" (logged). */
    static SboGameplay.WeaponData readWeapon(JsonNode root) {
        if (!present(root, "weapon") || !root.get("weapon").isObject()) return null;
        var w = root.get("weapon");
        try {
            return new SboGameplay.WeaponData(
                    w.has("group") ? w.get("group").asText() : "",
                    w.has("damageType") ? w.get("damageType").asText() : "",
                    w.has("damage") ? (float) w.get("damage").asDouble() : -1f,
                    w.has("swingSeconds") ? (float) w.get("swingSeconds").asDouble() : 0f);
        } catch (IllegalArgumentException ex) {
            logger.warn("Ignoring invalid weapon block in manifest: {}", ex.getMessage());
            return null;
        }
    }

    /** A tier written as an int or a tier name ("stone"); unreadable values become 0 with a warning. */
    private static int readTier(JsonNode node, String where) {
        if (node == null || node.isNull()) return 0;
        if (node.isNumber()) return node.asInt();
        int tier = ToolMiningRules.parseTier(node.asText());
        if (tier < 0) {
            logger.warn("Unknown tier '{}' at {} — treating as 0", node.asText(), where);
            return 0;
        }
        return tier;
    }

    // ── encode ───────────────────────────────────────────────────────────────

    @Override
    public JsonNode encode(SboGameplay g) {
        ObjectNode out = JSON.createObjectNode();
        if (g.gameProperties() != null) out.set("gameProperties", encode(g.gameProperties()));
        if (g.hasRecipes()) out.set("recipes", encode(g.recipes()));
        if (g.hasSmeltingRecipes()) out.set("smeltingRecipes", encode(g.smeltingRecipes()));
        if (g.hasFuel()) out.set("fuel", JSON.createObjectNode().put("burnTicks", g.fuel().burnTicks()));
        // Presence is meaningful (empty = drops nothing), so write whenever set.
        if (g.hasDrops()) out.set("drops", encode(g.drops()));
        if (g.hasTool()) out.set("tool", encode(g.tool()));
        if (g.hasWeapon()) out.set("weapon", encode(g.weapon()));
        if (g.hasDictionaries()) {
            ArrayNode ids = out.putArray("dictionaries");
            g.dictionaries().forEach(ids::add);
        }
        if (g.hasFuelTable()) {
            ArrayNode entries = JSON.createArrayNode();
            for (SboGameplay.FuelTableEntry e : g.fuelTable().entries()) {
                entries.addObject().put("ingredient", e.ingredient()).put("burnTicks", e.burnTicks());
            }
            out.putObject("fuelTable").set("entries", entries);
        }
        if (!g.connectTags().isEmpty()) {
            ArrayNode tags = out.putArray("connectTags");
            g.connectTags().forEach(tags::add);
        }
        if (g.hasMultipart()) out.set("multipart", encode(g.multipart()));
        return out;
    }

    private static ArrayNode encode(MultipartData data) {
        ArrayNode parts = JSON.createArrayNode();
        for (MultipartData.Part part : data.parts()) {
            ObjectNode n = parts.addObject();
            n.put("state", part.state());
            if (!part.when().isEmpty()) {
                ObjectNode when = n.putObject("when");
                part.when().forEach((side, condition) -> when.put(side.key(), condition.encode()));
            }
            if (!part.boxes().isEmpty()) {
                ArrayNode boxes = n.putArray("boxes");
                for (MultipartData.Box b : part.boxes()) {
                    boxes.addArray().add(b.minX()).add(b.minY()).add(b.minZ())
                            .add(b.maxX()).add(b.maxY()).add(b.maxZ());
                }
            }
        }
        return parts;
    }

    /**
     * Jackson writes a {@code POSITIVE_INFINITY} hardness as the string {@code "Infinity"}; the
     * reader's {@code asDouble()} reads it back.
     */
    static ObjectNode encode(SboGameplay.GameProperties gp) {
        ObjectNode n = JSON.createObjectNode();
        n.put("numericId", gp.numericId());
        n.put("hardness", gp.hardness());
        n.put("solid", gp.solid());
        n.put("breakable", gp.breakable());
        n.put("atlasX", gp.atlasX());
        n.put("atlasY", gp.atlasY());
        n.put("flower", gp.flower());
        n.put("stackable", gp.stackable());
        n.put("maxStackSize", gp.maxStackSize());
        if (gp.category() != null) n.put("category", gp.category());
        n.put("placeable", gp.placeable());
        if (gp.material() != null) n.put("material", gp.material());
        if (gp.requiredTier() != 0) n.put("requiredTier", gp.requiredTier());
        return n;
    }

    private static ObjectNode encode(SboGameplay.RecipeData data) {
        ArrayNode shaped = JSON.createArrayNode();
        for (SboGameplay.ShapedRecipe r : data.shaped()) {
            ObjectNode n = shaped.addObject();
            n.put("width", r.width());
            n.put("height", r.height());
            ArrayNode pattern = n.putArray("pattern");
            r.pattern().forEach(pattern::add);
            n.put("outputCount", r.outputCount());
        }
        ObjectNode out = JSON.createObjectNode();
        out.set("shaped", shaped);
        return out;
    }

    private static ObjectNode encode(SboGameplay.SmeltingRecipeData data) {
        ArrayNode recipes = JSON.createArrayNode();
        for (SboGameplay.SmeltingRecipeEntry e : data.recipes()) {
            recipes.addObject().put("input", e.inputObjectId()).put("outputCount", e.outputCount());
        }
        ObjectNode out = JSON.createObjectNode();
        out.set("recipes", recipes);
        return out;
    }

    private static ObjectNode encode(SboGameplay.DropData data) {
        ObjectNode out = JSON.createObjectNode();
        out.set("default", encode(data.drops()));
        ArrayNode byTool = out.putArray("byTool");
        for (SboGameplay.ToolDropOverride o : data.toolOverrides()) {
            ObjectNode n = byTool.addObject();
            n.put("tool", o.toolObjectId());
            n.set("drops", encode(o.drops()));
        }
        return out;
    }

    private static ArrayNode encode(List<SboGameplay.DropEntry> entries) {
        ArrayNode arr = JSON.createArrayNode();
        for (SboGameplay.DropEntry e : entries) {
            arr.addObject().put("objectId", e.objectId()).put("min", e.minCount()).put("max", e.maxCount())
                    .put("chance", e.chance());
        }
        return arr;
    }

    private static ObjectNode encode(SboGameplay.ToolData t) {
        ObjectNode n = JSON.createObjectNode();
        n.put("toolClass", t.toolClass());
        n.put("tier", t.tier());
        n.put("speedMultiplier", t.speedMultiplier());
        ArrayNode materials = n.putArray("materials");
        t.materials().forEach(materials::add);
        if (t.durability() != null) n.put("durability", t.durability());
        if (t.attackDamage() != null) n.put("attackDamage", t.attackDamage());
        return n;
    }

    private static ObjectNode encode(SboGameplay.WeaponData w) {
        return JSON.createObjectNode().put("group", w.group()).put("damageType", w.damageType())
                .put("damage", w.damage()).put("swingSeconds", w.swingSeconds());
    }
}
