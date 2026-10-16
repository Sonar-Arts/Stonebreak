package com.openmason.main.systems.io;

import com.openmason.api.target.GameTarget;
import com.openmason.main.AppPaths;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.target.GameTargets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves the three {@link WriteRoot}s lazily — the project root moves when
 * the user opens another project, the active game target changes with it, and the
 * target's tree is absent from a packaged jar — so every lookup asks the suppliers afresh.
 */
public final class WriteRoots {

    private final Supplier<Path> project;
    private final Supplier<Optional<GameTarget>> target;
    private final Supplier<Path> targetRoot;
    private final Supplier<Path> exports;

    private WriteRoots(Supplier<Path> project, Supplier<Optional<GameTarget>> target, Supplier<Path> targetRoot,
                       Supplier<Path> exports) {
        this.project = project;
        this.target = target;
        this.targetRoot = targetRoot;
        this.exports = exports;
    }

    /** Production wiring; tolerates a null interface (snapshot-test discipline). */
    public static WriteRoots forInterface(EditorServices editorServices) {
        Supplier<Path> project = () -> {
            Supplier<String> dir = editorServices != null
                    ? editorServices.getProjectDirectorySupplier() : null;
            String s = dir != null ? dir.get() : null;
            if (s != null && !s.isBlank()) {
                return Path.of(s);
            }
            Path fallback = AppPaths.defaultProjectsDir();
            return Files.isDirectory(fallback) ? fallback : null;
        };
        GameTargets targets = GameTargets.shared();
        return new WriteRoots(project, targets::active, targets::writableRoot, WriteSandbox::exportsRoot);
    }

    /** Fixed roots for tests, no target descriptor; any of them may be null (= absent). */
    public static WriteRoots of(Path project, Path target, Path exports) {
        return new WriteRoots(() -> project, Optional::empty, () -> target, () -> exports);
    }

    /** Fixed roots for tests with a target descriptor (its tree is the {@code target:} root). */
    public static WriteRoots forTarget(Path project, GameTarget target, Path exports) {
        return new WriteRoots(() -> project, () -> Optional.ofNullable(target),
                () -> target == null || !target.writes().writable() ? null : target.resources().locate(),
                () -> exports);
    }

    /** The game target the {@code target:} root belongs to, if known. */
    public Optional<GameTarget> target() {
        return target.get();
    }

    /** Absolute, normalized root path, or null when that root is unavailable. */
    public Path path(WriteRoot root) {
        Path p = switch (root) {
            case PROJECT -> project.get();
            case TARGET -> targetRoot.get();
            case EXPORTS -> exports.get();
        };
        return p == null ? null : p.toAbsolutePath().normalize();
    }

    /**
     * The root a bare name of {@code kind} lands in: its default root, except that a kind saved into
     * the game target lands in the project when there is no target tree (a project-only project).
     */
    public WriteRoot defaultRoot(WriteKind kind) {
        WriteRoot root = kind.defaultRoot();
        return root == WriteRoot.TARGET && path(WriteRoot.TARGET) == null ? WriteRoot.PROJECT : root;
    }

    /** The folder under {@code kind}'s default root a bare name lands in, resolved against this session's target. */
    public String subdir(WriteKind kind) {
        return defaultRoot(kind) != kind.defaultRoot() ? "" : kind.subdirFor(target().orElse(null));
    }

    /** Whether writes into {@code root} want confirmation under the default policy. */
    public boolean confirms(WriteRoot root) {
        return root == WriteRoot.TARGET && target().map(t -> t.writes().confirmWrites()).orElse(true);
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
