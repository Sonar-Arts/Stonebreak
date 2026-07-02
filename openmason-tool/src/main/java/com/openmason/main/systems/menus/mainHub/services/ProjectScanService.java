package com.openmason.main.systems.menus.mainHub.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Scans the configured base projects folder ("Home" folder) for .omp project
 * files. A project is an .omp found either directly in the base folder or one
 * subfolder down (the default layout is one folder per project). Results are
 * cached; call {@link #invalidate()} when the hub is shown or the base folder
 * changes. Never creates the base folder — that happens at project creation.
 */
public class ProjectScanService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectScanService.class);
    private static final String OMP_EXTENSION = ".omp";

    /** One .omp discovered under the base folder. */
    public record ScannedProject(String fileName, Path ompPath, FileTime lastModified) {

        /** Project display name: the file name without its extension. */
        public String displayName() {
            return fileName.endsWith(OMP_EXTENSION)
                    ? fileName.substring(0, fileName.length() - OMP_EXTENSION.length())
                    : fileName;
        }
    }

    private final Supplier<Path> baseFolderSupplier;

    private List<ScannedProject> cached;

    public ProjectScanService(Supplier<Path> baseFolderSupplier) {
        this.baseFolderSupplier = baseFolderSupplier;
    }

    public Path getBaseFolder() {
        return baseFolderSupplier.get();
    }

    /** Projects under the base folder, most recently modified first (cached). */
    public List<ScannedProject> getProjects() {
        if (cached == null) {
            cached = scan();
        }
        return cached;
    }

    /** Force a rescan on the next {@link #getProjects()} call. */
    public void invalidate() {
        cached = null;
    }

    private List<ScannedProject> scan() {
        Path base = baseFolderSupplier.get();
        if (base == null || !Files.isDirectory(base)) {
            logger.debug("Projects base folder does not exist yet: {}", base);
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base, 2)) {
            List<ScannedProject> projects = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(OMP_EXTENSION))
                    .map(ProjectScanService::toScanned)
                    .sorted(Comparator.comparing(ScannedProject::lastModified).reversed())
                    .toList();
            logger.debug("Scanned {} project(s) under {}", projects.size(), base);
            return projects;
        } catch (IOException e) {
            logger.warn("Failed to scan projects base folder: {}", base, e);
            return List.of();
        }
    }

    private static ScannedProject toScanned(Path ompPath) {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(ompPath);
        } catch (IOException e) {
            modified = FileTime.fromMillis(0);
        }
        return new ScannedProject(ompPath.getFileName().toString(), ompPath, modified);
    }
}
