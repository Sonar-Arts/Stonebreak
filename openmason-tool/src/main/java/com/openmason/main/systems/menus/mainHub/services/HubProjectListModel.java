package com.openmason.main.systems.menus.mainHub.services;

import com.openmason.main.systems.menus.mainHub.model.HubProjectEntry;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.menus.mainHub.services.ProjectScanService.ScannedProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the base-folder scan with the persisted recent-projects list into the
 * hub's "Projects" view. Scanned projects inherit identity/last-opened from a
 * matching recent entry (by normalized absolute path); scans without a match
 * are synthesized on the fly and are never written back to the recents file
 * until actually opened. Recents living outside the base folder are marked
 * external; recents whose file no longer exists are marked missing.
 *
 * <p>The merged list is cached (building it touches the filesystem); call
 * {@link #invalidate()} whenever the hub is shown, the base folder changes, or
 * a rename/delete mutates the underlying lists.
 */
public class HubProjectListModel {

    /** Template attached to synthesized entries (mirrors RecentProjectsService's default). */
    private static final ProjectTemplate SCANNED_TEMPLATE = new ProjectTemplate.Builder()
            .id("default")
            .name("Default")
            .type(ProjectTemplate.TemplateType.BASIC_3D_MODEL)
            .build();

    private final ProjectScanService scanService;
    private final RecentProjectsService recentProjectsService;

    private List<HubProjectEntry> cached;

    public HubProjectListModel(ProjectScanService scanService,
                               RecentProjectsService recentProjectsService) {
        this.scanService = scanService;
        this.recentProjectsService = recentProjectsService;
    }

    /** Merged project entries, most recently opened first (cached). */
    public List<HubProjectEntry> getEntries() {
        if (cached == null) {
            cached = build();
        }
        return cached;
    }

    /** Merged entries filtered by name/description (case-insensitive contains). */
    public List<HubProjectEntry> search(String query) {
        if (query == null || query.isEmpty()) {
            return getEntries();
        }
        String lower = query.toLowerCase();
        return getEntries().stream()
                .filter(e -> e.project().getName().toLowerCase().contains(lower)
                        || e.project().getDescription().toLowerCase().contains(lower))
                .toList();
    }

    /** Rebuild the merged list on the next access. */
    public void invalidate() {
        cached = null;
        scanService.invalidate();
    }

    private List<HubProjectEntry> build() {
        Path base = normalize(scanService.getBaseFolder());
        List<RecentProject> recents = recentProjectsService.getRecentProjects();

        Map<Path, RecentProject> recentsByPath = new HashMap<>();
        for (RecentProject recent : recents) {
            Path key = normalize(pathOf(recent.getPath()));
            if (key != null) {
                recentsByPath.putIfAbsent(key, recent);
            }
        }

        List<HubProjectEntry> entries = new ArrayList<>();
        java.util.Set<Path> scannedPaths = new java.util.HashSet<>();

        for (ScannedProject scanned : scanService.getProjects()) {
            Path key = normalize(scanned.ompPath());
            if (key == null || !scannedPaths.add(key)) {
                continue;
            }
            RecentProject recent = recentsByPath.get(key);
            entries.add(new HubProjectEntry(
                    recent != null ? recent : synthesize(scanned), false, false));
        }

        for (RecentProject recent : recents) {
            Path key = normalize(pathOf(recent.getPath()));
            if (key == null || scannedPaths.contains(key)) {
                continue;
            }
            boolean external = base == null || !key.startsWith(base);
            boolean missing = !Files.exists(key);
            entries.add(new HubProjectEntry(recent, external, missing));
        }

        entries.sort(Comparator.comparing((HubProjectEntry e) -> e.project().getLastOpened()).reversed());
        return entries;
    }

    /**
     * Wrap a scanned .omp as a RecentProject without persisting it. The id is
     * derived from the path so selection identity survives rescans.
     */
    private static RecentProject synthesize(ScannedProject scanned) {
        LocalDateTime modified = LocalDateTime.ofInstant(
                scanned.lastModified().toInstant(), ZoneId.systemDefault());
        return new RecentProject.Builder(modified, SCANNED_TEMPLATE)
                .id("scan:" + scanned.ompPath())
                .name(scanned.displayName())
                .path(scanned.ompPath().toString())
                .description("")
                .build();
    }

    private static Path pathOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Path.of(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path normalize(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return path;
        }
    }
}
