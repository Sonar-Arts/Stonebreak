package com.openmason.api.files;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A kind of file the editor knows: its extensions, where agent-initiated saves of it go by default,
 * and whether (and where) the Project Browser lists it. Contributed through
 * {@code PluginContext.fileTypes()}; what opening one does is a separate {@link FileOpener}
 * contribution, so a type can be declared statically and bound to its editor at registration.
 *
 * @param id          stable id (lowercase, e.g. {@code omo})
 * @param displayName human name ("Open Mason Model")
 * @param extensions  lowercase extensions with the leading dot, primary first; multi-dot
 *                    extensions ({@code .uss.json}) are matched before shorter ones
 * @param save        default save target, or null when agents/dialogs never write this kind
 * @param browse      Project Browser listing, or null when the browser does not list it
 */
public record FileType(String id, String displayName, List<String> extensions, SaveRule save, Browse browse) {

    /**
     * Where a save of this kind goes when the caller names no folder.
     *
     * @param root         {@code project}, {@code exports} or {@code game}
     * @param subdir       folder under the root ({@code ""} = the root itself)
     * @param fallbackName file stem used when nothing better is known ({@code model})
     */
    public record SaveRule(String root, String subdir, String fallbackName) {
        public SaveRule {
            Objects.requireNonNull(root, "root");
            subdir = subdir == null ? "" : subdir;
            Objects.requireNonNull(fallbackName, "fallbackName");
        }
    }

    /**
     * How the Project Browser lists this kind.
     *
     * @param label     the type column text (".OMO Model")
     * @param sortOrder position when sorting by type
     * @param folders   project-relative folders scanned for it: {@code ""} is the project folder
     *                  itself; a trailing {@code /**} walks the folder recursively
     */
    public record Browse(String label, int sortOrder, List<String> folders) {
        public Browse {
            Objects.requireNonNull(label, "label");
            folders = List.copyOf(folders);
        }
    }

    public FileType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (extensions == null || extensions.isEmpty()) {
            throw new IllegalArgumentException("File type " + id + " needs at least one extension");
        }
        extensions = extensions.stream().map(e -> {
            String x = e.toLowerCase(Locale.ROOT);
            return x.startsWith(".") ? x : "." + x;
        }).toList();
    }

    /** A type with one extension, not saved by agents and not listed in the browser. */
    public static FileType of(String id, String displayName, String extension) {
        return new FileType(id, displayName, List.of(extension), null, null);
    }

    public FileType withSave(String root, String subdir, String fallbackName) {
        return new FileType(id, displayName, extensions, new SaveRule(root, subdir, fallbackName), browse);
    }

    public FileType withBrowse(String label, int sortOrder, String... folders) {
        return new FileType(id, displayName, extensions, save, new Browse(label, sortOrder, List.of(folders)));
    }

    /** The primary extension, with its dot ({@code .omo}). */
    public String extension() {
        return extensions.get(0);
    }

    /** The extension without the dot ({@code omo}), as file dialog filters want it. */
    public String filterSpec() {
        return extension().substring(1);
    }

    /** The matching extension of {@code fileName} (case-insensitive), or null. */
    public String matchingExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        String best = null;
        for (String ext : extensions) {
            if (lower.endsWith(ext) && (best == null || ext.length() > best.length())) {
                best = ext;
            }
        }
        return best;
    }

    /** True when {@code fileName} ends with one of this type's extensions (case-insensitive). */
    public boolean matches(String fileName) {
        return matchingExtension(fileName) != null;
    }

    public boolean matches(Path path) {
        return path != null && path.getFileName() != null && matches(path.getFileName().toString());
    }

    /** {@code fileName} without this type's extension (unchanged when it has none). */
    public String stripExtension(String fileName) {
        String ext = matchingExtension(fileName);
        return ext == null ? fileName : fileName.substring(0, fileName.length() - ext.length());
    }
}
