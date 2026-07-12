package com.openmason.engine.format.sbo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Stonebreak Object (.SBO) file format specification.
 *
 * <p>SBO files are the game-ready export format for Open Mason assets.
 * They wrap an OMO model and its corresponding OMT texture data with
 * additional Stonebreak-specific metadata including object identity,
 * classification, integrity verification, and author attribution.
 *
 * <p>ZIP Structure:
 * <ul>
 *   <li>{@code manifest.json} - SBO metadata (identity, type, pack, author, checksum)</li>
 *   <li>{@code model.omo} - Embedded Open Mason Object file (contains texture data internally)</li>
 * </ul>
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with object identity, type, pack, checksum, and author signage</li>
 *   <li>1.1 - Added optional {@link GameProperties} block carrying gameplay metadata
 *       (numeric ID, hardness, solid/breakable flags, atlas coords, render layer,
 *       transparency, flower flag, stackable flag, max stack size, item category,
 *       placeable flag). Allows the game to populate its block/item registry directly
 *       from SBO files without an enforced enum.</li>
 *   <li>1.2 - Texture-only payload variant. Embedded asset is now optional OMO XOR
 *       optional OMT — manifest carries either {@code omoFile} (model-bearing SBO,
 *       e.g. blocks) or {@code textureFile} (texture-only SBO, e.g. sprite items).
 *       Exactly one must be present. Replaces the SBT-as-item flow.</li>
 *   <li>1.3 - Optional named states. Manifest may carry a {@code states[]} list,
 *       each entry mapping a state name to an embedded asset under
 *       {@code states/<name>/model.omo} or {@code states/<name>/texture.omt}.
 *       The default state's bytes are also written to the legacy
 *       {@code model.omo}/{@code texture.omt} entry so 1.2 readers still load.
 *       Used at runtime to switch between visual variants of one logical
 *       object (e.g. empty/water/milk wooden bucket).</li>
 *   <li>1.4 - Optional crafting {@link RecipeData} block. Each SBO may declare the
 *       shaped recipes that produce its own item, removing the need for a
 *       hardcoded recipe registry on the game side. Older readers ignore the
 *       field; the game scans all SBOs at startup and harvests recipes whose
 *       output is the SBO's own object.</li>
 *   <li>1.5 - Optional {@link SmeltingRecipeData} and {@link FuelData} blocks.
 *       The smelting block, like crafting, is owned by the recipe's output SBO
 *       (input is referenced by {@code objectId}). The fuel block describes the
 *       burn time of the SBO's own item when used as furnace fuel. Both fields
 *       are nullable; older readers ignore them.</li>
 *   <li>1.6 - Optional per-state animation clips (mirrors the SBE format).
 *       A state may embed a raw {@code .omanim} clip under
 *       {@code states/<name>/clip.omanim}; the manifest state entry gains an
 *       {@code animation} ref ({@link AnimationRef}: file, checksum, clipName,
 *       duration, fps, loop, requiredParts). The loop flag is resolved at
 *       export via {@link LoopMode} (clip default, forced loop, or play-once)
 *       so the game can play one-shot clips that hold their final pose (e.g.
 *       a door opening) or looping clips (e.g. a spinning fan). Model-bearing
 *       SBOs only; older readers ignore both the field and the ZIP entry.</li>
 *   <li>1.7 - Optional {@code sounds[]} block (shared wire shape with SBE 1.4,
 *       see {@link com.openmason.engine.format.sound.SoundData}). Each entry
 *       binds an event name — the standard block events {@code break} /
 *       {@code hit} / {@code place} / {@code step}, or any custom name — to an
 *       audio sample that is either embedded under {@code sounds/} or
 *       referenced by game classpath resource path (so many blocks can share
 *       one shipped sample), plus volume and a random pitch range. Multiple
 *       entries per event give playback variation. Older readers ignore the
 *       field and any {@code sounds/} entries.</li>
 * </ul>
 */
public final class SBOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.7";

    /** File extension for SBO files */
    public static final String FILE_EXTENSION = ".sbo";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Embedded OMO filename in ZIP archive (model-bearing SBOs). */
    public static final String EMBEDDED_OMO_FILENAME = "model.omo";

    /** Embedded OMT filename in ZIP archive (texture-only SBOs, 1.2+). */
    public static final String EMBEDDED_OMT_FILENAME = "texture.omt";

    /** Prefix for per-state embedded assets (1.3+): {@code states/<name>/model.omo|texture.omt}. */
    public static final String STATES_DIR_PREFIX = "states/";

    /** Per-state embedded animation clip filename (1.6+): {@code states/<name>/clip.omanim}. */
    public static final String STATE_CLIP_FILENAME = "clip.omanim";

    /** Prefix for embedded sound samples (1.7+): {@code sounds/<event>_<n>.<ext>}. */
    public static final String SOUNDS_DIR_PREFIX = "sounds/";

    /** Checksum algorithm used for integrity verification */
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    /** Private constructor - utility class */
    private SBOFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures a file path has the .sbo extension.
     *
     * @param filePath the file path to check
     * @return the file path with .sbo extension
     */
    public static String ensureExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return filePath;
        }

        String trimmed = filePath.trim();
        if (!trimmed.toLowerCase().endsWith(FILE_EXTENSION)) {
            return trimmed + FILE_EXTENSION;
        }
        return trimmed;
    }

    /**
     * Checks if a file path has the .sbo extension.
     *
     * @param filePath the file path to check
     * @return true if the path ends with .sbo
     */
    public static boolean hasSBOExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        return filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /** ZIP entry path for a state's embedded animation clip (1.6+). */
    public static String stateClipPath(String stateName) {
        return STATES_DIR_PREFIX + stateName + "/" + STATE_CLIP_FILENAME;
    }

    /**
     * ZIP entry path for an embedded sound sample (1.7+). {@code index}
     * disambiguates multiple samples on the same event; {@code extension}
     * preserves the source file's suffix (e.g. {@code "wav"}).
     */
    public static String soundEntryPath(String event, int index, String extension) {
        String ext = extension == null || extension.isBlank() ? "wav" : extension;
        return SOUNDS_DIR_PREFIX + event + "_" + index + "." + ext;
    }

    /**
     * Object type classification for Stonebreak assets.
     * Determines how the game engine interprets and renders the object.
     */
    public enum ObjectType {
        BLOCK("block"),
        ITEM("item"),
        ENTITY("entity"),
        DECORATION("decoration"),
        PARTICLE("particle"),
        OTHER("other");

        private final String id;

        ObjectType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        /**
         * Look up an ObjectType by its string identifier.
         *
         * @param id the type identifier
         * @return the matching ObjectType, or OTHER if not found
         */
        public static ObjectType fromId(String id) {
            if (id == null) return OTHER;
            for (ObjectType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
            return OTHER;
        }
    }

    /**
     * How a state clip's loop behaviour is resolved at export time (1.6+).
     *
     * <p>The {@code .omanim} clip carries its own loop flag; this mode lets
     * the export deliberately override it. {@code ONCE} clips play through a
     * single time and hold their final pose (via the runtime sampler's clamp
     * semantics) — the door open/close case. {@code LOOP} clips wrap forever.
     */
    public enum LoopMode {
        /** Use the loop flag stored inside the clip itself. */
        CLIP_DEFAULT,
        /** Force looping playback regardless of the clip's own flag. */
        LOOP,
        /** Force one-shot playback: play once, hold the final pose. */
        ONCE;

        /** Resolve to a concrete loop flag given the clip's own default. */
        public boolean resolve(boolean clipDefault) {
            return switch (this) {
                case LOOP -> true;
                case ONCE -> false;
                case CLIP_DEFAULT -> clipDefault;
            };
        }
    }

    /**
     * Reference to a state's embedded animation clip (1.6+). Mirrors the SBE
     * format's animation ref: a metadata snapshot of the raw {@code .omanim}
     * so the game can pick and schedule clips without unpacking every track.
     *
     * @param filename      ZIP entry path ({@code states/<name>/clip.omanim})
     * @param checksum      SHA-256 of the embedded clip bytes ("" for tool-side
     *                      stubs; the serializer recomputes it on save)
     * @param clipName      display name from the clip's own manifest (nullable)
     * @param duration      clip duration in seconds (snapshot)
     * @param fps           authored frames-per-second (snapshot)
     * @param loop          resolved loop flag: {@code true} = wrap forever,
     *                      {@code false} = play once and hold the final pose
     * @param requiredParts partIds the clip animates; used for model
     *                      compatibility checks (never null, may be empty)
     */
    public record AnimationRef(
            String filename,
            String checksum,
            String clipName,
            float duration,
            float fps,
            boolean loop,
            List<String> requiredParts
    ) {
        public AnimationRef {
            Objects.requireNonNull(filename, "animation filename cannot be null");
            Objects.requireNonNull(checksum, "animation checksum cannot be null");
            requiredParts = requiredParts == null ? List.of() : List.copyOf(requiredParts);
        }
    }

    /**
     * Per-state asset descriptor (1.3+).
     *
     * @param name      unique state name within this SBO (e.g. "empty", "water", "milk")
     * @param filename  ZIP entry path for this state's bytes
     *                  ({@code states/<name>/model.omo} or {@code states/<name>/texture.omt})
     * @param model     {@code true} if the embedded asset is an OMO,
     *                  {@code false} if it is an OMT
     * @param checksum  SHA-256 of the embedded asset bytes
     * @param animation optional embedded animation clip ref (1.6+); null when
     *                  the state is static. Model-bearing SBOs only.
     */
    public record StateEntry(
            String name,
            String filename,
            boolean model,
            String checksum,
            AnimationRef animation
    ) {
        public StateEntry {
            Objects.requireNonNull(name, "state name cannot be null");
            Objects.requireNonNull(filename, "state filename cannot be null");
            Objects.requireNonNull(checksum, "state checksum cannot be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("state name cannot be blank");
            }
        }

        /** Pre-1.6 convenience constructor: static state, no animation. */
        public StateEntry(String name, String filename, boolean model, String checksum) {
            this(name, filename, model, checksum, null);
        }

        /** True when this state embeds an animation clip (1.6+). */
        public boolean hasAnimation() {
            return animation != null;
        }
    }

    /**
     * Complete SBO document structure.
     *
     * <p>Contains all metadata needed to identify, classify, verify,
     * and attribute a Stonebreak game object.
     *
     * @param version          format version string
     * @param objectId         unique identifier for this object within Stonebreak
     * @param objectName       human-readable display name
     * @param objectType       classification type (block, item, entity, etc.)
     * @param objectPack       pack/group identifier for organizing related objects
     * @param checksum         SHA-256 checksum of the legacy/default embedded asset
     * @param author           author attribution (creator name or studio)
     * @param description      optional description of the object
     * @param createdAt        ISO-8601 timestamp of creation
     * @param omoFilename      filename of the legacy/default OMO entry in the ZIP
     *                         (model-bearing SBOs); null for texture-only SBOs.
     *                         When {@code states} is non-empty, this mirrors the
     *                         default state's asset for 1.2 reader back-compat.
     * @param textureFilename  filename of the legacy/default OMT entry in the ZIP
     *                         (texture-only SBOs); null for model-bearing SBOs.
     * @param gameProperties   optional gameplay metadata (1.1+); null for legacy 1.0 files
     * @param states           optional named states (1.3+). Empty list when the SBO
     *                         has no states (default-only behavior). When non-empty,
     *                         exactly one entry's name must equal {@code defaultStateName}.
     * @param defaultStateName name of the default state (1.3+); null when {@code states}
     *                         is empty.
     * @param recipes          optional crafting recipes (1.4+) whose output is this SBO's
     *                         own object. {@code null} or empty list means no recipes.
     * @param smeltingRecipes  optional smelting recipes (1.5+) whose output is this SBO's
     *                         own object. Each entry names the input via objectId.
     *                         {@code null} or empty list means no smelting recipes.
     * @param fuel             optional fuel descriptor (1.5+) declaring how long this
     *                         SBO's own item burns when used as furnace fuel.
     *                         {@code null} means the item is not a fuel.
     * @param sounds           optional sound bindings (1.7+): event name → audio
     *                         sample (embedded or resource-referenced).
     *                         {@code null} means the object declares no sounds.
     */
    public record Document(
            String version,
            String objectId,
            String objectName,
            String objectType,
            String objectPack,
            String checksum,
            String author,
            String description,
            String createdAt,
            String omoFilename,
            String textureFilename,
            GameProperties gameProperties,
            List<StateEntry> states,
            String defaultStateName,
            RecipeData recipes,
            SmeltingRecipeData smeltingRecipes,
            FuelData fuel,
            com.openmason.engine.format.sound.SoundData sounds
    ) {
        public Document {
            Objects.requireNonNull(version, "version cannot be null");
            Objects.requireNonNull(objectId, "objectId cannot be null");
            Objects.requireNonNull(objectName, "objectName cannot be null");
            Objects.requireNonNull(objectType, "objectType cannot be null");
            Objects.requireNonNull(objectPack, "objectPack cannot be null");
            Objects.requireNonNull(checksum, "checksum cannot be null");
            Objects.requireNonNull(author, "author cannot be null");
            boolean hasOmo = omoFilename != null && !omoFilename.isBlank();
            boolean hasTexture = textureFilename != null && !textureFilename.isBlank();
            if (hasOmo == hasTexture) {
                throw new IllegalArgumentException(
                        "SBO must embed exactly one of omoFile or textureFile (got omo="
                                + omoFilename + ", texture=" + textureFilename + ")");
            }
            states = states == null ? Collections.emptyList() : List.copyOf(states);
            if (!states.isEmpty()) {
                Objects.requireNonNull(defaultStateName, "defaultStateName required when states present");
                boolean defaultFound = false;
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (StateEntry e : states) {
                    if (!seen.add(e.name())) {
                        throw new IllegalArgumentException("duplicate state name: " + e.name());
                    }
                    if (e.name().equals(defaultStateName)) defaultFound = true;
                    if (e.model() != hasOmo) {
                        throw new IllegalArgumentException(
                                "state '" + e.name() + "' kind (model=" + e.model()
                                        + ") does not match SBO payload kind (model=" + hasOmo + ")");
                    }
                    if (e.hasAnimation() && !hasOmo) {
                        throw new IllegalArgumentException(
                                "state '" + e.name() + "' carries an animation clip but the SBO"
                                        + " is texture-only — clips require a model payload");
                    }
                }
                if (!defaultFound) {
                    throw new IllegalArgumentException(
                            "defaultStateName '" + defaultStateName + "' is not in states[]");
                }
            }
        }

        /** True when this SBO carries an embedded OMO model. */
        public boolean isModelBearing() {
            return omoFilename != null && !omoFilename.isBlank();
        }

        /** True when this SBO carries an embedded OMT texture only (1.2+). */
        public boolean isTextureOnly() {
            return textureFilename != null && !textureFilename.isBlank();
        }

        /** True when this SBO declares one or more named states (1.3+). */
        public boolean hasStates() {
            return states != null && !states.isEmpty();
        }

        /** True when any state embeds an animation clip (1.6+). */
        public boolean hasAnimations() {
            if (states == null) return false;
            for (StateEntry e : states) {
                if (e.hasAnimation()) return true;
            }
            return false;
        }

        /** True when this SBO declares one or more crafting recipes (1.4+). */
        public boolean hasRecipes() {
            return recipes != null && recipes.shaped() != null && !recipes.shaped().isEmpty();
        }

        /** True when this SBO declares one or more smelting recipes (1.5+). */
        public boolean hasSmeltingRecipes() {
            return smeltingRecipes != null
                    && smeltingRecipes.recipes() != null
                    && !smeltingRecipes.recipes().isEmpty();
        }

        /** True when this SBO declares fuel data (1.5+). */
        public boolean hasFuel() {
            return fuel != null && fuel.burnTicks() > 0;
        }

        /** True when this SBO declares one or more sound bindings (1.7+). */
        public boolean hasSounds() {
            return sounds != null && !sounds.isEmpty();
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
     * Optional gameplay metadata embedded in an SBO manifest (format version 1.1+).
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
     *   <li>{@code renderLayer} - "OPAQUE", "CUTOUT", or "TRANSLUCENT".</li>
     *   <li>{@code transparent} - whether neighbouring faces should still render
     *       through this block (e.g. glass, water, leaves).</li>
     *   <li>{@code flower} - whether water flow can break this block.</li>
     *   <li>{@code stackable} - whether the block supports layered stacking
     *       (e.g. snow).</li>
     *   <li>{@code maxStackSize} - inventory stack size (default 64 for blocks).</li>
     *   <li>{@code category} - item category for inventory grouping ("BLOCKS",
     *       "TOOLS", "MATERIALS", etc.).</li>
     *   <li>{@code placeable} - whether the item can be placed in the world as a
     *       block. True for all blocks; false for tool/material items.</li>
     * </ul>
     */
    public record GameProperties(
            int numericId,
            float hardness,
            boolean solid,
            boolean breakable,
            int atlasX,
            int atlasY,
            String renderLayer,
            boolean transparent,
            boolean flower,
            boolean stackable,
            int maxStackSize,
            String category,
            boolean placeable
    ) {
        public GameProperties {
            // renderLayer and category may be null; parser will default them.
        }

        /** Convenience: returns the render layer or "OPAQUE" if absent. */
        public String renderLayerOrDefault() {
            return renderLayer != null && !renderLayer.isBlank() ? renderLayer : "OPAQUE";
        }

        /** Convenience: returns the category or "BLOCKS" if absent. */
        public String categoryOrDefault() {
            return category != null && !category.isBlank() ? category : "BLOCKS";
        }
    }

    /**
     * One named state's source assets, supplied by the export UI (1.3+).
     *
     * @param name           state name (e.g. "empty", "water", "milk")
     * @param sourcePath     absolute path to the OMO or OMT file to embed for this state
     * @param clipSourcePath optional absolute path to a {@code .omanim} clip to
     *                       embed for this state (1.6+); null/blank = no clip
     * @param loopMode       how the clip's loop flag is resolved at export;
     *                       ignored when there is no clip
     */
    public record StateSpec(String name, String sourcePath, String clipSourcePath, LoopMode loopMode) {
        public StateSpec {
            Objects.requireNonNull(name, "state name cannot be null");
            Objects.requireNonNull(sourcePath, "state sourcePath cannot be null");
            loopMode = loopMode == null ? LoopMode.CLIP_DEFAULT : loopMode;
        }

        /** Pre-1.6 convenience constructor: static state, no clip. */
        public StateSpec(String name, String sourcePath) {
            this(name, sourcePath, null, LoopMode.CLIP_DEFAULT);
        }

        /** True when this state supplies an animation clip. */
        public boolean hasClip() {
            return clipSourcePath != null && !clipSourcePath.isBlank();
        }
    }

    /**
     * Builder for constructing SBO export parameters before serialization.
     * Collects user-provided metadata for the export window.
     */
    public static final class ExportParameters {
        private String objectId = "";
        private String objectName = "";
        private ObjectType objectType = ObjectType.BLOCK;
        private String objectPack = "default";
        private String author = "";
        private String description = "";
        private GameProperties gameProperties;
        private boolean statesEnabled = false;
        private final List<StateSpec> states = new ArrayList<>();
        private String defaultStateName = "";
        private RecipeData recipes;
        private SmeltingRecipeData smeltingRecipes;
        private FuelData fuel;
        private final List<com.openmason.engine.format.sound.SoundSpec> sounds = new ArrayList<>();

        public ExportParameters() {}

        public GameProperties getGameProperties() { return gameProperties; }
        public void setGameProperties(GameProperties gameProperties) { this.gameProperties = gameProperties; }

        public String getObjectId() { return objectId; }
        public void setObjectId(String objectId) { this.objectId = objectId != null ? objectId : ""; }

        public String getObjectName() { return objectName; }
        public void setObjectName(String objectName) { this.objectName = objectName != null ? objectName : ""; }

        public ObjectType getObjectType() { return objectType; }
        public void setObjectType(ObjectType objectType) { this.objectType = objectType != null ? objectType : ObjectType.BLOCK; }

        public String getObjectPack() { return objectPack; }
        public void setObjectPack(String objectPack) { this.objectPack = objectPack != null ? objectPack : ""; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author != null ? author : ""; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description != null ? description : ""; }

        public boolean isStatesEnabled() { return statesEnabled; }
        public void setStatesEnabled(boolean enabled) { this.statesEnabled = enabled; }

        public List<StateSpec> getStates() { return states; }
        public void setStates(List<StateSpec> newStates) {
            states.clear();
            if (newStates != null) states.addAll(newStates);
        }

        public String getDefaultStateName() { return defaultStateName; }
        public void setDefaultStateName(String name) { this.defaultStateName = name != null ? name : ""; }

        public RecipeData getRecipes() { return recipes; }
        public void setRecipes(RecipeData recipes) { this.recipes = recipes; }

        public SmeltingRecipeData getSmeltingRecipes() { return smeltingRecipes; }
        public void setSmeltingRecipes(SmeltingRecipeData smeltingRecipes) { this.smeltingRecipes = smeltingRecipes; }

        public FuelData getFuel() { return fuel; }
        public void setFuel(FuelData fuel) { this.fuel = fuel; }

        public List<com.openmason.engine.format.sound.SoundSpec> getSounds() { return sounds; }
        public void setSounds(List<com.openmason.engine.format.sound.SoundSpec> newSounds) {
            sounds.clear();
            if (newSounds != null) sounds.addAll(newSounds);
        }

        /**
         * Validates that all required fields are populated.
         *
         * @return true if the parameters are valid for export
         */
        public boolean isValid() {
            return getValidationError().isEmpty();
        }

        /**
         * Returns the first validation error message, or empty string if valid.
         */
        public String getValidationError() {
            if (objectId.isBlank()) return "Object ID is required";
            if (objectName.isBlank()) return "Object Name is required";
            if (objectPack.isBlank()) return "Object Pack is required";
            if (author.isBlank()) return "Author is required";
            if (statesEnabled) {
                boolean anyClip = false;
                for (StateSpec s : states) {
                    if (s.hasClip()) { anyClip = true; break; }
                }
                // A single state is meaningful when it carries a clip (an
                // always-animating block); static variants need at least two.
                if (states.size() < 2 && !(states.size() == 1 && anyClip)) {
                    return "At least 2 states are required when states are enabled"
                            + " (or 1 state with an animation clip)";
                }
                if (defaultStateName.isBlank()) return "A default state must be selected";
                java.util.Set<String> seen = new java.util.HashSet<>();
                boolean defaultFound = false;
                for (StateSpec s : states) {
                    if (s.name().isBlank()) return "State names cannot be blank";
                    if (!seen.add(s.name())) return "Duplicate state name: " + s.name();
                    if (s.sourcePath().isBlank()) return "State '" + s.name() + "' has no source file";
                    if (s.name().equals(defaultStateName)) defaultFound = true;
                }
                if (!defaultFound) return "Default state '" + defaultStateName + "' is not in the state list";
            }
            return "";
        }
    }
}
