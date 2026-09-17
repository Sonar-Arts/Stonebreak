package com.openmason.main.systems.io;

import java.util.Locale;

/**
 * What kind of file is being written. Each kind fixes its extension, the root
 * it defaults into and the sub-folder inside that root, so a bare file name is
 * always enough to produce a sensible target.
 */
public enum WriteKind {
    OMO(".omo", WriteRoot.PROJECT, "", "model"),
    OMT(".omt", WriteRoot.PROJECT, "", "texture"),
    OMANIM(".omanim", WriteRoot.PROJECT, "", "animation"),
    OMSC(".omsc", WriteRoot.PROJECT, "Scenes", "scene"),
    PNG(".png", WriteRoot.EXPORTS, "", "texture"),
    SBO(".sbo", WriteRoot.GAME_RESOURCES, "sbo/blocks", "object"),
    SBE(".sbe", WriteRoot.GAME_RESOURCES, "sbe/Mobs", "entity");

    private final String extension;
    private final WriteRoot defaultRoot;
    private final String defaultSubdir;
    private final String fallbackName;

    WriteKind(String extension, WriteRoot defaultRoot, String defaultSubdir, String fallbackName) {
        this.extension = extension;
        this.defaultRoot = defaultRoot;
        this.defaultSubdir = defaultSubdir;
        this.fallbackName = fallbackName;
    }

    /** Lower-case extension including the dot ({@code .omo}). */
    public String extension() {
        return extension;
    }

    public WriteRoot defaultRoot() {
        return defaultRoot;
    }

    /** Sub-folder under the default root a bare name lands in ("" = the root itself). */
    public String defaultSubdir() {
        return defaultSubdir;
    }

    /** Name used when the caller supplies none. */
    public String fallbackName() {
        return fallbackName;
    }

    /** Lower-case id used in JSON ({@code omo}, {@code sbo}, …). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True when {@code name} already carries this kind's extension. */
    public boolean hasExtension(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(extension);
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
                    + extension + " for a " + id() + " write");
        }
        return name + extension;
    }

    public static WriteKind fromId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(".")) {
            key = key.substring(1);
        }
        for (WriteKind k : values()) {
            if (k.id().equals(key)) {
                return k;
            }
        }
        return null;
    }
}
