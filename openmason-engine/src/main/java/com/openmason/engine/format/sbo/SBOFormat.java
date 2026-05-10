package com.openmason.engine.format.sbo;

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
 * </ul>
 */
public final class SBOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.2";

    /** File extension for SBO files */
    public static final String FILE_EXTENSION = ".sbo";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Embedded OMO filename in ZIP archive (model-bearing SBOs). */
    public static final String EMBEDDED_OMO_FILENAME = "model.omo";

    /** Embedded OMT filename in ZIP archive (texture-only SBOs, 1.2+). */
    public static final String EMBEDDED_OMT_FILENAME = "texture.omt";

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
     * Complete SBO document structure.
     *
     * <p>Contains all metadata needed to identify, classify, verify,
     * and attribute a Stonebreak game object.
     *
     * @param version         format version string
     * @param objectId        unique identifier for this object within Stonebreak
     * @param objectName      human-readable display name
     * @param objectType      classification type (block, item, entity, etc.)
     * @param objectPack      pack/group identifier for organizing related objects
     * @param checksum        SHA-256 checksum of the embedded OMO file bytes
     * @param author          author attribution (creator name or studio)
     * @param description     optional description of the object
     * @param createdAt       ISO-8601 timestamp of creation
     * @param omoFilename     filename of the embedded OMO file in the ZIP
     *                        (model-bearing SBOs, e.g. blocks); null for
     *                        texture-only SBOs (1.2+)
     * @param textureFilename filename of the embedded OMT file in the ZIP
     *                        (texture-only SBOs, 1.2+); null for model-bearing SBOs
     * @param gameProperties  optional gameplay metadata (1.1+); null for legacy 1.0 files
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
            GameProperties gameProperties
    ) {
        public Document {
            Objects.requireNonNull(version, "version cannot be null");
            Objects.requireNonNull(objectId, "objectId cannot be null");
            Objects.requireNonNull(objectName, "objectName cannot be null");
            Objects.requireNonNull(objectType, "objectType cannot be null");
            Objects.requireNonNull(objectPack, "objectPack cannot be null");
            Objects.requireNonNull(checksum, "checksum cannot be null");
            Objects.requireNonNull(author, "author cannot be null");
            // Exactly one of omoFilename / textureFilename must be present.
            boolean hasOmo = omoFilename != null && !omoFilename.isBlank();
            boolean hasTexture = textureFilename != null && !textureFilename.isBlank();
            if (hasOmo == hasTexture) {
                throw new IllegalArgumentException(
                        "SBO must embed exactly one of omoFile or textureFile (got omo="
                                + omoFilename + ", texture=" + textureFilename + ")");
            }
            // description, createdAt, gameProperties may be null
        }

        /** True when this SBO carries an embedded OMO model. */
        public boolean isModelBearing() {
            return omoFilename != null && !omoFilename.isBlank();
        }

        /** True when this SBO carries an embedded OMT texture only (1.2+). */
        public boolean isTextureOnly() {
            return textureFilename != null && !textureFilename.isBlank();
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

        /**
         * Validates that all required fields are populated.
         *
         * @return true if the parameters are valid for export
         */
        public boolean isValid() {
            return !objectId.isBlank()
                    && !objectName.isBlank()
                    && !objectPack.isBlank()
                    && !author.isBlank();
        }

        /**
         * Returns the first validation error message, or empty string if valid.
         */
        public String getValidationError() {
            if (objectId.isBlank()) return "Object ID is required";
            if (objectName.isBlank()) return "Object Name is required";
            if (objectPack.isBlank()) return "Object Pack is required";
            if (author.isBlank()) return "Author is required";
            return "";
        }
    }
}
