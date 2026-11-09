package com.stonebreak.openmason.assets.mcp;

import com.stonebreak.items.ToolMiningRules;
import com.stonebreak.mobs.sbe.SbeEntityKind;
import com.stonebreak.gameplay.SboGameplay;
import com.stonebreak.openmason.assets.AtlasSlots;
import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.format.sound.SoundSpec;
import com.openmason.main.systems.menus.dialogs.SBOExportWindow;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;
import com.openmason.main.systems.target.GameTargets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * JSON ⇄ SBO/SBE parameter plumbing for the {@code sbo_export} /
 * {@code sbe_export} / {@code *_editor_get} / {@code *_editor_set} tools.
 * Pure (no UI, no threads) so the defaults the export windows apply are
 * reproduced and tested here.
 */
public final class AssetExportBuilder {

    private AssetExportBuilder() {
    }

    // ================================================================ SBO

    /**
     * Build SBO export parameters from a tool's JSON.
     *
     * @param p           the {@code params} object (may be missing keys)
     * @param defaultOmo  the current model's on-disk .omo (used for every state without {@code omo})
     * @param defaultName model name for the objectName/objectId defaults
     * @param files       resolves a caller path to an existing file inside the roots
     */
    public static SBOFormat.ExportParameters sbo(JsonNode p, Path defaultOmo, String defaultName,
                                                 Function<String, Path> files) {
        return sbo(p, defaultOmo, defaultName, files, false);
    }

    /**
     * Texture-context twin of {@link #sbo}: the payload is a {@code .omt}, the
     * object type defaults to {@code item} (block/entity are refused — they need
     * a model), game properties get the {@link #spriteGameProperties sprite
     * defaults}, and states take {@code omt} sources and cannot carry clips.
     * Mirrors {@code SBOExportWindow.showForTexture()}.
     *
     * @param defaultOmt  the texture's on-disk .omt (used for every state without {@code omt})
     */
    public static SBOFormat.ExportParameters sboTexture(JsonNode p, Path defaultOmt, String defaultName,
                                                        Function<String, Path> files) {
        return sbo(p, defaultOmt, defaultName, files, true);
    }

    /**
     * Whether an {@code sbo_export} call targets the texture editor's .omt:
     * {@code source: "texture"}, or an explicit {@code omt} file.
     */
    public static boolean isTextureSource(JsonNode p) {
        if (p == null) {
            return false;
        }
        String source = text(p, "source", null);
        if (source == null) {
            return p.hasNonNull("omt");
        }
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "texture", "omt" -> true;
            case "model", "omo" -> {
                if (p.hasNonNull("omt")) {
                    throw new IllegalArgumentException("invalid_params: omt is only valid with source \"texture\"");
                }
                yield false;
            }
            default -> throw new IllegalArgumentException(
                    "invalid_params: source must be \"model\" or \"texture\", got \"" + source + "\"");
        };
    }

    /** Object types a texture-only SBO can be. Blocks and entities need a model. */
    public static boolean textureObjectTypeAllowed(SBOFormat.ObjectType type) {
        return SBOExportWindow.textureObjectTypeAllowed(type);
    }

    private static SBOFormat.ExportParameters sbo(JsonNode p, Path defaultSource, String defaultName,
                                                  Function<String, Path> files, boolean texture) {
        JsonNode n = p == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : p;
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        String name = text(n, "objectName", cleanName(defaultName));
        params.setObjectName(name);
        params.setObjectId(text(n, "objectId", GameTargets.shared().qualify(slug(name))));
        SBOFormat.ObjectType type = SBOFormat.ObjectType.fromId(text(n, "objectType", texture ? "item" : "block"));
        if (texture && !textureObjectTypeAllowed(type)) {
            throw new IllegalArgumentException("invalid_params: objectType " + type.getId()
                    + " needs a model — a texture-only SBO can be item|decoration|particle|other");
        }
        params.setObjectType(type);
        params.setObjectPack(text(n, "objectPack", "default"));
        params.setAuthor(text(n, "author", defaultAuthor()));
        params.setDescription(text(n, "description", ""));

        SboGameplay gameplay = SboGameplay.NONE;
        NumericIdValidator.Domain domain = NumericIdValidator.domainFor(type.getId());
        JsonNode gp = n.get("gameProperties");
        boolean wantsGp = (gp != null && !gp.isNull()) || n.hasNonNull("numericId")
                || domain != NumericIdValidator.Domain.NONE;
        if (wantsGp) {
            int numericId = n.hasNonNull("numericId") ? n.get("numericId").asInt()
                    : gp != null && gp.hasNonNull("numericId") ? gp.get("numericId").asInt()
                    : NumericIdValidator.suggestNextFreeId(domain);
            gameplay = gameplay.withGameProperties(texture
                    ? spriteGameProperties(gp, numericId)
                    : gameProperties(gp, type == SBOFormat.ObjectType.BLOCK, numericId));
            params.setRendering(rendering(gp, texture ? SPRITE_RENDERING : SBOFormat.Rendering.DEFAULT));
        }

        String sourceKey = texture ? "omt" : "omo";
        JsonNode states = n.get("states");
        if (states != null && states.isArray() && states.size() > 0) {
            List<SBOFormat.StateSpec> specs = new ArrayList<>();
            for (JsonNode s : states) {
                String stateName = text(s, "name", "");
                if (texture && s.hasNonNull("omo")) {
                    throw new IllegalArgumentException("invalid_params: state '" + stateName
                            + "' — texture-only states take omt, not omo");
                }
                if (texture && s.hasNonNull("clip")) {
                    throw new IllegalArgumentException("invalid_params: state '" + stateName
                            + "' — texture-only SBOs cannot carry animation clips");
                }
                String source = s.hasNonNull(sourceKey) ? files.apply(s.get(sourceKey).asText()).toString()
                        : defaultSource != null ? defaultSource.toString() : "";
                String clip = s.hasNonNull("clip") ? files.apply(s.get("clip").asText()).toString() : null;
                SBOFormat.LoopMode loop = loopMode(text(s, "loop", null));
                specs.add(new SBOFormat.StateSpec(stateName, source, clip, loop));
            }
            params.setStatesEnabled(true);
            params.setStates(specs);
            params.setDefaultStateName(text(n, "defaultState", specs.get(0).name()));
        }

        params.setSounds(sounds(n.get("sounds"), files));
        if (n.has("drops")) {
            gameplay = gameplay.withDrops(drops(n.get("drops")));
        }
        if (n.has("tool")) {
            gameplay = gameplay.withTool(tool(n.get("tool")));
        }
        if (n.has("weapon")) {
            gameplay = gameplay.withWeapon(weapon(n.get("weapon")));
        }
        gameplay.applyTo(params); // the stonebreak:gameplay extension (#383)
        return params;
    }

    /** Texture-only (sprite item) rendering: alpha-tested, see-through. */
    public static final SBOFormat.Rendering SPRITE_RENDERING = new SBOFormat.Rendering("CUTOUT", true);

    /**
     * The SBO's core rendering from an agent's {@code gameProperties} object: its {@code renderLayer}
     * and {@code transparent} keys (kept there in the tool API although the format now stores them
     * as {@code rendering}, #383) over {@code base}.
     */
    public static SBOFormat.Rendering rendering(JsonNode g, SBOFormat.Rendering base) {
        if (g == null || g.isNull()) {
            return base;
        }
        return new SBOFormat.Rendering(text(g, "renderLayer", base.renderLayer()),
                g.path("transparent").asBoolean(base.transparent()));
    }

    /** Merge a partial {@code gameProperties} object over the model export defaults. */
    public static SboGameplay.GameProperties gameProperties(JsonNode gp, boolean isBlock, int numericId) {
        JsonNode g = gp == null || gp.isNull() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : gp;
        int atlasX = g.path("atlasX").asInt(Integer.MIN_VALUE);
        int atlasY = g.path("atlasY").asInt(Integer.MIN_VALUE);
        if (atlasX == Integer.MIN_VALUE || atlasY == Integer.MIN_VALUE) {
            int[] slot = isBlock ? AtlasSlots.findFreeAtlasSlot() : new int[]{-1, -1};
            if (atlasX == Integer.MIN_VALUE) atlasX = slot[0];
            if (atlasY == Integer.MIN_VALUE) atlasY = slot[1];
        }
        return new SboGameplay.GameProperties(
                numericId,
                (float) g.path("hardness").asDouble(1.0),
                g.path("solid").asBoolean(isBlock),
                g.path("breakable").asBoolean(true),
                atlasX, atlasY,
                g.path("flower").asBoolean(false),
                g.path("stackable").asBoolean(true),
                g.path("maxStackSize").asInt(64),
                text(g, "category", isBlock ? "BLOCKS" : "MATERIALS").toUpperCase(Locale.ROOT),
                g.path("placeable").asBoolean(isBlock),
                text(g, "material", null),
                g.has("requiredTier") ? tier(g.get("requiredTier"), "gameProperties.requiredTier") : 0);
    }

    /**
     * Merge a partial {@code gameProperties} object over the texture-only
     * (sprite item) defaults: not solid, breakable, no atlas tile, max stack 64,
     * TOOLS category, not placeable (the rendering is {@link #SPRITE_RENDERING}).
     * These are the values the SBT-to-SBO migration emits for sprite items.
     */
    public static SboGameplay.GameProperties spriteGameProperties(JsonNode gp, int numericId) {
        SboGameplay.GameProperties defaults = new SboGameplay.GameProperties(
                numericId,
                /* hardness    */ 0.0f,
                /* solid       */ false,
                /* breakable   */ true,
                /* atlasX      */ -1,
                /* atlasY      */ -1,
                /* flower      */ false,
                /* stackable   */ false,
                /* maxStack    */ 64,
                /* category    */ "TOOLS",
                /* placeable   */ false,
                /* material    */ null,
                /* reqTier     */ 0);
        return gp == null || gp.isNull() ? defaults : patchGameProperties(defaults, gp);
    }

    /** Merge a partial {@code gameProperties} patch over an existing record. */
    static SboGameplay.GameProperties patchGameProperties(SboGameplay.GameProperties base, JsonNode g) {
        return new SboGameplay.GameProperties(
                g.path("numericId").asInt(base.numericId()),
                (float) g.path("hardness").asDouble(base.hardness()),
                g.path("solid").asBoolean(base.solid()),
                g.path("breakable").asBoolean(base.breakable()),
                g.path("atlasX").asInt(base.atlasX()),
                g.path("atlasY").asInt(base.atlasY()),
                g.path("flower").asBoolean(base.flower()),
                g.path("stackable").asBoolean(base.stackable()),
                g.path("maxStackSize").asInt(base.maxStackSize()),
                text(g, "category", base.categoryOrDefault()).toUpperCase(Locale.ROOT),
                g.path("placeable").asBoolean(base.placeable()),
                g.has("material") ? text(g, "material", null) : base.material(),
                g.has("requiredTier") ? tier(g.get("requiredTier"), "gameProperties.requiredTier")
                        : base.requiredTier());
    }

    public static SboGameplay.DropData drops(JsonNode d) {
        if (d == null || d.isNull()) {
            return null;
        }
        List<SboGameplay.DropEntry> entries = new ArrayList<>();
        List<SboGameplay.ToolDropOverride> overrides = new ArrayList<>();
        JsonNode list = d.isArray() ? d : d.get("drops");
        if (list != null) {
            for (JsonNode e : list) {
                entries.add(dropEntry(e));
            }
        }
        JsonNode tools = d.isArray() ? null : d.get("toolOverrides");
        if (tools != null) {
            for (JsonNode t : tools) {
                List<SboGameplay.DropEntry> te = new ArrayList<>();
                for (JsonNode e : t.path("drops")) {
                    te.add(dropEntry(e));
                }
                overrides.add(new SboGameplay.ToolDropOverride(
                        text(t, "tool", text(t, "toolObjectId", "")), te));
            }
        }
        return new SboGameplay.DropData(entries, overrides);
    }

    /**
     * Mining-tool section (SBO 1.9): {@code {toolClass, tier, speedMultiplier,
     * materials[], durability?, attackDamage?}}; {@code null} = not a tool.
     */
    public static SboGameplay.ToolData tool(JsonNode t) {
        if (t == null || t.isNull()) {
            return null;
        }
        List<String> materials = new ArrayList<>();
        for (JsonNode m : t.path("materials")) {
            materials.add(m.asText());
        }
        try {
            return new SboGameplay.ToolData(
                    text(t, "toolClass", ""),
                    t.has("tier") ? tier(t.get("tier"), "tool.tier") : 0,
                    (float) t.path("speedMultiplier").asDouble(1.0),
                    materials,
                    t.hasNonNull("durability") ? t.get("durability").asInt() : null,
                    t.hasNonNull("attackDamage") ? (float) t.get("attackDamage").asDouble() : null);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid_params: tool — " + ex.getMessage(), ex);
        }
    }

    /**
     * Melee-weapon section (SBO 1.10): {@code {group, damageType, damage,
     * swingSeconds}}; {@code null} = not a weapon.
     */
    public static SboGameplay.WeaponData weapon(JsonNode w) {
        if (w == null || w.isNull()) {
            return null;
        }
        try {
            return new SboGameplay.WeaponData(
                    text(w, "group", ""),
                    text(w, "damageType", ""),
                    (float) w.path("damage").asDouble(-1),
                    (float) w.path("swingSeconds").asDouble(0));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid_params: weapon — " + ex.getMessage(), ex);
        }
    }

    /** A tier as an int or a tier name ({@code wood|stone|iron|diamond}). */
    private static int tier(JsonNode v, String where) {
        int tier = v.isNumber() ? v.asInt() : ToolMiningRules.parseTier(v.asText());
        if (tier < 0) {
            throw new IllegalArgumentException("invalid_params: " + where + " '" + v.asText()
                    + "' — use an int >= 0 or one of " + ToolMiningRules.TIER_NAMES);
        }
        return tier;
    }

    private static SboGameplay.DropEntry dropEntry(JsonNode e) {
        String id = text(e, "objectId", text(e, "item", ""));
        int min = e.path("min").asInt(e.path("minCount").asInt(1));
        int max = e.path("max").asInt(e.path("maxCount").asInt(min));
        return new SboGameplay.DropEntry(id, min, max, (float) e.path("chance").asDouble(1.0));
    }

    public static List<SoundSpec> sounds(JsonNode arr, Function<String, Path> files) {
        List<SoundSpec> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode s : arr) {
            String source = s.hasNonNull("file") ? files.apply(s.get("file").asText()).toString() : null;
            String resource = text(s, "resource", text(s, "resourcePath", null));
            out.add(new SoundSpec(text(s, "event", ""), source, resource,
                    (float) s.path("volume").asDouble(1.0),
                    (float) s.path("pitchMin").asDouble(1.0),
                    (float) s.path("pitchMax").asDouble(1.0),
                    s.path("variation").asBoolean(false)));
        }
        return out;
    }

    static SBOFormat.LoopMode loopMode(String s) {
        if (s == null || s.isBlank()) {
            return SBOFormat.LoopMode.CLIP_DEFAULT;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "loop", "looping" -> SBOFormat.LoopMode.LOOP;
            case "once", "one_shot", "oneshot" -> SBOFormat.LoopMode.ONCE;
            default -> SBOFormat.LoopMode.CLIP_DEFAULT;
        };
    }

    // ================================================================ SBE

    public static SBEFormat.ExportParameters sbe(JsonNode p, String defaultName,
                                                 Function<String, Path> files) {
        JsonNode n = p == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : p;
        SBEFormat.ExportParameters params = new SBEFormat.ExportParameters();
        String name = text(n, "objectName", cleanName(defaultName));
        params.setObjectName(name);
        params.setObjectId(text(n, "objectId", GameTargets.shared().qualify(slug(name))));
        params.setEntityType(SbeEntityKind.fromId(text(n, "entityType", "mob")).id());
        params.setObjectPack(text(n, "objectPack", "default"));
        params.setAuthor(text(n, "author", defaultAuthor()));
        params.setDescription(text(n, "description", ""));
        JsonNode states = n.get("states");
        if (states != null) {
            for (JsonNode s : states) {
                Path model = s.hasNonNull("model") ? files.apply(s.get("model").asText()) : null;
                Path clip = s.hasNonNull("clip") ? files.apply(s.get("clip").asText()) : null;
                params.addState(text(s, "name", ""), model, clip);
            }
        }
        JsonNode variants = n.get("variants");
        if (variants != null) {
            for (JsonNode v : variants) {
                Path model = v.hasNonNull("model") ? files.apply(v.get("model").asText()) : null;
                params.addVariant(text(v, "name", ""), model);
            }
        }
        params.setSounds(sounds(n.get("sounds"), files));
        return params;
    }

    /** Blank / duplicate state or variant names, mirroring the export window. */
    public static String validateSbeBindings(SBEFormat.ExportParameters params) {
        Set<String> seen = new HashSet<>();
        for (SBEFormat.StateBinding b : params.getStates()) {
            if (b.name().isBlank()) return "State name cannot be blank";
            if (!seen.add(b.name())) return "Duplicate state: '" + b.name() + "'";
        }
        seen.clear();
        for (SBEFormat.VariantBinding v : params.getVariants()) {
            if (v.name().isBlank()) return "Variant name cannot be blank";
            if (!seen.add(v.name())) return "Duplicate variant: '" + v.name() + "'";
        }
        return null;
    }

    // ============================================================ describe

    public static Map<String, Object> describe(SBOFormat.Document d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", d.version());
        out.put("objectId", d.objectId());
        out.put("objectName", d.objectName());
        out.put("objectType", d.objectType());
        out.put("objectPack", d.objectPack());
        out.put("author", d.author());
        out.put("description", d.description());
        out.put("createdAt", d.createdAt());
        out.put("omoFilename", d.omoFilename());
        out.put("textureFilename", d.textureFilename());
        SboGameplay g0 = SboGameplay.of(d);
        SboGameplay.GameProperties gp = g0.gameProperties();
        if (gp != null) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("numericId", gp.numericId());
            g.put("hardness", gp.hardness());
            g.put("solid", gp.solid());
            g.put("breakable", gp.breakable());
            g.put("atlasX", gp.atlasX());
            g.put("atlasY", gp.atlasY());
            g.put("renderLayer", d.rendering().renderLayer());
            g.put("transparent", d.rendering().transparent());
            g.put("flower", gp.flower());
            g.put("stackable", gp.stackable());
            g.put("maxStackSize", gp.maxStackSize());
            g.put("category", gp.categoryOrDefault());
            g.put("placeable", gp.placeable());
            g.put("material", gp.material());
            g.put("requiredTier", gp.requiredTier());
            out.put("gameProperties", g);
        } else {
            out.put("gameProperties", null);
        }
        List<Map<String, Object>> states = new ArrayList<>();
        for (SBOFormat.StateEntry s : d.states()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("filename", s.filename());
            m.put("model", s.model());
            m.put("animation", animation(s.animation() == null ? null : new Object[]{
                    s.animation().clipName(), s.animation().duration(), s.animation().fps(),
                    s.animation().loop(), s.animation().filename()}));
            states.add(m);
        }
        out.put("states", states);
        out.put("defaultStateName", d.defaultStateName());
        out.put("recipeCount", g0.recipes() == null ? 0 : g0.recipes().shaped().size());
        out.put("smeltingRecipeCount", g0.smeltingRecipes() == null ? 0 : g0.smeltingRecipes().recipes().size());
        out.put("fuelBurnTicks", g0.fuel() == null ? null : g0.fuel().burnTicks());
        out.put("sounds", sounds(d.sounds()));
        out.put("fx", fx(d.fx()));
        out.put("drops", describeDrops(g0.drops()));
        out.put("tool", describeTool(g0.tool()));
        out.put("weapon", describeWeapon(g0.weapon()));
        out.put("rendering", Map.of("renderLayer", d.rendering().renderLayer(),
                "transparent", d.rendering().transparent()));
        out.put("extensions", List.copyOf(d.extensions().keys()));
        return out;
    }

    public static Map<String, Object> describe(SBEFormat.Document d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", d.version());
        out.put("objectId", d.objectId());
        out.put("objectName", d.objectName());
        out.put("entityType", d.entityType());
        out.put("objectPack", d.objectPack());
        out.put("author", d.author());
        out.put("description", d.description());
        out.put("createdAt", d.createdAt());
        out.put("omoFilename", d.omoFilename());
        List<Map<String, Object>> states = new ArrayList<>();
        for (SBEFormat.StateEntry s : d.states()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("modelOverride", s.modelOverride() == null ? null : s.modelOverride().filename());
            m.put("animation", animation(s.animation() == null ? null : new Object[]{
                    s.animation().clipName(), s.animation().duration(), s.animation().fps(),
                    s.animation().loop(), s.animation().filename()}));
            states.add(m);
        }
        out.put("states", states);
        List<Map<String, Object>> variants = new ArrayList<>();
        for (SBEFormat.VariantEntry v : d.variants()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", v.name());
            m.put("modelOverride", v.modelOverride() == null ? null : v.modelOverride().filename());
            variants.add(m);
        }
        out.put("variants", variants);
        out.put("sounds", sounds(d.sounds()));
        out.put("fx", fx(d.fx()));
        return out;
    }

    private static Map<String, Object> animation(Object[] a) {
        if (a == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clipName", a[0]);
        m.put("duration", a[1]);
        m.put("fps", a[2]);
        m.put("loop", a[3]);
        m.put("filename", a[4]);
        return m;
    }

    private static List<Map<String, Object>> sounds(SoundData data) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (SoundDef s : data.sounds()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", s.event());
            m.put("filename", s.filename());
            m.put("resourcePath", s.resourcePath());
            m.put("volume", s.volume());
            m.put("pitchMin", s.pitchMin());
            m.put("pitchMax", s.pitchMax());
            m.put("variation", s.variation());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> describeTool(SboGameplay.ToolData tool) {
        if (tool == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolClass", tool.toolClass());
        out.put("tier", tool.tier());
        out.put("speedMultiplier", tool.speedMultiplier());
        out.put("materials", tool.materials());
        out.put("durability", tool.durability());
        out.put("attackDamage", tool.attackDamage());
        return out;
    }

    private static Map<String, Object> describeWeapon(SboGameplay.WeaponData weapon) {
        if (weapon == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", weapon.group());
        out.put("damageType", weapon.damageType());
        out.put("damage", weapon.damage());
        out.put("swingSeconds", weapon.swingSeconds());
        return out;
    }

    private static Map<String, Object> describeDrops(SboGameplay.DropData drops) {
        if (drops == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drops", dropRows(drops.drops()));
        List<Map<String, Object>> tools = new ArrayList<>();
        for (SboGameplay.ToolDropOverride t : drops.toolOverrides()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", t.toolObjectId());
            m.put("drops", dropRows(t.drops()));
            tools.add(m);
        }
        out.put("toolOverrides", tools);
        return out;
    }

    private static List<Map<String, Object>> dropRows(List<SboGameplay.DropEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SboGameplay.DropEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectId", e.objectId());
            m.put("min", e.minCount());
            m.put("max", e.maxCount());
            m.put("chance", e.chance());
            rows.add(m);
        }
        return rows;
    }

    // =============================================================== patch

    /**
     * Apply a partial JSON patch to an SBO manifest. Patchable: objectId,
     * objectName, objectType, objectPack, author, description, gameProperties
     * (merged; {@code null} removes), defaultStateName, fuel ({burnTicks} or
     * null), sounds (replaced; {@code resource} or an already-embedded
     * {@code filename}), drops (replaced; null removes), tool and weapon
     * (each replaced; null removes). States, recipes and embedded bytes are untouched.
     */
    public static SBOFormat.Document patchSbo(SBOFormat.Document b, JsonNode p) {
        SboGameplay g = SboGameplay.of(b);
        SboGameplay.GameProperties gp = g.gameProperties();
        SBOFormat.Rendering rendering = b.rendering();
        if (p.has("gameProperties")) {
            JsonNode gpPatch = p.get("gameProperties");
            gp = gpPatch.isNull() ? null
                    : gp == null ? gameProperties(gpPatch, "block".equalsIgnoreCase(b.objectType()),
                    gpPatch.path("numericId").asInt(-1))
                    : patchGameProperties(gp, gpPatch);
            rendering = rendering(gpPatch, rendering);
        }
        if (p.hasNonNull("rendering")) {
            rendering = rendering(p.get("rendering"), rendering);
        }
        SboGameplay.FuelData fuel = g.fuel();
        if (p.has("fuel")) {
            JsonNode f = p.get("fuel");
            fuel = f.isNull() ? null : new SboGameplay.FuelData(Math.max(1,
                    f.isNumber() ? f.asInt() : f.path("burnTicks").asInt(1600)));
        }
        SoundData sounds = p.has("sounds") ? patchSounds(b.sounds(), p.get("sounds")) : b.sounds();
        g = g.withGameProperties(gp).withFuel(fuel)
                .withDrops(p.has("drops") ? drops(p.get("drops")) : g.drops())
                .withTool(p.has("tool") ? tool(p.get("tool")) : g.tool())
                .withWeapon(p.has("weapon") ? weapon(p.get("weapon")) : g.weapon());
        SBOFormat.Document patched = new SBOFormat.Document(
                b.version(),
                text(p, "objectId", b.objectId()),
                text(p, "objectName", b.objectName()),
                p.hasNonNull("objectType") ? SBOFormat.ObjectType.fromId(p.get("objectType").asText()).getId()
                        : b.objectType(),
                text(p, "objectPack", b.objectPack()),
                b.checksum(),
                text(p, "author", b.author()),
                p.has("description") ? (p.get("description").isNull() ? null : p.get("description").asText())
                        : b.description(),
                b.createdAt(), b.omoFilename(), b.textureFilename(),
                rendering, b.states(),
                text(p, "defaultStateName", b.defaultStateName()),
                sounds, patchFx(b.fx(), p), b.extensions());
        return g.applyTo(patched);
    }

    /** Same idea for SBE: objectId/Name, entityType, objectPack, author, description, sounds. */
    public static SBEFormat.Document patchSbe(SBEFormat.Document b, JsonNode p) {
        SoundData sounds = p.has("sounds") ? patchSounds(b.sounds(), p.get("sounds")) : b.sounds();
        return new SBEFormat.Document(
                b.version(),
                text(p, "objectId", b.objectId()),
                text(p, "objectName", b.objectName()),
                p.hasNonNull("entityType") ? SbeEntityKind.fromId(p.get("entityType").asText()).id()
                        : b.entityType(),
                text(p, "objectPack", b.objectPack()),
                b.checksum(),
                text(p, "author", b.author()),
                p.has("description") ? (p.get("description").isNull() ? null : p.get("description").asText())
                        : b.description(),
                b.createdAt(), b.omoFilename(), b.states(), b.variants(), sounds, patchFx(b.fx(), p), b.extensions());
    }

    /** The FX bindings (SBO 1.12 / SBE 1.6 {@code fx[]}) in their manifest wire shape, or null. */
    private static Object fx(com.openmason.engine.format.fx.FxData data) {
        return data == null || data.isEmpty() ? null : com.openmason.engine.format.fx.FxJson.toJson(data);
    }

    /** {@code fx} replaces the bindings wholesale (manifest wire shape); null/[] removes them; absent keeps. */
    private static com.openmason.engine.format.fx.FxData patchFx(com.openmason.engine.format.fx.FxData base,
                                                                JsonNode p) {
        if (!p.has("fx")) {
            return base;
        }
        com.openmason.engine.format.fx.FxData next = com.openmason.engine.format.fx.FxJson.read(p);
        return next == null || next.isEmpty() ? null : next;
    }

    private static SoundData patchSounds(SoundData base, JsonNode arr) {
        if (arr == null || arr.isNull()) {
            return null;
        }
        Map<String, SoundDef> byFile = new LinkedHashMap<>();
        if (base != null) {
            for (SoundDef d : base.sounds()) {
                if (d.filename() != null) {
                    byFile.put(d.filename(), d);
                }
            }
        }
        List<SoundDef> defs = new ArrayList<>();
        for (JsonNode s : arr) {
            String filename = text(s, "filename", null);
            String resource = text(s, "resource", text(s, "resourcePath", null));
            SoundDef prior = filename != null ? byFile.get(filename) : null;
            if (filename != null && prior == null) {
                throw new IllegalArgumentException("sound '" + filename + "' is not embedded in this "
                        + "asset — reference an existing filename or use resource:<classpath>");
            }
            defs.add(new SoundDef(text(s, "event", ""), filename,
                    prior != null ? prior.checksum() : null, resource,
                    (float) s.path("volume").asDouble(1.0),
                    (float) s.path("pitchMin").asDouble(1.0),
                    (float) s.path("pitchMax").asDouble(1.0),
                    s.path("variation").asBoolean(false)));
        }
        return new SoundData(defs);
    }

    // ============================================================= helpers

    static String text(JsonNode n, String key, String fallback) {
        if (n == null) {
            return fallback;
        }
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? fallback : s;
    }

    static String cleanName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Object";
        }
        String file = Path.of(raw).getFileName().toString();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String defaultAuthor() {
        String user = System.getProperty("user.name", "");
        return user.isBlank() ? "Open Mason" : user;
    }
}
