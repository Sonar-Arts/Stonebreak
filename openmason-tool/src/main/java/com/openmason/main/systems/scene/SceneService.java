package com.openmason.main.systems.scene;

import com.openmason.engine.format.omsc.OMSCFormat;
import com.openmason.engine.format.omsc.OMSCParseResult;
import com.openmason.engine.format.omsc.OMSCParser;
import com.openmason.engine.format.omsc.OMSCSerializer;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.viewer.scene.ModelCache;
import com.openmason.engine.rendering.viewer.scene.ModelHandle;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.project.ProjectLayout;
import com.openmason.main.systems.project.ProjectPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scene-level new/open/save, mirroring {@code ProjectService}'s shape.
 *
 * <p>Holds the open {@link SceneDocument} and translates between it and the
 * {@code .omsc} format — including the project-relative path anchoring, which is
 * deliberately tool-side because the engine has no notion of a project.
 */
public class SceneService {

    private static final Logger logger = LoggerFactory.getLogger(SceneService.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SceneDocument document = new SceneDocument();
    private final ModelCache modelCache;
    private final SceneModelResolver resolver;
    private final OMSCSerializer serializer = new OMSCSerializer();
    private final OMSCParser parser = new OMSCParser();

    private Runnable onSceneChanged = () -> { };
    private Runnable onSceneReplaced = () -> { };

    public SceneService(ModelCache modelCache) {
        this.modelCache = java.util.Objects.requireNonNull(modelCache, "modelCache");
        this.resolver = new SceneModelResolver(modelCache);
    }

    public SceneDocument getDocument() {
        return document;
    }

    /** Invoked whenever the scene changes in a way the viewer must redraw. */
    public void setOnSceneChanged(Runnable callback) {
        this.onSceneChanged = callback != null ? callback : () -> { };
    }

    /**
     * Invoked when the open scene is <em>replaced</em> — new, open, or project change —
     * rather than merely edited. This is the signal to drop per-scene state such as the
     * undo history; deliberately separate from {@link #setOnSceneChanged}, which also
     * fires on every ordinary edit.
     */
    public void setOnSceneReplaced(Runnable callback) {
        this.onSceneReplaced = callback != null ? callback : () -> { };
    }

    private void changed() {
        document.markDirty();
        onSceneChanged.run();
    }

    private void sceneReplaced() {
        onSceneReplaced.run();
    }

    // ------------------------------------------------------------------ new

    public void newScene(String sceneName) {
        releaseAllModels();
        document.clear();
        sceneReplaced();
        document.setSceneName(sceneName != null && !sceneName.isBlank() ? sceneName : "Untitled Scene");
        document.setCreatedAt(LocalDateTime.now().format(TIMESTAMP));
        onSceneChanged.run();
        logger.info("New scene: {}", document.sceneName());
    }

    // ------------------------------------------------------------------ open

    public boolean openScene(String filePath, Path projectRoot) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        OMSCParseResult parsed;
        try {
            parsed = parser.parse(Path.of(filePath));
        } catch (IOException e) {
            logger.error("Failed to open scene {}: {}", filePath, e.getMessage());
            return false;
        }

        releaseAllModels();
        document.clear();
        sceneReplaced();
        document.setSceneName(parsed.sceneName());
        document.setCurrentScenePath(filePath);
        document.setCreatedAt(parsed.manifest().createdAt());

        for (OMSCFormat.ModelRef ref : parsed.models()) {
            document.registerModel(resolver.resolve(ref, parsed.bytesFor(ref.modelId()), projectRoot));
        }

        for (OMSCFormat.InstanceEntry entry : parsed.instances()) {
            SceneModelRef model = document.modelBySessionId(entry.modelId());
            if (model == null || model.handle() == null) {
                // The model could not be resolved at all. Keeping the instance out of the
                // engine scene avoids a null handle, but it is still recorded in the
                // document's model list so the user is told what is missing.
                logger.warn("Skipping instance '{}': model '{}' is unavailable", entry.name(), entry.modelId());
                continue;
            }
            ModelInstance instance = document.addInstance(model, entry.name());
            OMOFormat.ModelTransform t = entry.transform();
            instance.transform().setPosition(t.posX(), t.posY(), t.posZ());
            instance.transform().setRotation(t.rotX(), t.rotY(), t.rotZ());
            instance.transform().setScale(t.scaleX(), t.scaleY(), t.scaleZ());
            instance.setVisible(entry.visible());
            instance.setLocked(entry.locked());
        }

        document.clearDirty();
        onSceneChanged.run();
        logger.info("Opened scene '{}' ({} instances, {} models)",
                document.sceneName(), document.instances().size(), document.models().size());
        return true;
    }

    // ------------------------------------------------------------------ save

    public boolean saveScene(Path projectRoot) {
        if (!document.hasCurrentScene()) {
            logger.warn("No scene path set; use saveSceneAs instead");
            return false;
        }
        return writeTo(document.currentScenePath(), projectRoot);
    }

    public boolean saveSceneAs(String filePath, Path projectRoot) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        String path = OMSCFormat.ensureExtension(filePath);
        ProjectLayout.ensureScaffold(projectRoot);
        if (writeTo(path, projectRoot)) {
            document.setCurrentScenePath(path);
            document.setSceneName(deriveName(path));
            return true;
        }
        return false;
    }

    private boolean writeTo(String filePath, Path projectRoot) {
        OMSCFormat.Document doc = extractState(projectRoot);
        Map<String, byte[]> bytes = collectModelBytes();
        if (bytes == null) {
            return false;
        }
        if (serializer.save(doc, bytes, filePath)) {
            document.clearDirty();
            logger.info("Saved scene to {}", filePath);
            return true;
        }
        return false;
    }

    /** Build the format document from the live scene, anchoring paths at the project root. */
    public OMSCFormat.Document extractState(Path projectRoot) {
        List<OMSCFormat.ModelRef> models = new ArrayList<>();
        for (SceneModelRef ref : document.models()) {
            String storedPath = ref.sourcePath() != null
                    ? ProjectPaths.relativize(projectRoot, ref.sourcePath().toString())
                    : ref.relativePath();
            models.add(new OMSCFormat.ModelRef(
                    ref.sessionId(),
                    storedPath,
                    ref.sourceName(),
                    OMSCFormat.modelEntryPath(ref.sessionId()),
                    "",   // recomputed by the serializer from the actual bytes
                    0));
        }

        List<OMSCFormat.InstanceEntry> instances = new ArrayList<>();
        for (ModelInstance instance : document.instances()) {
            SceneModelRef ref = document.modelFor(instance);
            if (ref == null) {
                continue;
            }
            var t = instance.transform();
            instances.add(new OMSCFormat.InstanceEntry(
                    instance.id(), instance.name(), ref.sessionId(),
                    new OMOFormat.ModelTransform(
                            t.getPositionX(), t.getPositionY(), t.getPositionZ(),
                            t.getRotationX(), t.getRotationY(), t.getRotationZ(),
                            t.getScaleX(), t.getScaleY(), t.getScaleZ()),
                    instance.isVisible(), instance.isLocked()));
        }

        String now = LocalDateTime.now().format(TIMESTAMP);
        return new OMSCFormat.Document(
                OMSCFormat.FORMAT_VERSION,
                document.sceneName(), null, null,
                document.createdAt() != null ? document.createdAt() : now,
                now,
                models, instances,
                null, null);
    }

    /**
     * Gather each model's bytes for embedding: re-read from the source file when there is
     * one (so a save also refreshes a drifted reference), otherwise reuse the copy the
     * scene was opened with.
     */
    private Map<String, byte[]> collectModelBytes() {
        Map<String, byte[]> bytes = new HashMap<>();
        for (SceneModelRef ref : document.models()) {
            byte[] data = null;
            if (ref.sourcePath() != null) {
                try {
                    data = java.nio.file.Files.readAllBytes(ref.sourcePath());
                } catch (IOException e) {
                    logger.warn("Cannot re-read {}, embedding the previous copy: {}",
                            ref.sourcePath(), e.getMessage());
                }
            }
            if (data == null) {
                data = ref.embeddedBytes();
            }
            if (data == null) {
                logger.error("No bytes available for model '{}'; cannot save", ref.sourceName());
                return null;
            }
            bytes.put(ref.sessionId(), data);
        }
        return bytes;
    }

    // ------------------------------------------------------------- operations

    /** Load a model from disk and register it, ready to be placed. */
    public SceneModelRef addModelFromFile(Path omoPath, Path projectRoot) throws IOException {
        ModelHandle handle = modelCache.acquire(omoPath);
        SceneModelRef ref = new SceneModelRef(
                UUID.randomUUID().toString(),
                omoPath.getFileName().toString(),
                omoPath,
                ProjectPaths.relativize(projectRoot, omoPath.toString()),
                null,
                handle,
                ResolutionStatus.REFERENCED);
        document.registerModel(ref);
        return ref;
    }

    /** Place a model in the scene. */
    public ModelInstance placeInstance(SceneModelRef model, String name, float x, float y, float z) {
        ModelInstance instance = document.addInstance(model, document.uniqueName(name));
        instance.transform().setPosition(x, y, z);
        changed();
        return instance;
    }

    public int importMissingModelsToProject(Path projectRoot) {
        int imported = 0;
        for (SceneModelRef ref : document.modelsNeedingImport()) {
            if (resolver.importToProject(ref, projectRoot)) {
                imported++;
            }
        }
        if (imported > 0) {
            changed();
        }
        return imported;
    }

    /**
     * Re-load a model whose file changed — the model-editor save hook.
     *
     * <p>Replaces the handle in place under the same session id, so every instance
     * placing that model updates at once.
     */
    public boolean reloadModel(String absolutePath) {
        if (absolutePath == null) {
            return false;
        }
        Path path = Path.of(absolutePath).toAbsolutePath();
        for (SceneModelRef ref : document.models()) {
            if (ref.sourcePath() == null || !ref.sourcePath().toAbsolutePath().equals(path)) {
                continue;
            }
            try {
                ModelHandle old = ref.handle();
                ModelHandle fresh = modelCache.acquire(path);
                document.replaceHandle(ref.sessionId(), fresh);
                ref.setStatus(ResolutionStatus.REFERENCED);
                modelCache.release(old);
                onSceneChanged.run();
                logger.info("Reloaded scene model {}", path.getFileName());
                return true;
            } catch (IOException e) {
                logger.error("Failed to reload {}: {}", path, e.getMessage());
                return false;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- accessors

    public boolean hasUnsavedChanges() { return document.isDirty(); }
    public void markDirty() { changed(); }
    public boolean hasCurrentScene() { return document.hasCurrentScene(); }
    public String getCurrentScenePath() { return document.currentScenePath(); }
    public String getCurrentSceneName() { return document.sceneName(); }

    /**
     * Drop the open scene entirely — used when the project changes or the editor session
     * ends, so a scene never outlives the project whose models it references.
     */
    public void clearCurrentScene() {
        releaseAllModels();
        document.clear();
        sceneReplaced();
        onSceneChanged.run();
    }

    /**
     * Hand every model back to the cache.
     *
     * <p>Each {@link SceneModelRef} holds a reference taken by {@code acquire}, separate
     * from the ones the instances hold. Clearing the document only releases the instance
     * references, so without this the cache would keep the model — and its GPU textures —
     * alive for the rest of the session, across every project the user opens.
     */
    private void releaseAllModels() {
        for (SceneModelRef ref : document.models()) {
            modelCache.release(ref.handle());
        }
    }

    private static String deriveName(String filePath) {
        String fileName = Path.of(filePath).getFileName().toString();
        return fileName.toLowerCase().endsWith(OMSCFormat.FILE_EXTENSION)
                ? fileName.substring(0, fileName.length() - OMSCFormat.FILE_EXTENSION.length())
                : fileName;
    }
}
