package com.openmason.engine.format.sbe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Stonebreak Entity (.SBE) file format specification.
 *
 * <p>SBE files are the game-ready export format for Open Mason entity assets.
 * They carry a single base OMO (the default render skeleton) plus a list of
 * declared <em>states</em>. Each state can optionally override the base model
 * with its own OMO and attach an animation clip. The runtime resolves an
 * entity's state to its (optional) override model and (optional) clip.
 *
 * <p>ZIP Structure:
 * <ul>
 *   <li>{@code manifest.json} - SBE metadata (identity, entity type, pack, author, checksum, state index)</li>
 *   <li>{@code model.omo} - Embedded base Open Mason Object file (always present)</li>
 *   <li>{@code states/<name>/model.omo} - Optional per-state model override</li>
 *   <li>{@code states/<name>/clip.omanim} - Optional per-state animation clip</li>
 * </ul>
 *
 * <p>Version History:
 * <ul>
 *   <li>1.0 - Initial format with entity type, object identity, pack, checksum, and author signage</li>
 *   <li>1.1 - Added flat animations index (replaced in 1.2)</li>
 *   <li>1.2 - Replaced animations index with a state-based index (per-state model + clip)</li>
 * </ul>
 */
public final class SBEFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.2";

    /** File extension for SBE files */
    public static final String FILE_EXTENSION = ".sbe";

    /** Manifest filename in ZIP archive */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Embedded base OMO filename in ZIP archive */
    public static final String EMBEDDED_OMO_FILENAME = "model.omo";

    /** Directory prefix for embedded per-state assets inside the ZIP archive */
    public static final String STATES_DIR_PREFIX = "states/";

    /** Filename used for per-state model overrides (under {@code states/<name>/}) */
    public static final String STATE_MODEL_FILENAME = "model.omo";

    /** Filename used for per-state animation clips (under {@code states/<name>/}) */
    public static final String STATE_CLIP_FILENAME = "clip.omanim";

    /** File extension for source animation clips (input only) */
    public static final String ANIMATION_EXTENSION = ".omanim";

    /** Checksum algorithm used for integrity verification */
    public static final String CHECKSUM_ALGORITHM = "SHA-256";

    /**
     * Suggested state names that the in-engine animation controller knows how
     * to map to entity behaviour. The format itself does <em>not</em> validate
     * against this list — packs may declare custom states.
     */
    public static final String[] WELL_KNOWN_STATES = {
            "idle", "walk", "run", "attack", "hurt", "death", "graze", "sleep"
    };

    private SBEFormat() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String ensureExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return filePath;
        String trimmed = filePath.trim();
        if (!trimmed.toLowerCase().endsWith(FILE_EXTENSION)) return trimmed + FILE_EXTENSION;
        return trimmed;
    }

    public static boolean hasSBEExtension(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return false;
        return filePath.trim().toLowerCase().endsWith(FILE_EXTENSION);
    }

    /** Returns the conventional ZIP entry path for a state's model override. */
    public static String stateModelPath(String stateName) {
        return STATES_DIR_PREFIX + stateName + "/" + STATE_MODEL_FILENAME;
    }

    /** Returns the conventional ZIP entry path for a state's animation clip. */
    public static String stateClipPath(String stateName) {
        return STATES_DIR_PREFIX + stateName + "/" + STATE_CLIP_FILENAME;
    }

    /** Entity type classification used by the Stonebreak engine. */
    public enum EntityType {
        MOB("mob"),
        NPC("npc"),
        PROJECTILE("projectile"),
        VEHICLE("vehicle"),
        OTHER("other");

        private final String id;
        EntityType(String id) { this.id = id; }
        public String getId() { return id; }

        public static EntityType fromId(String id) {
            if (id == null) return OTHER;
            for (EntityType type : values()) {
                if (type.id.equalsIgnoreCase(id)) return type;
            }
            return OTHER;
        }
    }

    /**
     * Reference to an embedded asset blob.
     *
     * @param filename ZIP entry path within the SBE archive
     * @param checksum SHA-256 hex digest of the embedded bytes
     */
    public record AssetRef(String filename, String checksum) {
        public AssetRef {
            Objects.requireNonNull(filename, "filename cannot be null");
            Objects.requireNonNull(checksum, "checksum cannot be null");
        }
    }

    /**
     * Reference to an embedded animation clip, with the metadata needed for
     * compatibility checks at load time.
     *
     * @param filename      ZIP entry path
     * @param checksum      SHA-256 of clip bytes
     * @param clipName      authored display name (from OMA manifest)
     * @param duration      clip duration in seconds
     * @param fps           authored frame rate
     * @param loop          whether the clip is authored to loop
     * @param requiredParts partIds the clip animates (compat snapshot)
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
            Objects.requireNonNull(filename, "filename cannot be null");
            Objects.requireNonNull(checksum, "checksum cannot be null");
            Objects.requireNonNull(clipName, "clipName cannot be null");
            requiredParts = requiredParts == null
                    ? Collections.emptyList()
                    : List.copyOf(requiredParts);
        }
    }

    /**
     * A declared entity state. Either side of the pair may be absent:
     * <ul>
     *   <li>{@code modelOverride == null} → use the base OMO</li>
     *   <li>{@code animation == null} → state has no clip (pose-only)</li>
     * </ul>
     * A state with both null is still legal (declares the state exists without
     * binding assets — useful for placeholders).
     *
     * @param name          unique state identifier
     * @param modelOverride per-state OMO override, or null
     * @param animation     per-state animation clip, or null
     */
    public record StateEntry(
            String name,
            AssetRef modelOverride,
            AnimationRef animation
    ) {
        public StateEntry {
            Objects.requireNonNull(name, "state name cannot be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("state name cannot be blank");
            }
        }

        public boolean hasModelOverride() { return modelOverride != null; }
        public boolean hasAnimation() { return animation != null; }
    }

    /**
     * Complete SBE document.
     *
     * @param version      format version string
     * @param objectId     unique identifier for this entity within Stonebreak
     * @param objectName   human-readable display name
     * @param entityType   entity type classification
     * @param objectPack   pack/group identifier
     * @param checksum     SHA-256 checksum of the embedded base OMO bytes
     * @param author       author attribution
     * @param description  optional description
     * @param createdAt    ISO-8601 timestamp of creation
     * @param omoFilename  filename of the embedded base OMO file
     * @param states       declared states; never null, may be empty
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
            String omoFilename,
            List<StateEntry> states
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
            states = states == null
                    ? Collections.emptyList()
                    : List.copyOf(states);
        }

        public boolean hasStates() { return !states.isEmpty(); }
    }

    /**
     * Per-state inputs the export window or editor passes to the serializer.
     * Source paths are optional in both slots — a state may declare itself
     * without binding assets.
     */
    public record StateBinding(String name, Path modelOverrideSource, Path clipSource) {
        public StateBinding {
            Objects.requireNonNull(name, "state name cannot be null");
        }
    }

    /**
     * Builder for the export-from-scratch flow. Collects user-supplied
     * metadata plus the list of state bindings; the serializer reads source
     * files from disk and embeds them.
     */
    public static final class ExportParameters {
        private String objectId = "";
        private String objectName = "";
        private EntityType entityType = EntityType.MOB;
        private String objectPack = "default";
        private String author = "";
        private String description = "";
        private final List<StateBinding> states = new ArrayList<>();

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

        public List<StateBinding> getStates() { return states; }

        public void addState(String name, Path modelOverrideSource, Path clipSource) {
            if (name == null) return;
            states.add(new StateBinding(name, modelOverrideSource, clipSource));
        }

        public void clearStates() { states.clear(); }

        public boolean isValid() {
            return !objectId.isBlank()
                    && !objectName.isBlank()
                    && !objectPack.isBlank()
                    && !author.isBlank();
        }

        public String getValidationError() {
            if (objectId.isBlank()) return "Object ID is required";
            if (objectName.isBlank()) return "Object Name is required";
            if (objectPack.isBlank()) return "Object Pack is required";
            if (author.isBlank()) return "Author is required";
            return "";
        }
    }
}
