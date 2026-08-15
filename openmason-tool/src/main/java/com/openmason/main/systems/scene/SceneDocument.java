package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelScene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The open scene: what is placed, where it came from, and whether it needs saving.
 *
 * <p>Composition rather than a parallel hierarchy — the engine's {@link ModelScene} owns
 * the instances and their transforms, and this adds only what the <em>file format</em>
 * needs: per-model provenance ({@link SceneModelRef}) plus session bookkeeping. Keeping
 * placement in the engine is what lets the same scene be rendered by a viewer that knows
 * nothing about projects or files.
 */
public class SceneDocument {

    private final ModelScene scene = new ModelScene();

    /** Session id -> provenance, in insertion order so saves are stable. */
    private final Map<String, SceneModelRef> modelsBySessionId = new LinkedHashMap<>();

    /** Reverse lookup, so an instance's handle can find its provenance. */
    private final Map<ModelHandle, SceneModelRef> refByHandle = new LinkedHashMap<>();

    private String sceneName = "Untitled Scene";
    private String currentScenePath;
    private String createdAt;
    private boolean dirty;

    public ModelScene scene() {
        return scene;
    }

    // ------------------------------------------------------------------ models

    public void registerModel(SceneModelRef ref) {
        modelsBySessionId.put(ref.sessionId(), ref);
        if (ref.handle() != null) {
            refByHandle.put(ref.handle(), ref);
        }
    }

    public SceneModelRef modelBySessionId(String sessionId) {
        return modelsBySessionId.get(sessionId);
    }

    public SceneModelRef modelFor(ModelInstance instance) {
        return instance == null ? null : refByHandle.get(instance.model());
    }

    public List<SceneModelRef> models() {
        return List.copyOf(modelsBySessionId.values());
    }

    /** Models currently backed only by an embedded copy — the "Import to project" set. */
    public List<SceneModelRef> modelsNeedingImport() {
        List<SceneModelRef> out = new ArrayList<>();
        for (SceneModelRef ref : modelsBySessionId.values()) {
            if (ref.status().needsImport()) {
                out.add(ref);
            }
        }
        return out;
    }

    /**
     * Swap in a freshly loaded handle for a model, keeping its session id.
     *
     * <p>This is the edit round-trip: saving a model in the editor replaces the geometry
     * behind every instance at once, with no instance re-keying.
     */
    public void replaceHandle(String sessionId, ModelHandle newHandle) {
        SceneModelRef ref = modelsBySessionId.get(sessionId);
        if (ref == null) {
            return;
        }
        if (ref.handle() != null) {
            refByHandle.remove(ref.handle());
        }
        ref.setHandle(newHandle);
        if (newHandle != null) {
            refByHandle.put(newHandle, ref);
        }
    }

    // --------------------------------------------------------------- instances

    public ModelInstance addInstance(SceneModelRef model, String name) {
        ModelInstance instance = scene.add(model.handle(), name);
        markDirty();
        return instance;
    }

    public boolean removeInstance(ModelInstance instance) {
        boolean removed = scene.remove(instance);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    /**
     * Copy an instance, offset slightly so the duplicate is visibly distinct rather than
     * hidden exactly inside the original.
     */
    public ModelInstance duplicateInstance(ModelInstance source, float offset) {
        if (source == null) {
            return null;
        }
        ModelInstance copy = scene.add(source.model(), uniqueName(source.name()));
        var from = source.transform();
        copy.transform().setPosition(
                from.getPositionX() + offset, from.getPositionY(), from.getPositionZ() + offset);
        copy.transform().setRotation(from.getRotationX(), from.getRotationY(), from.getRotationZ());
        copy.transform().setScale(from.getScaleX(), from.getScaleY(), from.getScaleZ());
        copy.setVisible(source.isVisible());
        markDirty();
        return copy;
    }

    /** Append a numeric suffix until the name is unused, Blender-style. */
    public String uniqueName(String desired) {
        String base = desired == null || desired.isBlank() ? "Instance" : desired;
        if (!nameTaken(base)) {
            return base;
        }
        for (int n = 2; n < 10_000; n++) {
            String candidate = base + " " + n;
            if (!nameTaken(candidate)) {
                return candidate;
            }
        }
        return base;
    }

    private boolean nameTaken(String name) {
        for (ModelInstance instance : scene.instances()) {
            if (name.equals(instance.name())) {
                return true;
            }
        }
        return false;
    }

    public List<ModelInstance> instances() {
        return scene.instances();
    }

    /** Instances placing the model with this session id. */
    public List<ModelInstance> instancesOf(String sessionId) {
        SceneModelRef ref = modelsBySessionId.get(sessionId);
        if (ref == null || ref.handle() == null) {
            return List.of();
        }
        List<ModelInstance> out = new ArrayList<>();
        for (ModelInstance instance : scene.instances()) {
            if (instance.model() == ref.handle()) {
                out.add(instance);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ session

    public String sceneName() { return sceneName; }
    public void setSceneName(String sceneName) { this.sceneName = sceneName; }

    public String currentScenePath() { return currentScenePath; }
    public void setCurrentScenePath(String path) { this.currentScenePath = path; }

    public boolean hasCurrentScene() {
        return currentScenePath != null && !currentScenePath.isBlank();
    }

    public String createdAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void clearDirty() { this.dirty = false; }

    /** Reset to an empty untitled scene, releasing every model reference. */
    public void clear() {
        scene.clear();
        modelsBySessionId.clear();
        refByHandle.clear();
        sceneName = "Untitled Scene";
        currentScenePath = null;
        createdAt = null;
        dirty = false;
    }
}
