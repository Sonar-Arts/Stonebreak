package com.openmason.engine.format.sbo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.openmason.engine.format.ext.FormatExtensionCodec;
import com.openmason.engine.format.ext.FormatExtensions;

/**
 * Stonebreak Object (.SBO) file format specification.
 *
 * <p>SBO files are the game-ready export format for Open Mason assets.
 * They wrap an OMO model or an OMT texture with object identity,
 * classification, render layer, states, sounds, integrity verification and
 * author attribution. Game data rides in namespaced {@code extensions}
 * ({@link FormatExtensions}, read through a game's {@link FormatExtensionCodec});
 * the engine never interprets it.
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
 *   <li>1.1 - Optional {@code gameProperties} block (game metadata; since 1.11 a game extension).</li>
 *   <li>1.2 - Texture-only payload variant: {@code omoFile} XOR {@code textureFile}.</li>
 *   <li>1.3 - Optional named states ({@code states[]}, assets under {@code states/<name>/}); the
 *       default state's bytes also sit in the legacy {@code model.omo}/{@code texture.omt} entry.</li>
 *   <li>1.4 - Optional crafting {@code recipes} (game data; since 1.11 a game extension).</li>
 *   <li>1.5 - Optional {@code smeltingRecipes} and {@code fuel} (game data; since 1.11 a game extension).</li>
 *   <li>1.6 - Optional per-state animation clips ({@code states/<name>/clip.omanim} +
 *       {@link AnimationRef}; loop resolved at export via {@link LoopMode}).</li>
 *   <li>1.7 - Optional {@code sounds[]} block (shared with SBE 1.4, see
 *       {@link com.openmason.engine.format.sound.SoundData}).</li>
 *   <li>1.8 - Optional {@code drops} table (game data; since 1.11 a game extension).</li>
 *   <li>1.9 - Mining data: item {@code tool} section, block material/required tier (game data;
 *       since 1.11 a game extension).</li>
 *   <li>1.10 - Combat data: item {@code weapon} section (game data; since 1.11 a game extension).</li>
 *   <li>1.11 - Namespaced {@code extensions} (#383): game data moves out of the format into
 *       {@code "<namespace>:<name>": {...}} members the engine keeps as written (unknown ones
 *       byte for byte). The render layer and transparency become the top-level
 *       {@code rendering} object ({@link Rendering}); older files' {@code gameProperties} still
 *       supply them. Top-level fields of older files the format no longer owns are kept and
 *       written back unchanged until the game that owns them moves them into its extension.</li>
 * </ul>
 */
public final class SBOFormat {

    /** Current format version */
    public static final String FORMAT_VERSION = "1.11";

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
     * @param rendering        render layer + transparency (1.11 top-level; older files:
     *                         their gameProperties block); never null
     * @param states           optional named states (1.3+). Empty list when the SBO
     *                         has no states (default-only behavior). When non-empty,
     *                         exactly one entry's name must equal {@code defaultStateName}.
     * @param defaultStateName name of the default state (1.3+); null when {@code states}
     *                         is empty.
     * @param sounds           optional sound bindings (1.7+): event name → audio
     *                         sample (embedded or resource-referenced).
     *                         {@code null} means the object declares no sounds.
     * @param extensions       namespaced game data (1.11+) and the top-level fields older
     *                         files wrote that the format no longer owns; never null
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
            Rendering rendering,
            List<StateEntry> states,
            String defaultStateName,
            com.openmason.engine.format.sound.SoundData sounds,
            FormatExtensions extensions
    ) {
        public Document {
            rendering = rendering == null ? Rendering.DEFAULT : rendering;
            extensions = extensions == null ? FormatExtensions.EMPTY : extensions;
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


        /** Copy with the rendering fields replaced. */
        public Document withRendering(Rendering value) {
            return new Document(version, objectId, objectName, objectType, objectPack, checksum, author, description, createdAt, omoFilename, textureFilename, value, states, defaultStateName, sounds, extensions);
        }

        /** Copy with the extensions (and foreign legacy fields) replaced. */
        public Document withExtensions(FormatExtensions value) {
            return new Document(version, objectId, objectName, objectType, objectPack, checksum, author, description, createdAt, omoFilename, textureFilename, rendering, states, defaultStateName, sounds, value);
        }

        /** The typed extension {@code codec} reads (see {@link FormatExtensions#get}). */
        public <T> java.util.Optional<T> extension(FormatExtensionCodec<T> codec) {
            return extensions.get(codec);
        }

        /** Copy with the {@code codec} extension set to {@code value} (null removes it). */
        public <T> Document withExtension(FormatExtensionCodec<T> codec, T value) {
            return withExtensions(extensions.with(codec, value));
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




        /** True when this SBO declares one or more sound bindings (1.7+). */
        public boolean hasSounds() {
            return sounds != null && !sounds.isEmpty();
        }



    }

    /**
     * The container-level rendering fields every game reads (#383; before 1.11 they lived in the
     * game's {@code gameProperties} block, which the parser still reads them from).
     *
     * @param renderLayer {@code OPAQUE}, {@code CUTOUT} or {@code TRANSLUCENT}: the chunk render pass
     *                    (CUTOUT is alpha-tested, TRANSLUCENT alpha-blended and sorted); together with
     *                    the {@code renderLayer} of the embedded model's face materials (most
     *                    permissive wins). Upper-cased; blank/null = OPAQUE.
     * @param transparent whether neighbouring faces still render through this object (it cannot
     *                    occlude them); any non-OPAQUE layer implies it
     */
    public record Rendering(String renderLayer, boolean transparent) {
        /** An opaque, occluding object. */
        public static final Rendering DEFAULT = new Rendering("OPAQUE", false);

        public Rendering {
            renderLayer = renderLayer == null || renderLayer.isBlank()
                    ? "OPAQUE" : renderLayer.trim().toUpperCase(java.util.Locale.ROOT);
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
        private Rendering rendering = Rendering.DEFAULT;
        private FormatExtensions extensions = FormatExtensions.EMPTY;
        private boolean statesEnabled = false;
        private final List<StateSpec> states = new ArrayList<>();
        private String defaultStateName = "";
        private final List<com.openmason.engine.format.sound.SoundSpec> sounds = new ArrayList<>();

        public ExportParameters() {}

        public Rendering getRendering() { return rendering; }
        public void setRendering(Rendering rendering) { this.rendering = rendering != null ? rendering : Rendering.DEFAULT; }

        /** Extensions written into the new file (#383). */
        public FormatExtensions getExtensions() { return extensions; }
        public void setExtensions(FormatExtensions extensions) {
            this.extensions = extensions != null ? extensions : FormatExtensions.EMPTY;
        }

        /** Sets (null removes) the {@code codec} extension of the new file. */
        public <T> void setExtension(FormatExtensionCodec<T> codec, T value) {
            this.extensions = extensions.with(codec, value);
        }

        /** The {@code codec} extension set so far. */
        public <T> java.util.Optional<T> getExtension(FormatExtensionCodec<T> codec) {
            return extensions.get(codec);
        }

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
