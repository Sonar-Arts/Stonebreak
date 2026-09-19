package com.openmason.main.systems.io;

import com.openmason.main.AppPaths;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.menus.dialogs.GameResourceDirs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves the three {@link WriteRoot}s lazily — the project root moves when
 * the user opens another project, and the game tree is absent from a packaged
 * jar — so every lookup asks the suppliers afresh.
 */
public final class WriteRoots {

    private final Supplier<Path> project;
    private final Supplier<Path> game;
    private final Supplier<Path> exports;

    private WriteRoots(Supplier<Path> project, Supplier<Path> game, Supplier<Path> exports) {
        this.project = project;
        this.game = game;
        this.exports = exports;
    }

    /** Production wiring; tolerates a null interface (snapshot-test discipline). */
    public static WriteRoots forInterface(MainImGuiInterface mainInterface) {
        Supplier<Path> project = () -> {
            Supplier<String> dir = mainInterface != null
                    ? mainInterface.getProjectDirectorySupplier() : null;
            String s = dir != null ? dir.get() : null;
            if (s != null && !s.isBlank()) {
                return Path.of(s);
            }
            Path fallback = AppPaths.defaultProjectsDir();
            return Files.isDirectory(fallback) ? fallback : null;
        };
        return new WriteRoots(project, GameResourceDirs::resourcesRoot, WriteSandbox::exportsRoot);
    }

    /** Fixed roots for tests; any of them may be null (= absent). */
    public static WriteRoots of(Path project, Path game, Path exports) {
        return new WriteRoots(() -> project, () -> game, () -> exports);
    }

    /** Absolute, normalized root path, or null when that root is unavailable. */
    public Path path(WriteRoot root) {
        Path p = switch (root) {
            case PROJECT -> project.get();
            case GAME_RESOURCES -> game.get();
            case EXPORTS -> exports.get();
        };
        return p == null ? null : p.toAbsolutePath().normalize();
    }

    /** Every root that currently resolves, in declaration order. */
    public Map<WriteRoot, Path> present() {
        Map<WriteRoot, Path> out = new EnumMap<>(WriteRoot.class);
        for (WriteRoot r : WriteRoot.values()) {
            Path p = path(r);
            if (p != null) {
                out.put(r, p);
            }
        }
        return out;
    }
}
