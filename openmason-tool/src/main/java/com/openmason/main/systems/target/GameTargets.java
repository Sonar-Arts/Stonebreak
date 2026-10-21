package com.openmason.main.systems.target;

import com.openmason.api.target.GameTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The registered {@link GameTarget}s and which one the editor currently works for (#380).
 *
 * <p>With a project open, the active target is the one its {@code .omp} names; a project that
 * names none (or a target whose plugin is missing) has no active target, and the editor works as
 * a project-only editor. With no project open, the first registered target is active, so a
 * session without a project behaves as before targets existed.
 *
 * <p>One process-wide instance ({@link #shared()}) serves the export dialogs, the asset catalog
 * and the write sandbox; tests build their own.
 */
public final class GameTargets {

    /** Namespace of new ids when no target is active. */
    public static final String FALLBACK_NAMESPACE = "custom";

    private static final GameTargets SHARED = new GameTargets(List::of);

    /** Process-wide instance; its targets come from the plugin registry once plugins registered. */
    public static GameTargets shared() {
        return SHARED;
    }

    /** Hears the active target change (project opened or closed, target switched). */
    @FunctionalInterface
    public interface Listener {
        void activeTargetChanged(GameTarget active);
    }

    private volatile Supplier<List<GameTarget>> source;
    private volatile boolean projectOpen;
    private volatile String projectTargetId;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public GameTargets(Supplier<List<GameTarget>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Targets come from {@code source} (the registered targets), read on every query. */
    public void setSource(Supplier<List<GameTarget>> source) {
        this.source = Objects.requireNonNull(source, "source");
        fire();
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ------------------------------------------------------------------ registry

    /** Every registered target, in registration order. */
    public List<GameTarget> all() {
        List<GameTarget> targets = source.get();
        return targets == null ? List.of() : List.copyOf(targets);
    }

    public Optional<GameTarget> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** The target sessions without a project use: the first registered one. */
    public Optional<GameTarget> defaultTarget() {
        List<GameTarget> all = all();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
    }

    /** The target pre-1.5 projects migrate to (the one claiming {@link GameTarget#legacyDefault()}), or null. */
    public String legacyTargetId() {
        return all().stream().filter(GameTarget::legacyDefault).map(GameTarget::id).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ selection

    /** A project was opened or created; {@code targetId} is its target (null = none). */
    public void projectOpened(String targetId) {
        projectOpen = true;
        projectTargetId = targetId == null || targetId.isBlank() ? null : targetId;
        fire();
    }

    /** No project is open any more. */
    public void projectClosed() {
        projectOpen = false;
        projectTargetId = null;
        fire();
    }

    public boolean isProjectOpen() {
        return projectOpen;
    }

    /** The open project's target id (null = none, or no project open). */
    public String projectTargetId() {
        return projectTargetId;
    }

    /** The target the editor currently works for, if any (see class docs). */
    public Optional<GameTarget> active() {
        return projectOpen ? find(projectTargetId) : defaultTarget();
    }

    /** True when the open project names a target that no plugin registered. */
    public boolean isProjectTargetMissing() {
        return projectOpen && projectTargetId != null && find(projectTargetId).isEmpty();
    }

    // ------------------------------------------------------------------ active-target paths

    /** The active target's resource tree, or null (no target, or its tree is not on disk). */
    public Path resourcesRoot() {
        return active().map(t -> t.resources().locate()).orElse(null);
    }

    /** The resource tree as a write root: null when absent or the target's write rules forbid writes. */
    public Path writableRoot() {
        return active().filter(t -> t.writes().writable()).map(t -> t.resources().locate()).orElse(null);
    }

    /** {@code <resources>/<relative>} when that folder exists, else null. */
    public Path existingFolder(String relative) {
        Path root = resourcesRoot();
        if (root == null || relative == null) {
            return null;
        }
        Path p = root.resolve(relative);
        return Files.isDirectory(p) ? p : null;
    }

    /**
     * The folder an asset of {@code fileType} with manifest type {@code subtype} ships from in the
     * active target, absolute: the subtype's folder when it exists on disk, else the type's base
     * folder; null when there is no target or neither folder exists.
     */
    public Path folderFor(String fileType, String subtype) {
        Optional<GameTarget> t = active();
        if (t.isEmpty()) {
            return null;
        }
        Path specific = existingFolder(t.get().folders().folderFor(fileType, subtype));
        return specific != null ? specific : existingFolder(t.get().folders().folder(fileType));
    }

    /** {@link #folderFor} as a string (what the file dialogs take), or null. */
    public String folderPathFor(String fileType, String subtype) {
        Path p = folderFor(fileType, subtype);
        return p == null ? null : p.toString();
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

    /** The namespace new ids use: the active target's, else {@link #FALLBACK_NAMESPACE}. */
    public String namespace() {
        return active().map(GameTarget::defaultNamespace).orElse(FALLBACK_NAMESPACE);
    }

    /** {@code <namespace>:<localId>} in the active namespace. */
    public String qualify(String localId) {
        return namespace() + ":" + localId;
    }

    /** True when {@code namespacedId} belongs to the active target's namespace. */
    public boolean ownsId(String namespacedId) {
        return active().map(t -> t.owns(namespacedId)).orElse(false);
    }

    /** Suggested file name of an exported asset, with the active target's prefix ({@link #suggestedFileName(String, String, String, String)}). */
    public String suggestedFileName(String objectName, String extension, String fallback) {
        return suggestedFileName(active().map(GameTarget::assetFilePrefix).orElse(""), objectName, extension, fallback);
    }

    /**
     * {@code prefix} + title-cased words of {@code objectName} joined by underscores + extension
     * ({@code "MG_"}, "oak door" → {@code MG_Oak_Door.sbo}); {@code fallback} when the name has no
     * letters or digits.
     */
    public static String suggestedFileName(String prefix, String objectName, String extension, String fallback) {
        String base = objectName == null ? "" : objectName.trim();
        if (base.isEmpty()) {
            return fallback;
        }
        String head = prefix == null ? "" : prefix;
        StringBuilder sb = new StringBuilder(head);
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
        while (sb.length() > head.length() && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() == head.length()) {
            return fallback;
        }
        return sb + "." + extension;
    }

    private void fire() {
        GameTarget current = active().orElse(null);
        for (Listener l : listeners) {
            l.activeTargetChanged(current);
        }
    }
}
