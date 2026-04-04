package com.openmason.main.systems.rendering.model.io.sbe;

import java.util.Objects;

/**
 * Stonebreak Entity (.SBE) file format specification.
 *
 * <p>SBE files are the game-ready export format for Open Mason entity assets.
 * Structurally identical to SBO files, they additionally carry entity-specific
 * metadata (Entity Type) that the Stonebreak engine uses for entity registration
 * and behaviour binding. This format will be expanded with further entity
 * information in future versions.
 *
 * <p>ZIP Structure:
 * <ul>
 *   <li>{@code manifest.json} - SBE metadata (identity, entity type, pack, author, checksum)</li>
 *   <li>{@code model.omo} - Embedded Open Mason Object file (contains texture data internally)</li>
 * </ul>
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with entity type, object identity, pack, checksum, and author signage</li>
 * </ul>
 */
public final class SBEFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.0";

    /** File extension for SBE files */
    public static final String FILE_EXTENSION = ".sbe";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Embedded OMO filename in ZIP archive */
    public static final String EMBEDDED_OMO_FILENAME = "model.omo";

    /** Checksum algorithm used for integrity verification */
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    /** Private constructor - utility class */
    private SBEFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures a file path has the .sbe extension.
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
     * Checks if a file path has the .sbe extension.
     */
    public static boolean hasSBEExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        return filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /**
     * Entity type classification for Stonebreak entities.
     * Determines how the game engine registers and manages the entity.
     */
    public enum EntityType {
        MOB("mob"),
        NPC("npc"),
        PROJECTILE("projectile"),
        VEHICLE("vehicle"),
        OTHER("other");

        private final String id;

        EntityType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        /**
         * Look up an EntityType by its string identifier.
         *
         * @param id the type identifier
         * @return the matching EntityType, or OTHER if not found
         */
        public static EntityType fromId(String id) {
            if (id == null) return OTHER;
            for (EntityType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
            return OTHER;
        }
    }

    /**
     * Complete SBE document structure.
     *
     * <p>Contains all metadata needed to identify, classify, verify,
     * and attribute a Stonebreak entity asset.
     *
     * @param version      format version string
     * @param objectId     unique identifier for this entity within Stonebreak
     * @param objectName   human-readable display name
     * @param entityType   entity type classification (mob, npc, projectile, etc.)
     * @param objectPack   pack/group identifier for organizing related objects
     * @param checksum     SHA-256 checksum of the embedded OMO file bytes
     * @param author       author attribution (creator name or studio)
     * @param description  optional description of the entity
     * @param createdAt    ISO-8601 timestamp of creation
     * @param omoFilename  filename of the embedded OMO file in the ZIP
     */
    public record Document(
            String version,
            String objectId,
            String objectName,
            String entityType,
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
            Objects.requireNonNull(entityType, "entityType cannot be null");
            Objects.requireNonNull(objectPack, "objectPack cannot be null");
            Objects.requireNonNull(checksum, "checksum cannot be null");
            Objects.requireNonNull(author, "author cannot be null");
            Objects.requireNonNull(omoFilename, "omoFilename cannot be null");
        }
    }

    /**
     * Builder for constructing SBE export parameters before serialization.
     * Collects user-provided metadata for the export window.
     */
    public static final class ExportParameters {
        private String objectId = "";
        private String objectName = "";
        private EntityType entityType = EntityType.MOB;
        private String objectPack = "default";
        private String author = "";
        private String description = "";

        public ExportParameters() {}

        public String getObjectId() { return objectId; }
        public void setObjectId(String objectId) { this.objectId = objectId != null ? objectId : ""; }

        public String getObjectName() { return objectName; }
        public void setObjectName(String objectName) { this.objectName = objectName != null ? objectName : ""; }

        public EntityType getEntityType() { return entityType; }
        public void setEntityType(EntityType entityType) { this.entityType = entityType != null ? entityType : EntityType.MOB; }

        public String getObjectPack() { return objectPack; }
        public void setObjectPack(String objectPack) { this.objectPack = objectPack != null ? objectPack : ""; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author != null ? author : ""; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description != null ? description : ""; }

        /**
         * Validates that all required fields are populated.
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
