package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.format.sound.SoundSpec;
import com.openmason.main.systems.menus.dialogs.SBOExportWindow;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;

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
        return type != SBOFormat.ObjectType.BLOCK && type != SBOFormat.ObjectType.ENTITY;
    }

    private static SBOFormat.ExportParameters sbo(JsonNode p, Path defaultSource, String defaultName,
                                                  Function<String, Path> files, boolean texture) {
        JsonNode n = p == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : p;
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        String name = text(n, "objectName", cleanName(defaultName));
        params.setObjectName(name);
        params.setObjectId(text(n, "objectId", "stonebreak:" + slug(name)));
        SBOFormat.ObjectType type = SBOFormat.ObjectType.fromId(text(n, "objectType", texture ? "item" : "block"));
        if (texture && !textureObjectTypeAllowed(type)) {
            throw new IllegalArgumentException("invalid_params: objectType " + type.getId()
                    + " needs a model — a texture-only SBO can be item|decoration|particle|other");
        }
        params.setObjectType(type);
        params.setObjectPack(text(n, "objectPack", "default"));
        params.setAuthor(text(n, "author", defaultAuthor()));
        params.setDescription(text(n, "description", ""));

        NumericIdValidator.Domain domain = NumericIdValidator.domainFor(type.getId());
        JsonNode gp = n.get("gameProperties");
        boolean wantsGp = (gp != null && !gp.isNull()) || n.hasNonNull("numericId")
                || domain != NumericIdValidator.Domain.NONE;
        if (wantsGp) {
            int numericId = n.hasNonNull("numericId") ? n.get("numericId").asInt()
                    : gp != null && gp.hasNonNull("numericId") ? gp.get("numericId").asInt()
                    : NumericIdValidator.suggestNextFreeId(domain);
            params.setGameProperties(texture
                    ? spriteGameProperties(gp, numericId)
                    : gameProperties(gp, type == SBOFormat.ObjectType.BLOCK, numericId));
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
            params.setDrops(drops(n.get("drops")));
        }
        return params;
    }

    /** Merge a partial {@code gameProperties} object over the model export defaults. */
    public static SBOFormat.GameProperties gameProperties(JsonNode gp, boolean isBlock, int numericId) {
        JsonNode g = gp == null || gp.isNull() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : gp;
        int atlasX = g.path("atlasX").asInt(Integer.MIN_VALUE);
        int atlasY = g.path("atlasY").asInt(Integer.MIN_VALUE);
        if (atlasX == Integer.MIN_VALUE || atlasY == Integer.MIN_VALUE) {
            int[] slot = isBlock ? SBOExportWindow.findFreeAtlasSlot() : new int[]{-1, -1};
            if (atlasX == Integer.MIN_VALUE) atlasX = slot[0];
            if (atlasY == Integer.MIN_VALUE) atlasY = slot[1];
        }
        return new SBOFormat.GameProperties(
                numericId,
                (float) g.path("hardness").asDouble(1.0),
                g.path("solid").asBoolean(isBlock),
                g.path("breakable").asBoolean(true),
                atlasX, atlasY,
                text(g, "renderLayer", "OPAQUE").toUpperCase(Locale.ROOT),
                g.path("transparent").asBoolean(false),
                g.path("flower").asBoolean(false),
                g.path("stackable").asBoolean(true),
                g.path("maxStackSize").asInt(64),
                text(g, "category", isBlock ? "BLOCKS" : "MATERIALS").toUpperCase(Locale.ROOT),
                g.path("placeable").asBoolean(isBlock));
    }

    /**
     * Merge a partial {@code gameProperties} object over the texture-only
     * (sprite item) defaults: not solid, breakable, no atlas tile, CUTOUT layer
     * with transparency, max stack 64, TOOLS category, not placeable. These are
     * the values the SBT-to-SBO migration emits for sprite items.
     */
    public static SBOFormat.GameProperties spriteGameProperties(JsonNode gp, int numericId) {
        SBOFormat.GameProperties defaults = new SBOFormat.GameProperties(
                numericId,
                /* hardness    */ 0.0f,
                /* solid       */ false,
                /* breakable   */ true,
                /* atlasX      */ -1,
                /* atlasY      */ -1,
                /* renderLayer */ "CUTOUT",
                /* transparent */ true,
                /* flower      */ false,
                /* stackable   */ false,
                /* maxStack    */ 64,
                /* category    */ "TOOLS",
                /* placeable   */ false);
        return gp == null || gp.isNull() ? defaults : patchGameProperties(defaults, gp);
    }

    /** Merge a partial {@code gameProperties} patch over an existing record. */
    static SBOFormat.GameProperties patchGameProperties(SBOFormat.GameProperties base, JsonNode g) {
        return new SBOFormat.GameProperties(
                g.path("numericId").asInt(base.numericId()),
                (float) g.path("hardness").asDouble(base.hardness()),
                g.path("solid").asBoolean(base.solid()),
                g.path("breakable").asBoolean(base.breakable()),
                g.path("atlasX").asInt(base.atlasX()),
                g.path("atlasY").asInt(base.atlasY()),
                text(g, "renderLayer", base.renderLayerOrDefault()).toUpperCase(Locale.ROOT),
                g.path("transparent").asBoolean(base.transparent()),
                g.path("flower").asBoolean(base.flower()),
                g.path("stackable").asBoolean(base.stackable()),
                g.path("maxStackSize").asInt(base.maxStackSize()),
                text(g, "category", base.categoryOrDefault()).toUpperCase(Locale.ROOT),
                g.path("placeable").asBoolean(base.placeable()));
    }

    public static SBOFormat.DropData drops(JsonNode d) {
        if (d == null || d.isNull()) {
            return null;
        }
        List<SBOFormat.DropEntry> entries = new ArrayList<>();
        List<SBOFormat.ToolDropOverride> overrides = new ArrayList<>();
        JsonNode list = d.isArray() ? d : d.get("drops");
        if (list != null) {
            for (JsonNode e : list) {
                entries.add(dropEntry(e));
            }
        }
        JsonNode tools = d.isArray() ? null : d.get("toolOverrides");
        if (tools != null) {
            for (JsonNode t : tools) {
                List<SBOFormat.DropEntry> te = new ArrayList<>();
                for (JsonNode e : t.path("drops")) {
                    te.add(dropEntry(e));
                }
                overrides.add(new SBOFormat.ToolDropOverride(
                        text(t, "tool", text(t, "toolObjectId", "")), te));
            }
        }
        return new SBOFormat.DropData(entries, overrides);
    }

    private static SBOFormat.DropEntry dropEntry(JsonNode e) {
        String id = text(e, "objectId", text(e, "item", ""));
        int min = e.path("min").asInt(e.path("minCount").asInt(1));
        int max = e.path("max").asInt(e.path("maxCount").asInt(min));
        return new SBOFormat.DropEntry(id, min, max, (float) e.path("chance").asDouble(1.0));
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
        params.setObjectId(text(n, "objectId", "stonebreak:" + slug(name)));
        params.setEntityType(SBEFormat.EntityType.fromId(text(n, "entityType", "mob")));
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
        SBOFormat.GameProperties gp = d.gameProperties();
        if (gp != null) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("numericId", gp.numericId());
            g.put("hardness", gp.hardness());
            g.put("solid", gp.solid());
            g.put("breakable", gp.breakable());
            g.put("atlasX", gp.atlasX());
            g.put("atlasY", gp.atlasY());
            g.put("renderLayer", gp.renderLayerOrDefault());
            g.put("transparent", gp.transparent());
            g.put("flower", gp.flower());
            g.put("stackable", gp.stackable());
            g.put("maxStackSize", gp.maxStackSize());
            g.put("category", gp.categoryOrDefault());
            g.put("placeable", gp.placeable());
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
        out.put("recipeCount", d.recipes() == null ? 0 : d.recipes().shaped().size());
        out.put("smeltingRecipeCount", d.smeltingRecipes() == null ? 0 : d.smeltingRecipes().recipes().size());
        out.put("fuelBurnTicks", d.fuel() == null ? null : d.fuel().burnTicks());
        out.put("sounds", sounds(d.sounds()));
        out.put("drops", describeDrops(d.drops()));
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

    private static Map<String, Object> describeDrops(SBOFormat.DropData drops) {
        if (drops == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drops", dropRows(drops.drops()));
        List<Map<String, Object>> tools = new ArrayList<>();
        for (SBOFormat.ToolDropOverride t : drops.toolOverrides()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", t.toolObjectId());
            m.put("drops", dropRows(t.drops()));
            tools.add(m);
        }
        out.put("toolOverrides", tools);
        return out;
    }

    private static List<Map<String, Object>> dropRows(List<SBOFormat.DropEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SBOFormat.DropEntry e : entries) {
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
     * {@code filename}), drops (replaced; null removes). States, recipes and
     * embedded bytes are untouched.
     */
    public static SBOFormat.Document patchSbo(SBOFormat.Document b, JsonNode p) {
        SBOFormat.GameProperties gp = b.gameProperties();
        if (p.has("gameProperties")) {
            JsonNode g = p.get("gameProperties");
            gp = g.isNull() ? null
                    : gp == null ? gameProperties(g, "block".equalsIgnoreCase(b.objectType()),
                    g.path("numericId").asInt(-1))
                    : patchGameProperties(gp, g);
        }
        SBOFormat.FuelData fuel = b.fuel();
        if (p.has("fuel")) {
            JsonNode f = p.get("fuel");
            fuel = f.isNull() ? null : new SBOFormat.FuelData(Math.max(1,
                    f.isNumber() ? f.asInt() : f.path("burnTicks").asInt(1600)));
        }
        SoundData sounds = p.has("sounds") ? patchSounds(b.sounds(), p.get("sounds")) : b.sounds();
        SBOFormat.DropData drops = p.has("drops") ? drops(p.get("drops")) : b.drops();
        return new SBOFormat.Document(
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
                gp, b.states(),
                text(p, "defaultStateName", b.defaultStateName()),
                b.recipes(), b.smeltingRecipes(), fuel, sounds, drops);
    }

    /** Same idea for SBE: objectId/Name, entityType, objectPack, author, description, sounds. */
    public static SBEFormat.Document patchSbe(SBEFormat.Document b, JsonNode p) {
        SoundData sounds = p.has("sounds") ? patchSounds(b.sounds(), p.get("sounds")) : b.sounds();
        return new SBEFormat.Document(
                b.version(),
                text(p, "objectId", b.objectId()),
                text(p, "objectName", b.objectName()),
                p.hasNonNull("entityType") ? SBEFormat.EntityType.fromId(p.get("entityType").asText()).getId()
                        : b.entityType(),
                text(p, "objectPack", b.objectPack()),
                b.checksum(),
                text(p, "author", b.author()),
                p.has("description") ? (p.get("description").isNull() ? null : p.get("description").asText())
                        : b.description(),
                b.createdAt(), b.omoFilename(), b.states(), b.variants(), sounds);
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
