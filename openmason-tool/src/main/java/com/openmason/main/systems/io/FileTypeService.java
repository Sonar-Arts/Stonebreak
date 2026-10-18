package com.openmason.main.systems.io;

import com.openmason.api.Contributions;
import com.openmason.api.files.FileOpener;
import com.openmason.api.files.FileType;
import com.openmason.api.files.FileTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Queries over the registered {@link FileType}s and {@link FileOpener}s (#379): which type a path
 * is, what the Project Browser lists, which kinds agents may save, and opening a file with the
 * editor its plugin registered. Published as a service by the editor shell.
 */
public final class FileTypeService {

    private static final Logger logger = LoggerFactory.getLogger(FileTypeService.class);

    private final Contributions<FileType> types;
    private final Contributions<FileOpener> openers;

    public FileTypeService(Contributions<FileType> types, Contributions<FileOpener> openers) {
        this.types = Objects.requireNonNull(types, "types");
        this.openers = Objects.requireNonNull(openers, "openers");
    }

    /** A service over the built-in types only, with no openers (headless code and tests). */
    public static FileTypeService builtin() {
        Contributions<FileType> types = new Contributions<>("file type", FileType::id);
        BuiltinFileTypes.all().forEach(t -> types.register("builtin", t));
        return new FileTypeService(types, new Contributions<>("file opener", FileOpener::fileTypeId));
    }

    /** Every registered type, in registration order. */
    public List<FileType> all() {
        return types.all();
    }

    public Optional<FileType> find(String id) {
        return types.find(id);
    }

    /** The type of {@code path} (longest matching extension), if any. */
    public Optional<FileType> forPath(Path path) {
        return FileTypes.match(types.all(), path);
    }

    public Optional<FileType> forName(String fileName) {
        return FileTypes.match(types.all(), fileName);
    }

    /** The types the Project Browser lists, by their browse sort order. */
    public List<FileType> browsable() {
        return types.all().stream().filter(t -> t.browse() != null)
                .sorted(Comparator.comparingInt(t -> t.browse().sortOrder())).toList();
    }

    /** The kinds agent-initiated saves can write (types with a save rule). */
    public List<WriteKind> saveKinds() {
        return types.all().stream().filter(t -> t.save() != null).map(WriteKind::of).toList();
    }

    /** Whether a plugin registered what opening {@code type} does. */
    public boolean canOpen(FileType type) {
        return type != null && openers.find(type.id()).isPresent();
    }

    /**
     * Opens {@code path} with its type's registered opener.
     *
     * @return false when the path has no known type or the type has no opener
     */
    public boolean open(Path path) {
        Optional<FileType> type = forPath(path);
        if (type.isEmpty()) {
            return false;
        }
        Optional<FileOpener> opener = openers.find(type.get().id());
        if (opener.isEmpty()) {
            logger.warn("No opener registered for {} files ({})", type.get().id(), path);
            return false;
        }
        opener.get().open().accept(path);
        return true;
    }
}
