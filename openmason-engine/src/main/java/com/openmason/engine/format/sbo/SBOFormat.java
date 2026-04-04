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
 * </ul>
 */
public final class SBOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.0";

    /** File extension for SBO files */
    public static final String FILE_EXTENSION = ".sbo";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Embedded OMO filename in ZIP archive */
    public static final String EMBEDDED_OMO_FILENAME = "model.omo";

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
     * @param version      format version string
     * @param objectId     unique identifier for this object within Stonebreak
     * @param objectName   human-readable display name
     * @param objectType   classification type (block, item, entity, etc.)
     * @param objectPack   pack/group identifier for organizing related objects
     * @param checksum     SHA-256 checksum of the embedded OMO file bytes
     * @param author       author attribution (creator name or studio)
     * @param description  optional description of the object
     * @param createdAt    ISO-8601 timestamp of creation
     * @param omoFilename  filename of the embedded OMO file in the ZIP
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
            String omoFilename
    ) {
        public Document {
            Objects.requireNonNull(version, "version cannot be null");
            Objects.requireNonNull(objectId, "objectId cannot be null");
            Objects.requireNonNull(objectName, "objectName cannot be null");
            Objects.requireNonNull(objectType, "objectType cannot be null");
            Objects.requireNonNull(objectPack, "objectPack cannot be null");
            Objects.requireNonNull(checksum, "checksum cannot be null");
            Objects.requireNonNull(author, "author cannot be null");
            Objects.requireNonNull(omoFilename, "omoFilename cannot be null");
            // description and createdAt may be null
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

        public ExportParameters() {}

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
