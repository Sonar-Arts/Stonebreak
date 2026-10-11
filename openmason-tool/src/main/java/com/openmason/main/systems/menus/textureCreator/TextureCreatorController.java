package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.canvas.CanvasShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.CanvasState;
import com.openmason.main.systems.menus.textureCreator.canvas.CompositeShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.CubeNetShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.main.systems.menus.textureCreator.clipboard.ClipboardManager;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.io.TextureExporter;
import com.openmason.main.systems.menus.textureCreator.io.TextureImporter;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Texture creator controller - coordinates all texture creator operations.
 *
 * <p>The editor holds any number of open {@link TextureDocument}s (one per document tab) and
 * works on the <em>active</em> one: every accessor below ({@link #getLayerManager()},
 * {@link #getCommandHistory()}, {@link #getCanvasState()}, the face-region queries, undo/redo...)
 * answers for it, and {@link TextureCreatorState} is re-bound to it on every switch. With no
 * document open an unlisted placeholder stands in, so readers never see null; the panels show
 * an empty state instead of its canvas ({@link #hasOpenDocument()}).
 *
 * <p>Opening a texture never replaces another one: {@link #newTexture}, {@link #loadProject},
 * {@link #importTexture} and {@link #openFaceRegion} add a document (or focus the one already
 * showing that file / face). Only {@link #closeDocuments} removes documents; saving or
 * discarding unsaved work first is the caller's job.
 */
public class TextureCreatorController {

    private static final Logger logger = LoggerFactory.getLogger(TextureCreatorController.class);

    /** Why documents are closing; listeners decide on flushes and saves from it. */
    public enum CloseReason {
        /** The user closed tabs (or an agent closed a document). */
        USER,
        /** The Texture workspace tab was closed. */
        WORKSPACE_CLOSED,
        /** A different model was loaded: face documents point at textures that are gone. */
        MODEL_CHANGED
    }

    /** Document lifecycle events, all on the main thread. */
    public interface DocumentListener {
        /** {@code document} became the active one (the placeholder when the last one closed). */
        default void activated(TextureDocument document) {
        }

        /** {@code document} is about to close (still listed, still holding its pixels). */
        default void closing(TextureDocument document, CloseReason reason) {
        }

        /** One {@link #closeDocuments} call finished; {@code documents} are gone. */
        default void closed(List<TextureDocument> documents, CloseReason reason) {
        }
    }

    private final TextureCreatorState state;
    private final List<TextureDocument> documents = new ArrayList<>();
    private final TextureDocument placeholder;
    private TextureDocument active;
    private final TextureExporter exporter;
    private final TextureImporter importer;
    private final ClipboardManager clipboard;
    private final List<java.util.function.Consumer<java.nio.file.Path>> saveListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<DocumentListener> documentListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Runs before the active document changes (the UI settles a pending move/paste into it). */
    private Runnable beforeSwitch = () -> { };

    /** Pixels staged by prepareBlankCanvas / prepareCanvasFromPixels for the next face opened. */
    private LayerManager stagedFaceLayers;

    private int nextDocumentNumber = 1;
    private int nextUntitledNumber = 1;

    /**
     * Create texture creator controller (no documents open).
     *
     * @param state texture creator state
     */
    public TextureCreatorController(TextureCreatorState state) {
        this.state = state;
        TextureCreatorState.CanvasSize size = state.getCurrentCanvasSize();
        this.placeholder = new TextureDocument("none", "No Texture",
                new LayerManager(size.getWidth(), size.getHeight()));
        this.active = placeholder;
        state.bindDocument(placeholder);

        this.exporter = new TextureExporter();
        this.importer = new TextureImporter();
        this.clipboard = new ClipboardManager();

        logger.info("Texture creator controller initialized");
    }

    // ── Documents ────────────────────────────────────────────────────────

    /** The open documents in tab order (a copy). */
    public List<TextureDocument> getDocuments() {
        return List.copyOf(documents);
    }

    /** True when at least one document is open (otherwise the placeholder is active). */
    public boolean hasOpenDocument() {
        return !documents.isEmpty();
    }

    /** The document on screen: the active open one, or the placeholder when none is open. */
    public TextureDocument getActiveDocument() {
        return active;
    }

    /** The open document with this id, or null. */
    public TextureDocument findDocument(String id) {
        if (id == null) {
            return null;
        }
        for (TextureDocument d : documents) {
            if (d.id().equals(id)) {
                return d;
            }
        }
        return null;
    }

    /** The open face document painting {@code faceId} (or {@code materialId} when no face id is known). */
    public TextureDocument findFaceDocument(int faceId, int materialId) {
        for (TextureDocument d : documents) {
            if (d.isBoundTo(faceId, materialId)) {
                return d;
            }
        }
        return null;
    }

    /** The open document showing {@code filePath}, or null. */
    public TextureDocument findDocumentByPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        java.nio.file.Path wanted = normalize(filePath);
        for (TextureDocument d : documents) {
            if (d.getFilePath() != null && normalize(d.getFilePath()).equals(wanted)) {
                return d;
            }
        }
        return null;
    }

    /** Make {@code document} (an open one) the active document. */
    public void activate(TextureDocument document) {
        if (document == null || document == active) {
            return;
        }
        if (!documents.contains(document)) {
            throw new IllegalArgumentException("Not an open texture document: " + document);
        }
        switchTo(document);
    }

    /**
     * The active document, opening a fresh untitled 16x16 one first when none is open (Tools →
     * Texture Editor, an agent opening the editor without naming a texture).
     */
    public TextureDocument ensureDocument() {
        if (documents.isEmpty()) {
            newTexture(new TextureCreatorState.CanvasSize(16, 16));
        }
        return active;
    }

    /**
     * Closes documents as they are — unsaved work is lost, so prompting is the caller's job.
     * Listeners hear {@code closing} for each and one {@code closed} for the batch; when the
     * active document closes, its right-hand neighbour (else the left one, else the placeholder)
     * becomes active.
     */
    public void closeDocuments(java.util.Collection<TextureDocument> toClose, CloseReason reason) {
        List<TextureDocument> closing = new ArrayList<>();
        for (TextureDocument d : toClose) {
            if (documents.contains(d) && !closing.contains(d)) {
                closing.add(d);
            }
        }
        if (closing.isEmpty()) {
            return;
        }
        if (closing.contains(active)) {
            beforeSwitch.run(); // settle a pending move/paste while its document still exists
        }
        for (TextureDocument d : closing) {
            for (DocumentListener l : documentListeners) {
                l.closing(d, reason);
            }
        }
        TextureDocument next = active;
        if (closing.contains(active)) {
            int index = documents.indexOf(active);
            next = null;
            for (int i = index + 1; i < documents.size() && next == null; i++) {
                if (!closing.contains(documents.get(i))) {
                    next = documents.get(i);
                }
            }
            for (int i = index - 1; i >= 0 && next == null; i--) {
                if (!closing.contains(documents.get(i))) {
                    next = documents.get(i);
                }
            }
        }
        for (TextureDocument d : closing) {
            unbindFace(d);
            documents.remove(d);
        }
        if (next != active) {
            setActive(next != null ? next : placeholder);
        }
        logger.info("Closed {} texture document(s) ({})", closing.size(), reason);
        for (DocumentListener l : documentListeners) {
            l.closed(List.copyOf(closing), reason);
        }
    }

    /** Closes every open document (see {@link #closeDocuments}). */
    public void closeAll(CloseReason reason) {
        closeDocuments(getDocuments(), reason);
    }

    /** Closes every face document (see {@link #closeDocuments}). */
    public void closeFaceDocuments(CloseReason reason) {
        closeDocuments(documents.stream().filter(TextureDocument::isFace).toList(), reason);
    }

    public void addDocumentListener(DocumentListener listener) {
        documentListeners.add(java.util.Objects.requireNonNull(listener, "listener"));
    }

    public void removeDocumentListener(DocumentListener listener) {
        documentListeners.remove(listener);
    }

    /**
     * Runs before the active document changes or closes, while it is still active: the UI
     * commits a floating move/paste into the document it belongs to.
     */
    public void setBeforeDocumentSwitch(Runnable hook) {
        this.beforeSwitch = hook != null ? hook : () -> { };
    }

    private void switchTo(TextureDocument document) {
        beforeSwitch.run();
        setActive(document);
    }

    private void setActive(TextureDocument document) {
        this.active = document;
        state.bindDocument(document);
        for (DocumentListener l : documentListeners) {
            l.activated(document);
        }
    }

    /**
     * Lists and activates a new document; {@code untitled} ones take the next "Untitled-N" name
     * (documents that get a file or a face binding at once do not use up a number).
     */
    private TextureDocument addDocument(LayerManager layers, boolean untitled) {
        String name = untitled ? "Untitled-" + nextUntitledNumber++ : "Untitled";
        TextureDocument doc = new TextureDocument("t" + nextDocumentNumber++, name, layers);
        documents.add(doc);
        switchTo(doc);
        return doc;
    }

    private static java.nio.file.Path normalize(String path) {
        try {
            return java.nio.file.Path.of(path).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return java.nio.file.Path.of(path);
        }
    }

    // ── Opening textures ─────────────────────────────────────────────────

    /**
     * Open a new blank texture with the given dimensions as its own document.
     *
     * @param canvasSize canvas size
     * @return the new (active) document
     */
    public TextureDocument newTexture(TextureCreatorState.CanvasSize canvasSize) {
        // A new layer manager creates its default "Background" layer
        TextureDocument doc = addDocument(new LayerManager(canvasSize.getWidth(), canvasSize.getHeight()), true);
        logger.info("Created new texture {}: {}", doc.id(), canvasSize.getDisplayName());
        return doc;
    }

    /**
     * Stage a blank canvas with an opaque fill colour for the next face opened
     * ({@link #openFaceRegion}): used when the face has no texture yet, so the canvas must match
     * the GPU texture the preview pipeline uploads into.
     *
     * @param width     canvas width in pixels
     * @param height    canvas height in pixels
     * @param fillColor RGBA fill color (use {@link PixelCanvas#packRGBA})
     */
    public void prepareBlankCanvas(int width, int height, int fillColor) {
        LayerManager layers = new LayerManager(width, height);
        applyAutoShapeMask(layers);
        Layer background = layers.getActiveLayer();
        if (background != null) {
            background.getCanvas().fill(fillColor);
        }
        stagedFaceLayers = layers;
        logger.info("Prepared blank face canvas: {}x{}", width, height);
    }

    /**
     * Stage existing pixel data read back from a GPU texture for the next face opened
     * ({@link #openFaceRegion}), so the editor shows the face's current pixels. Opening a face
     * whose document is already open replaces that document's pixels (the resize dialog).
     *
     * @param width      texture width in pixels
     * @param height     texture height in pixels
     * @param rgbaPixels RGBA byte array (4 bytes per pixel, row-major)
     */
    public void prepareCanvasFromPixels(int width, int height, byte[] rgbaPixels) {
        LayerManager layers = new LayerManager(width, height);
        applyAutoShapeMask(layers);
        Layer background = layers.getActiveLayer();
        if (background != null && rgbaPixels != null) {
            int[] pixels = background.getCanvas().getPixels();
            int pixelCount = Math.min(pixels.length, rgbaPixels.length / 4);
            for (int i = 0; i < pixelCount; i++) {
                int offset = i * 4;
                int r = rgbaPixels[offset] & 0xFF;
                int g = rgbaPixels[offset + 1] & 0xFF;
                int b = rgbaPixels[offset + 2] & 0xFF;
                int a = rgbaPixels[offset + 3] & 0xFF;
                pixels[i] = PixelCanvas.packRGBA(r, g, b, a);
            }
        }
        stagedFaceLayers = layers;
        logger.info("Prepared face canvas from existing texture: {}x{}", width, height);
    }

    /**
     * The canvas size {@link #openFaceRegion} will give this face's document: the staged canvas,
     * else the face's open document, else 16x16. Face masks are built against it.
     */
    public int[] faceCanvasSize(int faceId, int materialId) {
        if (stagedFaceLayers != null) {
            return new int[]{stagedFaceLayers.getCanvasWidth(), stagedFaceLayers.getCanvasHeight()};
        }
        TextureDocument open = findFaceDocument(faceId, materialId);
        if (open != null) {
            return new int[]{open.getLayerManager().getCanvasWidth(), open.getLayerManager().getCanvasHeight()};
        }
        return new int[]{16, 16};
    }

    /** Drops a staged face canvas (the face it was prepared for could not be opened). */
    public void discardPreparedCanvas() {
        stagedFaceLayers = null;
    }

    /**
     * Save the active document to a .OMT file (preserves all layers and project state).
     *
     * @param filePath output file path
     * @return true if save succeeded
     */
    public boolean saveProject(String filePath) {
        return saveDocument(active, filePath);
    }

    /**
     * Save {@code document} to a .OMT file. The document then belongs to that file and is clean.
     *
     * @return true if save succeeded
     */
    public boolean saveDocument(TextureDocument document, String filePath) {
        boolean success = exporter.exportToOMT(document.getLayerManager(), filePath);

        if (success) {
            if (!document.isFace()) {
                // A face document stays bound to its face; the .omt is a copy.
                document.setFilePath(filePath);
                document.setProjectFile(true);
            }
            document.setUnsavedChanges(false);
            logger.info("Saved texture {} to: {}", document.id(), filePath);
            for (java.util.function.Consumer<java.nio.file.Path> l : saveListeners) {
                try {
                    l.accept(java.nio.file.Path.of(filePath));
                } catch (RuntimeException e) {
                    logger.warn("Texture save listener failed: {}", e.getMessage());
                }
            }
        }

        return success;
    }

    /**
     * Called with the file after every successful .OMT save, whichever path saved it (menu,
     * shortcut, MCP). The UI editor refreshes the documents that use the texture (#294).
     */
    public void addSaveListener(java.util.function.Consumer<java.nio.file.Path> listener) {
        saveListeners.add(java.util.Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Open a .OMT project as a document tab, or focus the tab already showing that file (its
     * edits, saved or not, are kept — the file is not re-read).
     *
     * @param filePath input file path
     * @return true if the project is now the active document
     */
    public boolean loadProject(String filePath) {
        return openProject(filePath) != null;
    }

    /**
     * {@link #loadProject} returning the document.
     *
     * @return the active document showing the file, or null when it could not be read
     */
    public TextureDocument openProject(String filePath) {
        TextureDocument existing = findDocumentByPath(filePath);
        if (existing != null && existing.isProjectFile()) {
            activate(existing);
            return existing;
        }

        LayerManager loadedManager = importer.importFromOMT(filePath);
        if (loadedManager == null) {
            logger.error("Failed to load project from: {}", filePath);
            return null;
        }

        TextureDocument doc = addDocument(loadedManager, false);
        doc.setFilePath(filePath);
        doc.setProjectFile(true);
        doc.setUnsavedChanges(false);

        logger.info("Loaded project {} from: {} with {} layers", doc.id(), filePath, loadedManager.getLayerCount());
        return doc;
    }

    /**
     * {@code document}'s file is gone (deleted from the project browser): it keeps its pixels
     * but no longer has a file, so the next Save asks where, and it is flagged unsaved.
     */
    public void detachFromFile(TextureDocument document) {
        document.setFilePath(null);
        document.setProjectFile(false);
        document.setUnsavedChanges(true);
    }

    /**
     * Export the active document to a PNG file (flattens all visible layers).
     *
     * @param filePath output file path
     * @return true if export succeeded
     */
    public boolean exportTexture(String filePath) {
        return exportDocument(active, filePath);
    }

    /** Export {@code document}'s visible layers as a PNG; the document's file and dirty state are untouched. */
    public boolean exportDocument(TextureDocument document, String filePath) {
        PixelCanvas composite = document.getLayerManager().compositeLayersToCanvas();
        boolean success = exporter.exportToPNG(composite, filePath);
        if (success) {
            logger.info("Exported texture {} to: {}", document.id(), filePath);
        }
        return success;
    }

    /**
     * Import a PNG as a new document of the given canvas size (the image is resized to it).
     *
     * @param filePath input file path
     * @param targetSize target canvas size
     * @return true if import succeeded
     */
    public boolean importTexture(String filePath, TextureCreatorState.CanvasSize targetSize) {
        PixelCanvas imported = importer.importFromPNGResized(filePath, targetSize.getWidth(), targetSize.getHeight());

        if (imported == null) {
            logger.error("Failed to import texture from: {}", filePath);
            return false;
        }

        LayerManager layers = new LayerManager(targetSize.getWidth(), targetSize.getHeight());
        applyAutoShapeMask(layers);
        layers.renameLayer(0, "Imported");
        layers.getLayer(0).getCanvas().copyFrom(imported);

        TextureDocument doc = addDocument(layers, false);
        doc.setFilePath(filePath);
        doc.setProjectFile(false); // PNG import is not a project file
        doc.setUnsavedChanges(true);

        logger.info("Imported texture {} from: {} to {} canvas", doc.id(), filePath, targetSize.getDisplayName());
        return true;
    }

    // ── Active document ──────────────────────────────────────────────────

    /**
     * Undo last operation.
     */
    public void undo() {
        if (active.getCommandHistory().undo()) {
            notifyModified(active);
            logger.debug("Undo performed");
        }
    }

    /**
     * Redo last undone operation.
     */
    public void redo() {
        if (active.getCommandHistory().redo()) {
            notifyModified(active);
            logger.debug("Redo performed");
        }
    }

    /** The active document's view (zoom/pan). */
    public CanvasState getCanvasState() {
        return active.getCanvasState();
    }

    /** The active document's layers. */
    public LayerManager getLayerManager() {
        return active.getLayerManager();
    }

    /** The active document's undo history. */
    public CommandHistory getCommandHistory() {
        return active.getCommandHistory();
    }

    /**
     * Get the texture creator state.
     * @return state
     */
    public TextureCreatorState getState() {
        return state;
    }

    /**
     * Get the texture importer.
     * @return importer
     */
    public TextureImporter getImporter() {
        return importer;
    }

    /**
     * Get composited canvas (all visible layers merged).
     * @return composited canvas
     */
    public PixelCanvas getCompositedCanvas() {
        return active.getLayerManager().compositeLayersToCanvas();
    }

    /**
     * Get active layer canvas.
     * @return active layer canvas, or null if no active layer
     */
    public PixelCanvas getActiveLayerCanvas() {
        Layer activeLayer = active.getLayerManager().getActiveLayer();
        return activeLayer != null ? activeLayer.getCanvas() : null;
    }

    /**
     * Get composited canvas of all layers EXCEPT the active layer.
     * Useful for multi-layer preview during transformations.
     * @return background canvas (all layers except active), or null if no active layer
     */
    public PixelCanvas getBackgroundCanvas() {
        LayerManager layers = active.getLayerManager();
        Layer activeLayer = layers.getActiveLayer();
        if (activeLayer == null) {
            return null;
        }
        return layers.compositeLayersExcluding(activeLayer);
    }

    /**
     * Notify that the active layer has been modified.
     * This marks the composite cache as dirty so it will be regenerated.
     */
    public void notifyLayerModified() {
        notifyModified(active);
    }

    /** {@link #notifyLayerModified()} for any document (agents may edit one that is not on screen). */
    public void notifyModified(TextureDocument document) {
        document.getLayerManager().markCompositeDirty();
        document.setUnsavedChanges(true);
    }

    /**
     * Get the clipboard manager (shared by every document).
     * @return clipboard manager
     */
    public ClipboardManager getClipboard() {
        return clipboard;
    }

    /**
     * Copy current selection to clipboard.
     */
    public void copySelection() {
        SelectionRegion selection = state.getCurrentSelection();

        if (selection == null || selection.isEmpty()) {
            logger.debug("No active selection to copy");
            return;
        }

        Layer activeLayer = active.getLayerManager().getActiveLayer();
        if (activeLayer == null) {
            logger.warn("No active layer to copy from");
            return;
        }

        PixelCanvas canvas = activeLayer.getCanvas();
        clipboard.copy(canvas, selection);
    }

    /**
     * Cut current selection to clipboard (copy then clear).
     */
    public void cutSelection() {
        SelectionRegion selection = state.getCurrentSelection();

        if (selection == null || selection.isEmpty()) {
            logger.debug("No active selection to cut");
            return;
        }

        Layer activeLayer = active.getLayerManager().getActiveLayer();
        if (activeLayer == null) {
            logger.warn("No active layer to cut from");
            return;
        }

        PixelCanvas canvas = activeLayer.getCanvas();

        // Create command for the cut operation
        DrawCommand cutCommand = new DrawCommand(canvas, "Cut Selection");

        // Cut to clipboard (copy + clear)
        clipboard.cut(canvas, selection, cutCommand);

        // Execute command if changes were made
        if (cutCommand.hasChanges()) {
            active.getCommandHistory().executeCommand(cutCommand);
            notifyLayerModified();
        }
    }

    /**
     * Check if clipboard has data that can be pasted.
     * @return true if clipboard has data
     */
    public boolean canPaste() {
        return clipboard.hasData();
    }

    /**
     * Delete the contents of the current selection.
     * Sets all pixels within the selection to transparent.
     */
    public void deleteSelection() {
        SelectionRegion selection = state.getCurrentSelection();

        if (selection == null || selection.isEmpty()) {
            logger.debug("No active selection to delete");
            return;
        }

        Layer activeLayer = active.getLayerManager().getActiveLayer();
        if (activeLayer == null) {
            logger.warn("No active layer to delete selection from");
            return;
        }

        PixelCanvas canvas = activeLayer.getCanvas();
        java.awt.Rectangle bounds = selection.getBounds();

        // Create command for undo/redo support
        DrawCommand deleteCommand =
            new DrawCommand(canvas, "Delete Selection");

        // Delete all pixels within selection
        int pixelsDeleted = 0;
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (canvas.isValidCoordinate(x, y) && selection.contains(x, y)) {
                    int oldColor = canvas.getPixel(x, y);
                    int newColor = 0x00000000; // Transparent

                    if (oldColor != newColor) {
                        deleteCommand.recordPixelChange(x, y, oldColor, newColor);
                        pixelsDeleted++;
                    }
                }
            }
        }

        // Only execute if pixels were changed
        if (deleteCommand.hasChanges()) {
            active.getCommandHistory().executeCommand(deleteCommand);
            notifyLayerModified();
            logger.info("Deleted {} pixels from selection", pixelsDeleted);
        } else {
            logger.debug("No pixels to delete in selection (all already transparent)");
        }
    }

    // ── Face-region editing ──────────────────────────────────────────────

    /** {@link #openFaceRegion(int, int, UVRegion, CanvasShapeMask, float, float)} without a face id. */
    public TextureDocument openFaceRegion(int materialId, UVRegion uvRegion, CanvasShapeMask mask,
                                          float viewportWidth, float viewportHeight) {
        return openFaceRegion(-1, materialId, uvRegion, mask, viewportWidth, viewportHeight);
    }

    /**
     * Open a model face as a face document focused on its UV region, or focus the face's document
     * when it is already open.
     *
     * <p>The document's pixels come from the canvas staged by {@link #prepareBlankCanvas} /
     * {@link #prepareCanvasFromPixels} (replacing an already-open face document's pixels and
     * history — the resize dialog), else the open document keeps its own, else a blank 16x16.
     * The face mask is composed onto the active layer's mask, pixels outside it are cleared, the
     * view frames the UV region and the face's material receives the document's real-time preview.
     *
     * @param faceId         model face, or -1 when unknown (the material then identifies the face)
     * @param materialId     material ID the face belongs to
     * @param uvRegion       the face's UV region within the material texture
     * @param mask           shape mask defining the paintable area (nullable for full-rect faces)
     * @param viewportWidth  available viewport width in screen pixels
     * @param viewportHeight available viewport height in screen pixels
     * @return the face's (now active) document
     */
    public TextureDocument openFaceRegion(int faceId, int materialId, UVRegion uvRegion, CanvasShapeMask mask,
                                          float viewportWidth, float viewportHeight) {
        LayerManager staged = stagedFaceLayers;
        stagedFaceLayers = null;

        TextureDocument doc = findFaceDocument(faceId, materialId);
        if (doc == null) {
            if (staged == null) {
                logger.warn("Face {} opened without a prepared canvas; starting from a blank 16x16", faceId);
                staged = new LayerManager(16, 16);
                applyAutoShapeMask(staged);
            }
            doc = addDocument(staged, false);
        } else {
            activate(doc);
            unbindFace(doc);
            if (staged != null) {
                doc.setLayerManager(staged);
                doc.getCommandHistory().clear();
                doc.getSelectionManager().clearSelection();
            }
        }
        doc.setUnsavedChanges(false);

        // Compose the face mask onto the active layer's own mask (e.g. a cube net mask)
        PixelCanvas activeCanvas = getActiveLayerCanvas();
        CanvasShapeMask baseMask = null;
        if (activeCanvas != null) {
            baseMask = activeCanvas.getShapeMask();
            if (baseMask != null && mask != null) {
                activeCanvas.setShapeMask(new CompositeShapeMask(baseMask, mask));
            } else {
                activeCanvas.setShapeMask(mask != null ? mask : baseMask);
            }
        }
        doc.setFace(new TextureDocument.FaceBinding(faceId, materialId, uvRegion, mask, baseMask));

        // Frame the view on the face's UV region
        LayerManager layers = doc.getLayerManager();
        doc.getCanvasState().frameTo(uvRegion.u0(), uvRegion.v0(), uvRegion.u1(), uvRegion.v1(),
                layers.getCanvasWidth(), layers.getCanvasHeight(), viewportWidth, viewportHeight);

        // Clear non-editable pixels to transparent so the base texture
        // doesn't bleed into non-paintable areas on the canvas
        if (activeCanvas != null) {
            CanvasShapeMask effectiveMask = activeCanvas.getShapeMask();
            if (effectiveMask != null) {
                int[] pixels = activeCanvas.getPixels();
                int w = activeCanvas.getWidth();
                int h = activeCanvas.getHeight();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (!effectiveMask.isEditable(x, y)) {
                            pixels[y * w + x] = 0x00000000;
                        }
                    }
                }
            }
            // Force a full GPU upload so the preview pipeline pushes the canvas
            // contents to the face's texture on the next flush
            activeCanvas.notifyFullCanvasDirty();
        }
        layers.markCompositeDirty();

        logger.info("Opened face region {}: face={}, materialId={}, uv=({},{})→({},{}), mask={}",
            doc.id(), faceId, materialId, uvRegion.u0(), uvRegion.v0(), uvRegion.u1(), uvRegion.v1(),
            mask != null ? "active" : "none");
        return doc;
    }

    /**
     * Close the active document when it is a face document (its listeners flush it to the model
     * and save the model). Does nothing for a standalone texture.
     */
    public void closeFaceRegion() {
        if (active.isFace()) {
            closeDocuments(List.of(active), CloseReason.USER);
        }
    }

    /** Restores the canvas mask a face binding replaced and forgets the binding. */
    private void unbindFace(TextureDocument doc) {
        TextureDocument.FaceBinding face = doc.face();
        if (face == null) {
            return;
        }
        Layer layer = doc.getLayerManager().getActiveLayer();
        if (layer != null) {
            layer.getCanvas().setShapeMask(face.baseMask());
        }
        doc.setFace(null);
    }

    /**
     * Check if face-region editing mode is active.
     *
     * @return true if the active document is a face document
     */
    public boolean isFaceRegionActive() {
        return active.isFace();
    }

    /**
     * Get the material ID of the face being edited.
     *
     * @return material ID, or -1 if the active document is not a face document
     */
    public int getFaceRegionMaterialId() {
        return active.isFace() ? active.face().materialId() : -1;
    }

    /**
     * Get the UV region of the face being edited.
     *
     * @return UV region, or null if the active document is not a face document
     */
    public UVRegion getFaceRegionUV() {
        return active.isFace() ? active.face().uvRegion() : null;
    }

    /**
     * Import a PNG into the active face region, resizing to fit the UV bounds.
     *
     * <p>Loads the PNG at native resolution, scales it to match the face region's
     * pixel dimensions on the canvas, and stamps the pixels onto the active layer.
     * Respects the face mask so only editable pixels are modified. The operation
     * is fully undoable via {@link DrawCommand}.
     *
     * @param filePath path to the PNG file
     * @return true if the import succeeded and pixels were applied
     */
    public boolean importTextureToFaceRegion(String filePath) {
        UVRegion faceRegionUV = getFaceRegionUV();
        if (faceRegionUV == null) {
            logger.warn("Cannot import to face region: no face region active");
            return false;
        }

        PixelCanvas sourceCanvas = importer.importFromPNG(filePath);
        if (sourceCanvas == null) {
            logger.error("Failed to load PNG for face region import: {}", filePath);
            return false;
        }

        PixelCanvas activeCanvas = getActiveLayerCanvas();
        if (activeCanvas == null) {
            logger.warn("No active layer canvas for face region import");
            return false;
        }

        LayerManager layerManager = active.getLayerManager();
        int canvasWidth = layerManager.getCanvasWidth();
        int canvasHeight = layerManager.getCanvasHeight();

        // Calculate the face region bounds in pixel coordinates
        int regionX = Math.round(faceRegionUV.u0() * canvasWidth);
        int regionY = Math.round(faceRegionUV.v0() * canvasHeight);
        int regionW = Math.round((faceRegionUV.u1() - faceRegionUV.u0()) * canvasWidth);
        int regionH = Math.round((faceRegionUV.v1() - faceRegionUV.v0()) * canvasHeight);

        if (regionW <= 0 || regionH <= 0) {
            logger.warn("Face region has zero area: {}x{}", regionW, regionH);
            return false;
        }

        // Resize the imported image to match the face region dimensions
        PixelCanvas resized = sourceCanvas.resized(regionW, regionH);

        // Create an undoable command and stamp pixels onto the active canvas
        DrawCommand importCommand = new DrawCommand(activeCanvas, "Import PNG to Face");

        CanvasShapeMask effectiveMask = activeCanvas.getShapeMask();
        int pixelsApplied = 0;

        for (int dy = 0; dy < regionH; dy++) {
            for (int dx = 0; dx < regionW; dx++) {
                int canvasX = regionX + dx;
                int canvasY = regionY + dy;

                if (!activeCanvas.isValidCoordinate(canvasX, canvasY)) {
                    continue;
                }

                // Respect the face mask
                if (effectiveMask != null && !effectiveMask.isEditable(canvasX, canvasY)) {
                    continue;
                }

                int newColor = resized.getPixel(dx, dy);
                int oldColor = activeCanvas.getPixel(canvasX, canvasY);

                if (oldColor != newColor) {
                    importCommand.recordPixelChange(canvasX, canvasY, oldColor, newColor);
                    pixelsApplied++;
                }
            }
        }

        if (importCommand.hasChanges()) {
            active.getCommandHistory().executeCommand(importCommand);
            notifyLayerModified();
            logger.info("Imported PNG to face region: {} pixels applied from {}", pixelsApplied, filePath);
            return true;
        }

        logger.debug("PNG import to face region resulted in no pixel changes");
        return true;
    }

    // ── Shape mask auto-assignment ──────────────────────────────────────

    /**
     * Apply an automatic shape mask to a fresh layer stack based on its dimensions.
     *
     * <p>Currently recognizes:
     * <ul>
     *   <li>64x48 — standard cube net layout ({@link CubeNetShapeMask})</li>
     * </ul>
     *
     * <p>For unrecognized dimensions, no mask is applied (all pixels editable).
     */
    private static void applyAutoShapeMask(LayerManager layers) {
        CanvasShapeMask autoMask = CubeNetShapeMask.createIfApplicable(layers.getCanvasWidth(), layers.getCanvasHeight());
        Layer layer = layers.getActiveLayer();
        if (layer != null) {
            layer.getCanvas().setShapeMask(autoMask);
        }
    }
}
