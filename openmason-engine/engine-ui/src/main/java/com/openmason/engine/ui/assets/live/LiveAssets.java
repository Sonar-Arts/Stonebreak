package com.openmason.engine.ui.assets.live;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.ui.assets.ProjectAssetSource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Editor-side change propagation for shared UI assets. Documents open in previews are
 * {@link #track tracked}; when a project file or asset is saved, every cache drops that id
 * and listeners receive the documents to refresh — directly or through components. Nothing
 * reloads or recomposites until a cache is next asked for the id.
 *
 * <p>Tracking records each shared row's project hint and convention paths whether or not the
 * asset currently resolves, so a missing file that appears later (or a relink target) still
 * reaches the documents waiting for it.
 */
public final class LiveAssets {

    private final DependencyIndex index = new DependencyIndex();
    private final List<UiAssetCache<?>> caches = new CopyOnWriteArrayList<>();
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();

    public void addCache(UiAssetCache<?> cache) {
        caches.add(cache);
    }

    /** Receives the ids of documents whose previews must refresh (sorted, never empty). */
    public void addListener(Consumer<Set<String>> affectedDocuments) {
        listeners.add(affectedDocuments);
    }

    public DependencyIndex index() {
        return index;
    }

    /**
     * Starts (or refreshes) tracking a document under its document id. Call again after the
     * document's dependency table changes (embed, extract, relink).
     */
    public void track(OmuiArchive doc, ProjectAssetSource project) {
        String key = doc.manifest().documentId();
        index.put(key, doc.dependencies().entries().stream().map(UiDependency::id).toList());
        for (UiDependency row : doc.dependencies().entries()) {
            if (row.mode() != UiDependency.Mode.SHARED || project == null) {
                continue;
            }
            if (row.sourceHint() != null && EntryPaths.problem(row.sourceHint()) == null) {
                index.location(key, row.id(), row.sourceHint());
            }
            for (String path : project.conventionCandidates(row.id(), row.kind())) {
                index.location(key, row.id(), path);
            }
        }
    }

    public void untrack(String documentId) {
        index.remove(documentId);
    }

    /**
     * A shared asset (texture, component, script, ...) was saved or relinked.
     *
     * @return the documents notified
     */
    public Set<String> assetChanged(String id) {
        return changed(Set.of(id));
    }

    /** A project file was written, created or deleted; maps it to the asset ids it serves. */
    public Set<String> projectFileChanged(String projectPath) {
        return changed(index.idsAt(projectPath));
    }

    private Set<String> changed(Set<String> ids) {
        Set<String> affected = new TreeSet<>(UiValue.KEY_ORDER);
        for (String id : ids) {
            Set<String> dependents = index.dependents(id);
            for (UiAssetCache<?> cache : caches) {
                cache.invalidate(id);
                dependents.forEach(cache::invalidate); // components that embed the asset re-instance too
            }
            affected.addAll(dependents);
        }
        if (!affected.isEmpty()) {
            Set<String> view = Collections.unmodifiableSet(affected);
            listeners.forEach(l -> l.accept(view));
        }
        return affected;
    }
}
