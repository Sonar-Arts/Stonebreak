package com.openmason.main.systems.menus.panes.projectBrowser;

import com.openmason.api.files.FileType;
import com.openmason.api.files.FileTypes;
import com.openmason.main.systems.io.FileTypeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Scans the open project's folder (the directory its .omp lives in) for the asset files the
 * Project Browser lists: every registered {@link FileType} with a {@link FileType.Browse browse}
 * rule (#379), in the folders that rule names ({@code ""} = the project folder itself, flat;
 * {@code Scenes} = that folder, flat; {@code UI/**} = that folder, recursively). Cached; call
 * {@link #invalidate()} to force a rescan.
 */
public class ProjectAssetScanner {

    private static final Logger logger = LoggerFactory.getLogger(ProjectAssetScanner.class);

    /** How deep a recursive ({@code /**}) folder is walked. */
    private static final int MAX_DEPTH = 8;

    /** One asset file discovered in the project folder. */
    public record AssetEntry(String name, Path path, FileType type) {

        public String pathString() {
            return path.toString();
        }
    }

    private Path root;
    private List<AssetEntry> cached;
    private Supplier<List<FileType>> types = () -> FileTypeService.builtin().browsable();

    /** Where the browsable types come from (the registered file types). */
    public void setTypes(Supplier<List<FileType>> types) {
        this.types = Objects.requireNonNull(types, "types");
        cached = null;
    }

    /** Set the project folder to scan; {@code null} means no project is open. */
    public void setRoot(Path root) {
        if (!Objects.equals(this.root, root)) {
            this.root = root;
            cached = null;
        }
    }

    public Path getRoot() {
        return root;
    }

    /** Force a rescan on the next {@link #scan()} call. */
    public void invalidate() {
        cached = null;
    }

    /** Assets in the project folder (cached; empty when no project is open). */
    public List<AssetEntry> scan() {
        if (cached == null) {
            cached = doScan();
        }
        return cached;
    }

    private List<AssetEntry> doScan() {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<FileType> browsable = types.get().stream().filter(t -> t.browse() != null).toList();
        // Folder spec -> the types listed there, in the order the specs first appear.
        Map<String, List<FileType>> byFolder = new LinkedHashMap<>();
        for (FileType t : browsable) {
            for (String folder : t.browse().folders()) {
                byFolder.computeIfAbsent(folder, f -> new ArrayList<>()).add(t);
            }
        }
        List<AssetEntry> entries = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Map.Entry<String, List<FileType>> e : byFolder.entrySet()) {
            String spec = e.getKey();
            boolean recursive = spec.endsWith("/**");
            String rel = recursive ? spec.substring(0, spec.length() - 3) : spec;
            Path dir = rel.isEmpty() ? root : root.resolve(rel);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            // Flat folders stay flat on purpose: walking would start surfacing models nested in
            // subfolders, which changes what the browser has always listed.
            try (Stream<Path> files = recursive ? Files.walk(dir, MAX_DEPTH) : Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    AssetEntry entry = toEntry(file, e.getValue());
                    if (entry != null && seen.add(file.toAbsolutePath().normalize())) {
                        entries.add(entry);
                    }
                });
            } catch (IOException ex) {
                logger.warn("Failed to scan project folder: {}", dir, ex);
            }
        }
        logger.debug("Scanned {} asset(s) for project folder {}", entries.size(), root);
        return entries;
    }

    private static AssetEntry toEntry(Path file, List<FileType> types) {
        String fileName = file.getFileName().toString();
        return FileTypes.match(types, fileName)
                .map(t -> new AssetEntry(t.stripExtension(fileName), file, t))
                .orElse(null);
    }
}
