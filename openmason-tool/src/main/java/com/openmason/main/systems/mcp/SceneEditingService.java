package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.scene.SceneDocument;
import com.openmason.main.systems.scene.SceneModelRef;
import com.openmason.main.systems.scene.SceneService;
import com.openmason.main.systems.scene.SceneViewerActions;
import com.openmason.main.systems.scene.SceneViewerImGuiInterface;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-safe facade over the Scene Viewer for the MCP tools: inspect the open scene,
 * place / transform / rename / show / lock / delete / duplicate instances, drive the
 * selection, and new / open / save the scene.
 *
 * <p>Every mutation goes through {@link SceneViewerActions}, so an MCP edit lands in
 * the same undo history, marks the scene dirty and re-aims the gizmo exactly as a UI
 * edit would. Every method marshals to the main/GL thread via {@link MainThreadExecutor}
 * and blocks the caller, mirroring {@link AttachmentEditingService}.
 */
public final class SceneEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final MainImGuiInterface mainInterface;

    public SceneEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    // ---------------------------------------------------------------- views

    public record Vec3(float x, float y, float z) {
        static Vec3 of(float x, float y, float z) { return new Vec3(x, y, z); }
    }

    public record InstanceView(String id, String name, String modelId, String modelName,
                               Vec3 position, Vec3 rotation, Vec3 scale,
                               boolean visible, boolean locked, boolean selected) {
        static InstanceView from(SceneDocument document, ModelInstance i, boolean selected) {
            SceneModelRef ref = document.modelFor(i);
            var t = i.transform();
            return new InstanceView(i.id(), i.name(),
                    ref != null ? ref.sessionId() : null,
                    ref != null ? ref.sourceName() : null,
                    Vec3.of(t.getPositionX(), t.getPositionY(), t.getPositionZ()),
                    Vec3.of(t.getRotationX(), t.getRotationY(), t.getRotationZ()),
                    Vec3.of(t.getScaleX(), t.getScaleY(), t.getScaleZ()),
                    i.isVisible(), i.isLocked(), selected);
        }
    }

    public record ModelView(String id, String sourceName, String path, String status, int instanceCount) {}

    public record SceneInfo(String name, String path, boolean dirty, String author, String description,
                            int instanceCount, int modelCount, int orphanCount,
                            List<String> selectedIds, boolean canUndo, boolean canRedo,
                            String undoDescription, String redoDescription) {}

    // ---------------------------------------------------------------- reads

    public SceneInfo getInfo() {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            SceneDocument doc = viewer.getDocument();
            SceneViewerActions actions = viewer.getActions();
            return new SceneInfo(doc.sceneName(), doc.currentScenePath(), doc.isDirty(),
                    doc.author(), doc.description(),
                    doc.instances().size(), doc.models().size(), doc.orphanInstances().size(),
                    viewer.getSelection().selectedIds(),
                    actions.canUndo(), actions.canRedo(),
                    actions.canUndo() ? actions.undoDescription() : null,
                    actions.canRedo() ? actions.redoDescription() : null);
        }));
    }

    public List<InstanceView> listInstances() {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            SceneDocument doc = viewer.getDocument();
            List<InstanceView> out = new ArrayList<>();
            for (ModelInstance i : doc.instances()) {
                out.add(InstanceView.from(doc, i, viewer.getSelection().isSelected(i.id())));
            }
            return out;
        }));
    }

    public List<ModelView> listModels() {
        return await(MainThreadExecutor.submit(() -> {
            SceneDocument doc = requireViewer().getDocument();
            List<ModelView> out = new ArrayList<>();
            for (SceneModelRef ref : doc.models()) {
                out.add(new ModelView(ref.sessionId(), ref.sourceName(),
                        ref.sourcePath() != null ? ref.sourcePath().toString() : ref.relativePath(),
                        ref.status().name(), doc.instancesOf(ref.sessionId()).size()));
            }
            return out;
        }));
    }

    public InstanceView getInstance(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            ModelInstance i = resolve(viewer.getDocument(), idOrName);
            return InstanceView.from(viewer.getDocument(), i, viewer.getSelection().isSelected(i.id()));
        }));
    }

    // -------------------------------------------------------------- mutations

    public InstanceView place(String omoPath, String name, Vector3f position, Vector3f rotation, Vector3f scale) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            SceneService service = viewer.getSceneService();
            Path path = resolveOmoPath(omoPath);
            SceneModelRef ref;
            try {
                ref = service.addModelFromFile(path, projectRoot());
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot load model " + path + ": " + e.getMessage(), e);
            }
            String instanceName = name != null && !name.isBlank()
                    ? name
                    : (ref.sourceName() != null ? ref.sourceName().replaceFirst("(?i)\\.omo$", "") : "Instance");
            Vector3f p = position != null ? position : new Vector3f();
            ModelInstance instance = viewer.getActions().place(ref, instanceName, p.x, p.y, p.z);
            if (rotation != null || scale != null) {
                // Fold the initial pose into the placement rather than a second undo step.
                var t = instance.transform();
                if (rotation != null) t.setRotation(rotation.x, rotation.y, rotation.z);
                if (scale != null) t.setScale(scale.x, scale.y, scale.z);
                viewer.getActions().syncGizmoToSelection();
            }
            return InstanceView.from(viewer.getDocument(), instance, true);
        }));
    }

    public InstanceView setTransform(String idOrName, Vector3f position, Vector3f rotation, Vector3f scale) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            ModelInstance i = resolve(viewer.getDocument(), idOrName);
            if (i.isLocked()) {
                throw new IllegalStateException("Instance '" + i.name() + "' is locked");
            }
            viewer.getActions().setTransform(i, position, rotation, scale);
            return InstanceView.from(viewer.getDocument(), i, viewer.getSelection().isSelected(i.id()));
        }));
    }

    public InstanceView setProperties(String idOrName, String name, Boolean visible, Boolean locked) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            ModelInstance i = resolve(viewer.getDocument(), idOrName);
            SceneViewerActions actions = viewer.getActions();
            if (name != null) actions.rename(i, name);
            if (visible != null) actions.setVisible(i, visible);
            if (locked != null) actions.setLocked(i, locked);
            return InstanceView.from(viewer.getDocument(), i, viewer.getSelection().isSelected(i.id()));
        }));
    }

    public McpAck delete(List<String> idsOrNames) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            selectAll(viewer, idsOrNames);
            viewer.getActions().deleteSelected();
            return McpAck.ok();
        }));
    }

    public List<InstanceView> duplicate(List<String> idsOrNames) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            selectAll(viewer, idsOrNames);
            viewer.getActions().duplicateSelected();
            List<InstanceView> out = new ArrayList<>();
            for (ModelInstance i : viewer.getActions().selected()) {
                out.add(InstanceView.from(viewer.getDocument(), i, true));
            }
            return out;
        }));
    }

    public List<String> select(List<String> idsOrNames) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            if (idsOrNames == null || idsOrNames.isEmpty()) {
                viewer.getActions().clearSelection();
            } else {
                selectAll(viewer, idsOrNames);
            }
            return viewer.getSelection().selectedIds();
        }));
    }

    public McpAck focus(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerImGuiInterface viewer = requireViewer();
            if (idOrName == null || idOrName.isBlank()) {
                viewer.getActions().frameAll();
            } else {
                viewer.getActions().select(resolve(viewer.getDocument(), idOrName).id());
                viewer.getActions().focusSelected();
            }
            return McpAck.ok();
        }));
    }

    // ------------------------------------------------------------ file ops

    public SceneInfo newScene(String name) {
        await(MainThreadExecutor.submit(() -> {
            requireViewer().getSceneService().newScene(name);
            return Boolean.TRUE;
        }));
        return getInfo();
    }

    public SceneInfo open(String path) {
        boolean ok = await(MainThreadExecutor.submit(() ->
                requireViewer().getSceneService().openScene(resolveScenePath(path), projectRoot())));
        if (!ok) {
            throw new IllegalArgumentException("Could not open scene: " + path);
        }
        return getInfo();
    }

    public SceneInfo save(String path) {
        boolean ok = await(MainThreadExecutor.submit(() -> {
            SceneService service = requireViewer().getSceneService();
            if (path != null && !path.isBlank()) {
                return service.saveSceneAs(resolveScenePath(path), projectRoot());
            }
            if (service.hasCurrentScene()) {
                return service.saveScene(projectRoot());
            }
            return service.saveIntoProject(projectRoot());
        }));
        if (!ok) {
            throw new IllegalStateException("Scene save failed — see the Open Mason log");
        }
        return getInfo();
    }

    public Map<String, Object> undo() {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerActions actions = requireViewer().getActions();
            String what = actions.canUndo() ? actions.undoDescription() : null;
            actions.undo();
            return Map.of("undone", what != null ? what : "", "canUndo", actions.canUndo());
        }));
    }

    public Map<String, Object> redo() {
        return await(MainThreadExecutor.submit(() -> {
            SceneViewerActions actions = requireViewer().getActions();
            String what = actions.canRedo() ? actions.redoDescription() : null;
            actions.redo();
            return Map.of("redone", what != null ? what : "", "canRedo", actions.canRedo());
        }));
    }

    // ---------------------------------------------------------------- helpers

    private SceneViewerImGuiInterface requireViewer() {
        SceneViewerImGuiInterface viewer = mainInterface != null ? mainInterface.getSceneViewer() : null;
        if (viewer == null) {
            throw new IllegalStateException("The Scene Viewer is not available (is a project open?)");
        }
        return viewer;
    }

    private Path projectRoot() {
        String dir = mainInterface.getProjectDirectorySupplier() != null
                ? mainInterface.getProjectDirectorySupplier().get() : null;
        return dir == null ? null : Path.of(dir);
    }

    /** Absolute as given; otherwise relative to the project root. */
    private Path resolveOmoPath(String omoPath) {
        if (omoPath == null || omoPath.isBlank()) {
            throw new IllegalArgumentException("omo_path is required");
        }
        Path p = Path.of(omoPath);
        if (!p.isAbsolute() && projectRoot() != null) {
            p = projectRoot().resolve(p);
        }
        if (!java.nio.file.Files.isRegularFile(p)) {
            throw new IllegalArgumentException("No such .omo file: " + p);
        }
        return p.toAbsolutePath();
    }

    /** Absolute as given; a bare name goes into the project's Scenes/ folder. */
    private String resolveScenePath(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute() || projectRoot() == null) {
            return p.toString();
        }
        if (p.getNameCount() == 1) {
            return com.openmason.main.systems.project.ProjectLayout.scenesDir(projectRoot())
                    .resolve(p).toString();
        }
        return projectRoot().resolve(p).toString();
    }

    private static ModelInstance resolve(SceneDocument document, String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new IllegalArgumentException("instance id or name is required");
        }
        ModelInstance byId = document.scene().byId(idOrName);
        if (byId != null) {
            return byId;
        }
        ModelInstance match = null;
        for (ModelInstance i : document.instances()) {
            if (idOrName.equals(i.name())) {
                if (match != null) {
                    throw new IllegalArgumentException("Instance name '" + idOrName
                            + "' is ambiguous — use the id");
                }
                match = i;
            }
        }
        if (match == null) {
            List<String> known = new ArrayList<>();
            for (ModelInstance i : document.instances()) {
                known.add(i.name() + " (" + i.id() + ")");
            }
            throw new IllegalArgumentException("No scene instance '" + idOrName + "'. Known: " + known);
        }
        return match;
    }

    private static void selectAll(SceneViewerImGuiInterface viewer, List<String> idsOrNames) {
        if (idsOrNames == null || idsOrNames.isEmpty()) {
            throw new IllegalArgumentException("at least one instance id or name is required");
        }
        viewer.getSelection().clear();
        for (String ref : idsOrNames) {
            viewer.getSelection().toggle(resolve(viewer.getDocument(), ref).id());
        }
        viewer.getActions().syncGizmoToSelection();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
