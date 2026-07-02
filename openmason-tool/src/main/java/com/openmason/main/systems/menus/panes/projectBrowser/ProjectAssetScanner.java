package com.openmason.main.systems.menus.panes.projectBrowser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Scans the open project's folder (the directory its .omp lives in) for the
 * asset files the Project Browser lists: .OMO models and .OMT textures. One
 * directory level, cached; call {@link #invalidate()} to force a rescan.
 */
public class ProjectAssetScanner {

    private static final Logger logger = LoggerFactory.getLogger(ProjectAssetScanner.class);

    /** Asset kinds the browser understands. */
    public enum AssetType {
        OMO(".OMO Model", ".omo"),
        OMT(".OMT Texture", ".omt");

        private final String label;
        private final String extension;

        AssetType(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        public String label() {
            return label;
        }
    }

    /** One asset file discovered in the project folder. */
    public record AssetEntry(String name, Path path, AssetType type) {

        public String pathString() {
            return path.toString();
        }
    }

    private Path root;
    private List<AssetEntry> cached;

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
        List<AssetEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                AssetEntry entry = toEntry(file);
                if (entry != null) {
                    entries.add(entry);
                }
            });
            logger.debug("Scanned {} asset(s) in project folder {}", entries.size(), root);
        } catch (IOException e) {
            logger.warn("Failed to scan project folder: {}", root, e);
        }
        return entries;
    }

    private static AssetEntry toEntry(Path file) {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase();
        for (AssetType type : AssetType.values()) {
            if (lower.endsWith(type.extension)) {
                String name = fileName.substring(0, fileName.length() - type.extension.length());
                return new AssetEntry(name, file, type);
            }
        }
        return null;
    }
}
