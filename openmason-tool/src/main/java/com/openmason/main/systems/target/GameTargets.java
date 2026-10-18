package com.openmason.main.systems.target;

import com.openmason.api.target.GameTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The game targets plugins contributed and the one the editor currently works against (#380).
 *
 * <p>With a project open, the active target is the project's (none when it names none, or names
 * one no plugin provides). With no project, it is the {@link #defaultTarget() default}: the first
 * registered target, so the editor's standalone tools keep working. Export dialogs, the asset
 * catalog, the write sandbox and the UI editor all ask {@link #active()} afresh, so a target
 * switch applies at once.
 */
public final class GameTargets {

    /** Namespace ids are qualified with when there is no target (a project-only project). */
    public static final String NO_TARGET_NAMESPACE = "custom";

    private static final GameTargets SHARED = new GameTargets(List::of);

    private volatile Supplier<List<GameTarget>> source;
    private volatile boolean projectOpen;
    private volatile String projectTargetId;

    /** Targets read from {@code source} at each lookup (tests pass a fixed list). */
    public GameTargets(Supplier<List<GameTarget>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** The process-wide registry (fed from the plugin contributions at startup). */
    public static GameTargets shared() {
        return SHARED;
    }

    /** Where the registered targets come from (the plugin registry's live view). */
    public void setSource(Supplier<List<GameTarget>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Every registered target, in registration order. */
    public List<GameTarget> all() {
        List<GameTarget> targets = source.get();
        return targets == null ? List.of() : targets;
    }

    public Optional<GameTarget> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** The target a new project defaults to and the editor uses with no project open: the first registered. */
    public Optional<GameTarget> defaultTarget() {
        List<GameTarget> targets = all();
        return targets.isEmpty() ? Optional.empty() : Optional.of(targets.getFirst());
    }

    /** The id of the target that claims projects saved before targets existed, or null. */
    public String legacyTargetId() {
        return all().stream().filter(GameTarget::legacyDefault).map(GameTarget::id).findFirst().orElse(null);
    }

    /** A project targeting {@code targetId} (null = none) is now open. */
    public void projectOpened(String targetId) {
        this.projectTargetId = targetId == null || targetId.isBlank() ? null : targetId;
        this.projectOpen = true;
    }

    /** No project is open any more: the default target applies again. */
    public void projectClosed() {
        this.projectOpen = false;
        this.projectTargetId = null;
    }

    /** True when the open project names a target that no plugin provides. */
    public boolean isProjectTargetMissing() {
        String id = projectTargetId;
        return projectOpen && id != null && find(id).isEmpty();
    }

    /** The target the editor works against right now, if any. */
    public Optional<GameTarget> active() {
        return projectOpen ? find(projectTargetId) : defaultTarget();
    }

    /** {@code local} in the active target's namespace ({@code oak_planks} → {@code stonebreak:oak_planks}). */
    public String qualify(String local) {
        return active().map(GameTarget::defaultNamespace).orElse(NO_TARGET_NAMESPACE) + ":" + local;
    }

    /**
     * Suggested file name for an exported asset, following the active target's shipped convention:
     * its file prefix ({@code SB_}) + title-cased words joined by underscores + extension
     * ({@code Oak Planks} → {@code SB_Oak_Planks.sbo}). {@code fallback} when the name has no
     * letters or digits.
     */
    public String suggestedFileName(String objectName, String extension, String fallback) {
        String base = objectName == null ? "" : objectName.trim();
        if (base.isEmpty()) {
            return fallback;
        }
        String prefix = active().map(GameTarget::assetFilePrefix).orElse("");
        StringBuilder sb = new StringBuilder(prefix);
        boolean startOfWord = true;
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(startOfWord ? Character.toUpperCase(c) : c);
                startOfWord = false;
            } else if (!startOfWord) {
                sb.append('_');
                startOfWord = true;
            }
        }
        while (sb.length() > prefix.length() && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() == prefix.length()) {
            return fallback;
        }
        return sb + "." + extension;
    }

    /** Absolute path of the active target's resource tree, or null (no target, or no tree on disk). */
    public Path resourcesRoot() {
        return active().map(t -> t.resources().locate()).orElse(null);
    }

    /** The active target's tree when the editor may write into it, else null. */
    public Path writableRoot() {
        return active().filter(t -> t.writes().writable()).map(t -> t.resources().locate()).orElse(null);
    }

    /**
     * The existing folder the active target keeps {@code type} assets of manifest type
     * {@code subtype} in: the subtype's folder, else the type's base folder; null when neither
     * exists (or there is no target or tree). A null {@code subtype} asks for the base folder.
     */
    public Path folderFor(String type, String subtype) {
        Optional<GameTarget> t = active();
        Path root = resourcesRoot();
        if (t.isEmpty() || root == null) {
            return null;
        }
        for (String rel : new String[]{t.get().folders().folderFor(type, subtype), t.get().folders().folder(type)}) {
            if (rel != null) {
                Path dir = root.resolve(rel);
                if (Files.isDirectory(dir)) {
                    return dir;
                }
            }
        }
        return null;
    }

    /** {@link #folderFor} as a string, for the native file dialogs. */
    public String folderPathFor(String type, String subtype) {
        Path dir = folderFor(type, subtype);
        return dir == null ? null : dir.toString();
    }

    /** True when {@code dir} is inside the active target's resource tree. */
    public boolean isInsideResources(String dir) {
        Path root = resourcesRoot();
        if (root == null || dir == null) {
            return false;
        }
        try {
            return Path.of(dir).toAbsolutePath().normalize().startsWith(root.normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
