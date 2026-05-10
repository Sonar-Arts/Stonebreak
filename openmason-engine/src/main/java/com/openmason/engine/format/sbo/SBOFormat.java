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
 * </ul>
 */
public final class SBOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.4";

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
     * Per-state asset descriptor (1.3+).
     *
     * @param name     unique state name within this SBO (e.g. "empty", "water", "milk")
     * @param filename ZIP entry path for this state's bytes
     *                 ({@code states/<name>/model.omo} or {@code states/<name>/texture.omt})
     * @param model    {@code true} if the embedded asset is an OMO,
     *                 {@code false} if it is an OMT
     * @param checksum SHA-256 of the embedded asset bytes
     */
    public record StateEntry(
            String name,
            String filename,
            boolean model,
            String checksum
    ) {
        public StateEntry {
            Objects.requireNonNull(name, "state name cannot be null");
            Objects.requireNonNull(filename, "state filename cannot be null");
            Objects.requireNonNull(checksum, "state checksum cannot be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("state name cannot be blank");
            }
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
            RecipeData recipes
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

        /** True when this SBO declares one or more crafting recipes (1.4+). */
        public boolean hasRecipes() {
            return recipes != null && recipes.shaped() != null && !recipes.shaped().isEmpty();
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
     * One named state's source asset, supplied by the export UI (1.3+).
     *
     * @param name       state name (e.g. "empty", "water", "milk")
     * @param sourcePath absolute path to the OMO or OMT file to embed for this state
     */
    public record StateSpec(String name, String sourcePath) {
        public StateSpec {
            Objects.requireNonNull(name, "state name cannot be null");
            Objects.requireNonNull(sourcePath, "state sourcePath cannot be null");
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
                if (states.size() < 2) return "At least 2 states are required when states are enabled";
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
