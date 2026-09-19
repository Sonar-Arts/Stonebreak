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

    // View-state bridge: the camera and display toggles live in the viewer, which the
    // service must not depend on. Suppliers are read at save, consumers fed at open.
    private java.util.function.Supplier<OMSCFormat.CameraState> cameraOut = () -> null;
    private java.util.function.Supplier<OMSCFormat.ViewportState> viewportOut = () -> null;
    private java.util.function.Consumer<OMSCFormat.CameraState> cameraIn = c -> { };
    private java.util.function.Consumer<OMSCFormat.ViewportState> viewportIn = v -> { };

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

    /**
     * Connect the viewer's camera and display state so they round-trip through the file.
     * Any argument may be null to leave that direction unwired.
     */
    public void setViewStateBridge(java.util.function.Supplier<OMSCFormat.CameraState> cameraOut,
                                   java.util.function.Supplier<OMSCFormat.ViewportState> viewportOut,
                                   java.util.function.Consumer<OMSCFormat.CameraState> cameraIn,
                                   java.util.function.Consumer<OMSCFormat.ViewportState> viewportIn) {
        this.cameraOut = cameraOut != null ? cameraOut : () -> null;
        this.viewportOut = viewportOut != null ? viewportOut : () -> null;
        this.cameraIn = cameraIn != null ? cameraIn : c -> { };
        this.viewportIn = viewportIn != null ? viewportIn : v -> { };
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
        document.setAuthor(parsed.manifest().author());
        document.setDescription(parsed.manifest().description());

        for (OMSCFormat.ModelRef ref : parsed.models()) {
            document.registerModel(resolver.resolve(ref, parsed.bytesFor(ref.modelId()), projectRoot));
        }

        for (OMSCFormat.InstanceEntry entry : parsed.instances()) {
            SceneModelRef model = document.modelBySessionId(entry.modelId());
            if (model == null || model.handle() == null) {
                // The model could not be resolved at all. There is nothing to render or
                // pick, but the placement is kept on paper so the next save does not
                // silently drop it; the outliner lists it as missing.
                logger.warn("Instance '{}' kept as an orphan: model '{}' is unavailable",
                        entry.name(), entry.modelId());
                document.addOrphanInstance(entry);
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

        // The saved viewpoint and display toggles, when the file carries them (a 1.0 file
        // written before they were persisted has neither, and the viewer keeps its own).
        if (parsed.manifest().camera() != null) {
            cameraIn.accept(parsed.manifest().camera());
        }
        if (parsed.manifest().viewport() != null) {
            viewportIn.accept(parsed.manifest().viewport());
        }

        document.clearDirty();
        onSceneChanged.run();
        logger.info("Opened scene '{}' ({} instances, {} models, {} orphaned placements)",
                document.sceneName(), document.instances().size(), document.models().size(),
                document.orphanInstances().size());
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

    /**
     * Save without asking: to the current path when there is one, otherwise into the
     * project's {@code Scenes/} folder under the scene's name (uniquified against files
     * already there, so an untitled scene never overwrites a sibling). This is the
     * save-on-exit / save-with-project path, where a modal Save As would be a second
     * dialog stacked on the first.
     *
     * @return false when there is no project to save into either
     */
    public boolean saveIntoProject(Path projectRoot) {
        if (document.hasCurrentScene()) {
            return saveScene(projectRoot);
        }
        if (projectRoot == null) {
            logger.warn("Cannot save untitled scene '{}': no project is open", document.sceneName());
            return false;
        }
        ProjectLayout.ensureScaffold(projectRoot);
        Path scenesDir = ProjectLayout.scenesDir(projectRoot);
        String base = safeFileName(document.sceneName());
        Path target = scenesDir.resolve(base + OMSCFormat.FILE_EXTENSION);
        for (int n = 2; java.nio.file.Files.exists(target) && n < 10_000; n++) {
            target = scenesDir.resolve(base + " " + n + OMSCFormat.FILE_EXTENSION);
        }
        return saveSceneAs(target.toString(), projectRoot);
    }

    private static String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "Untitled Scene" : cleaned;
    }

    private boolean writeTo(String filePath, Path projectRoot) {
        Map<String, byte[]> bytes = collectModelBytes();
        OMSCFormat.Document doc = extractState(projectRoot, bytes.keySet());
        if (serializer.save(doc, bytes, filePath)) {
            document.clearDirty();
            logger.info("Saved scene to {}", filePath);
            return true;
        }
        return false;
    }

    /** Build the format document from the live scene, anchoring paths at the project root. */
    public OMSCFormat.Document extractState(Path projectRoot) {
        return extractState(projectRoot, null);
    }

    /**
     * @param saveable session ids of the models whose bytes are available for embedding;
     *                 null means all. A model outside the set — and every placement of
     *                 it — is left out, because the format cannot record a model it has
     *                 no bytes for.
     */
    private OMSCFormat.Document extractState(Path projectRoot, java.util.Set<String> saveable) {
        List<OMSCFormat.ModelRef> models = new ArrayList<>();
        for (SceneModelRef ref : document.models()) {
            if (saveable != null && !saveable.contains(ref.sessionId())) {
                continue;
            }
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
            if (ref == null || (saveable != null && !saveable.contains(ref.sessionId()))) {
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
        // Placements whose model never loaded ride along untouched.
        for (OMSCFormat.InstanceEntry orphan : document.orphanInstances()) {
            if (saveable == null || saveable.contains(orphan.modelId())) {
                instances.add(orphan);
            }
        }

        String now = LocalDateTime.now().format(TIMESTAMP);
        return new OMSCFormat.Document(
                OMSCFormat.FORMAT_VERSION,
                document.sceneName(), document.author(), document.description(),
                document.createdAt() != null ? document.createdAt() : now,
                now,
                models, instances,
                cameraOut.get(), viewportOut.get());
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
                // Neither a file nor an embedded copy: the format has nothing to write
                // for this model, so it and its placements are dropped from this save
                // rather than failing the whole scene. Loud, because it is data loss.
                logger.error("No bytes available for model '{}'; its {} placement(s) are not saved",
                        ref.sourceName(), document.instancesOf(ref.sessionId()).size()
                                + document.orphanInstances().stream()
                                        .filter(o -> o.modelId().equals(ref.sessionId())).count());
                continue;
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
                document.adoptOrphans(ref.sessionId());
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
