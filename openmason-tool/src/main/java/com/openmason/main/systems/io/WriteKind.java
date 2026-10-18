package com.openmason.main.systems.io;

import com.openmason.api.files.FileType;
import com.openmason.api.target.GameTarget;
import com.openmason.main.systems.target.GameTargets;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What kind of file is being written: a registered {@link FileType}'s save rule (#379). Each kind
 * fixes its extension, the root it defaults into and the sub-folder inside that root, so a bare
 * file name is always enough to produce a sensible target. The constants are the built-in kinds;
 * a plugin's kinds come from its registered file types ({@link FileTypeService#saveKinds()}).
 */
public final class WriteKind {

    public static final WriteKind OMO = of(BuiltinFileTypes.OMO);
    public static final WriteKind OMT = of(BuiltinFileTypes.OMT);
    public static final WriteKind OMANIM = of(BuiltinFileTypes.OMANIM);
    public static final WriteKind OMSC = of(BuiltinFileTypes.OMSC);
    public static final WriteKind PNG = of(BuiltinFileTypes.PNG);
    public static final WriteKind SBO = of(BuiltinFileTypes.SBO);
    public static final WriteKind SBE = of(BuiltinFileTypes.SBE);
    /** UI Editor source documents (#324); bare names land beside the convention tree {@code UI/}. */
    public static final WriteKind OMUI = of(BuiltinFileTypes.OMUI);
    /** Exported UI documents, the editor's default export folder. */
    public static final WriteKind SBUI = of(BuiltinFileTypes.SBUI);

    private final FileType type;
    private final WriteRoot defaultRoot;

    private WriteKind(FileType type) {
        this.type = type;
        this.defaultRoot = WriteRoot.fromPrefix(type.save().root());
        if (defaultRoot == null) {
            throw new IllegalArgumentException("File type " + type.id() + " saves into unknown root '"
                    + type.save().root() + "' (project, exports or target)");
        }
    }

    /**
     * The write kind of {@code type}.
     *
     * @throws IllegalArgumentException when the type has no save rule or names an unknown root
     */
    public static WriteKind of(FileType type) {
        Objects.requireNonNull(type, "type");
        if (type.save() == null) {
            throw new IllegalArgumentException("File type " + type.id() + " has no save rule");
        }
        return new WriteKind(type);
    }

    /** The built-in kinds, in declaration order. */
    public static List<WriteKind> builtins() {
        return List.of(OMO, OMT, OMANIM, OMSC, PNG, SBO, SBE, OMUI, SBUI);
    }

    /** The built-in kind with id {@code id} ({@code omo}, {@code .omo}...), or null. */
    public static WriteKind fromId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(".")) {
            key = key.substring(1);
        }
        for (WriteKind k : builtins()) {
            if (k.id().equals(key)) {
                return k;
            }
        }
        return null;
    }

    /** The file type this kind writes. */
    public FileType fileType() {
        return type;
    }

    /** Lower-case extension including the dot ({@code .omo}). */
    public String extension() {
        return type.extension();
    }

    public WriteRoot defaultRoot() {
        return defaultRoot;
    }

    /**
     * Sub-folder under the default root a bare name lands in ("" = the root itself), resolved
     * against the active game target ({@link #subdirFor}).
     */
    public String defaultSubdir() {
        return subdirFor(defaultRoot == WriteRoot.TARGET ? GameTargets.shared().active().orElse(null) : null);
    }

    /**
     * The sub-folder for {@code target}. A save rule into the {@code target} root names one of the
     * target's asset folder keys ({@code sbo.block}); a key the target does not declare (or no
     * target) lands in the root itself. A value with a {@code /} is a literal folder.
     */
    public String subdirFor(GameTarget target) {
        String sub = type.save().subdir();
        if (defaultRoot != WriteRoot.TARGET || sub.isEmpty() || sub.contains("/")) {
            return sub;
        }
        String folder = target == null ? null : target.folders().folder(sub);
        return folder == null ? "" : folder;
    }

    /** Name used when the caller supplies none. */
    public String fallbackName() {
        return type.save().fallbackName();
    }

    /** Lower-case id used in JSON ({@code omo}, {@code sbo}, …). */
    public String id() {
        return type.id();
    }

    /** True when {@code name} already carries this kind's extension. */
    public boolean hasExtension(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(extension());
    }

    /**
     * Append this kind's extension when the name has none. A name carrying a
     * <em>different</em> extension is refused — silently rewriting
     * {@code foo.png} into {@code foo.png.omo} is how files get lost.
     *
     * @throws IllegalArgumentException when the name has a foreign extension
     */
    public String ensureExtension(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("file name is empty");
        }
        if (hasExtension(name)) {
            return name;
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot > slash + 1) {
            throw new IllegalArgumentException("wrong_extension: " + name + " must end in "
                    + extension() + " for a " + id() + " write");
        }
        return name + extension();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WriteKind k && k.id().equals(id());
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }

    @Override
    public String toString() {
        return id().toUpperCase(Locale.ROOT);
    }
}
