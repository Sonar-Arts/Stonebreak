package com.openmason.engine.format.sbt;

import java.util.Objects;

/**
 * Stonebreak Texture (.SBT) file format specification.
 *
 * <p>SBT files are the game-ready export format for Open Mason texture assets.
 * Structurally identical to SBO/SBE files, they wrap an Open Mason Texture
 * (.OMT) file with metadata used by the Stonebreak engine to identify and
 * register texture content.
 *
 * <p>ZIP Structure:
 * <ul>
 *   <li>{@code manifest.json} - SBT metadata (identity, texture type, pack, author, checksum)</li>
 *   <li>{@code texture.omt} - Embedded Open Mason Texture file</li>
 * </ul>
 */
public final class SBTFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.0";

    /** File extension for SBT files */
    public static final String FILE_EXTENSION = ".sbt";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Embedded OMT filename in ZIP archive */
    public static final String EMBEDDED_OMT_FILENAME = "texture.omt";

    /** Checksum algorithm used for integrity verification */
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    private SBTFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ensures a file path has the .sbt extension.
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
     * Checks if a file path has the .sbt extension.
     */
    public static boolean hasSBTExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }
        return filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /**
     * Texture type classification for Stonebreak textures.
     * Determines how the game engine registers and applies the texture.
     */
    public enum TextureType {
        BLOCK("block"),
        ITEM("item"),
        ENTITY("entity"),
        UI("ui"),
        OTHER("other");

        private final String id;

        TextureType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static TextureType fromId(String id) {
            if (id == null) return OTHER;
            for (TextureType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
            return OTHER;
        }
    }

    /**
     * Complete SBT document structure.
     *
     * @param version       format version string
     * @param textureId     unique identifier for this texture within Stonebreak
     * @param textureName   human-readable display name
     * @param textureType   texture type classification
     * @param texturePack   pack/group identifier
     * @param checksum      SHA-256 checksum of the embedded OMT file bytes
     * @param author        author attribution
     * @param description   optional description
     * @param createdAt     ISO-8601 creation timestamp
     * @param omtFilename   filename of the embedded OMT file in the ZIP
     */
    public record Document(
            String version,
            String textureId,
            String textureName,
            String textureType,
            String texturePack,
            String checksum,
            String author,
            String description,
            String createdAt,
            String omtFilename
    ) {
        public Document {
            Objects.requireNonNull(version, "version cannot be null");
            Objects.requireNonNull(textureId, "textureId cannot be null");
            Objects.requireNonNull(textureName, "textureName cannot be null");
            Objects.requireNonNull(textureType, "textureType cannot be null");
            Objects.requireNonNull(texturePack, "texturePack cannot be null");
            Objects.requireNonNull(checksum, "checksum cannot be null");
            Objects.requireNonNull(author, "author cannot be null");
            Objects.requireNonNull(omtFilename, "omtFilename cannot be null");
        }
    }

    /**
     * Builder for constructing SBT export parameters before serialization.
     */
    public static final class ExportParameters {
        private String textureId = "";
        private String textureName = "";
        private TextureType textureType = TextureType.BLOCK;
        private String texturePack = "default";
        private String author = "";
        private String description = "";

        public ExportParameters() {}

        public String getTextureId() { return textureId; }
        public void setTextureId(String textureId) { this.textureId = textureId != null ? textureId : ""; }

        public String getTextureName() { return textureName; }
        public void setTextureName(String textureName) { this.textureName = textureName != null ? textureName : ""; }

        public TextureType getTextureType() { return textureType; }
        public void setTextureType(TextureType textureType) { this.textureType = textureType != null ? textureType : TextureType.BLOCK; }

        public String getTexturePack() { return texturePack; }
        public void setTexturePack(String texturePack) { this.texturePack = texturePack != null ? texturePack : ""; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author != null ? author : ""; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description != null ? description : ""; }

        public boolean isValid() {
            return !textureId.isBlank()
                    && !textureName.isBlank()
                    && !texturePack.isBlank()
                    && !author.isBlank();
        }

        public String getValidationError() {
            if (textureId.isBlank()) return "Texture ID is required";
            if (textureName.isBlank()) return "Texture Name is required";
            if (texturePack.isBlank()) return "Texture Pack is required";
            if (author.isBlank()) return "Author is required";
            return "";
        }
    }
}
