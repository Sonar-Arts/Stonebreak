package com.openmason.main.systems.mcp;

import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelPaintOps;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.main.systems.menus.textureCreator.SymmetryState;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import com.openmason.main.systems.menus.textureCreator.TextureDocument;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseConfig;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseFilter;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.SimplexNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.ValueNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.WhiteNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.layers.LayerStackSnapshot;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import com.openmason.main.systems.scripting.live.CanvasScriptCommand;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-safe facade over the texture editor canvas/layer surface.
 *
 * <p>Mirrors {@link ModelEditingService}: every operation is marshalled to the
 * GL/main thread, mutates the target document's active layer canvas, registers an
 * undoable {@link DrawCommand} in that document's history when pixels change, and
 * notifies the controller so the GPU preview is invalidated.
 *
 * <p><b>Documents, not the window.</b> The editor holds several open textures (tabs).
 * Operations act on the active one, or on the one named for the duration of
 * {@link #inDocument(String, java.util.function.Supplier)}. Opening a texture adds or
 * focuses a document tab and opens the Texture workspace only when it is not open;
 * nothing here closes the workspace — that stays the user's.
 */
public final class TextureEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final MainImGuiInterface mainInterface;

    /** Document id named by the caller (MCP thread), carried to the main thread by {@link #onMain}. */
    private static final ThreadLocal<String> REQUESTED_DOCUMENT = new ThreadLocal<>();
    /** The document id an operation runs against, set on the main thread for its duration. */
    private static final ThreadLocal<String> TARGET_DOCUMENT = new ThreadLocal<>();

    public TextureEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    /**
     * Run {@code call} (one or more operations of this service) against the document with this
     * id instead of the active one; null means the active document.
     */
    public <T> T inDocument(String documentId, java.util.function.Supplier<T> call) {
        String previous = REQUESTED_DOCUMENT.get();
        REQUESTED_DOCUMENT.set(documentId == null || documentId.isBlank() ? null : documentId.trim());
        try {
            return call.get();
        } finally {
            if (previous == null) {
                REQUESTED_DOCUMENT.remove();
            } else {
                REQUESTED_DOCUMENT.set(previous);
            }
        }
    }

    /**
     * The id of the document an operation would target now (the named one, else the active
     * one); for work that runs later, such as a save writer, to pin with {@link #inDocument}.
     */
    public String resolveDocumentId() {
        return onMain((() -> requireDocument().id()));
    }

    // ===================== Read =====================

    public CanvasInfo getCanvasInfo() {
        return onMain((this::getCanvasInfoUnsafe));
    }

    public PixelInfo getPixel(int x, int y) {
        return onMain((() -> {
            PixelCanvas canvas = requireActiveCanvas();
            if (!canvas.isValidCoordinate(x, y)) {
                throw new IllegalArgumentException("Pixel out of bounds: (" + x + "," + y + ")");
            }
            int packed = canvas.getPixel(x, y);
            int[] rgba = PixelCanvas.unpackRGBA(packed);
            return new PixelInfo(x, y, rgba[0], rgba[1], rgba[2], rgba[3]);
        }));
    }

    /** Bulk region read — one call instead of one get_pixel round trip per pixel. */
    public RegionInfo getRegion(int x, int y, int w, int h) {
        return onMain((() -> {
            PixelCanvas canvas = requireActiveCanvas();
            if (w <= 0 || h <= 0 || !canvas.isValidCoordinate(x, y)
                    || !canvas.isValidCoordinate(x + w - 1, y + h - 1)) {
                throw new IllegalArgumentException("Region out of bounds: " + w + "x" + h
                        + " at (" + x + "," + y + ") on " + canvas.getWidth()
                        + "x" + canvas.getHeight() + " canvas");
            }
            int[] rgba = new int[w * h * 4];
            int i = 0;
            for (int yy = y; yy < y + h; yy++) {
                for (int xx = x; xx < x + w; xx++) {
                    int[] px = PixelCanvas.unpackRGBA(canvas.getPixel(xx, yy));
                    rgba[i++] = px[0];
                    rgba[i++] = px[1];
                    rgba[i++] = px[2];
                    rgba[i++] = px[3];
                }
            }
            return new RegionInfo(x, y, w, h, rgba);
        }));
    }

    public List<LayerView> listLayers() {
        return onMain((() -> {
            return listLayersUnsafe(requireDocument().getLayerManager());
        }));
    }

    // ===================== Mutate: drawing =====================

    public DrawResult setPixels(List<PixelEntry> pixels) {
        return runDraw("Set Pixels", canvas -> {
            int changed = 0;
            for (PixelEntry p : pixels) {
                int color = PixelCanvas.packRGBA(p.r(), p.g(), p.b(), p.a());
                changed += recordIfEditable(canvas, p.x(), p.y(), color);
            }
            return changed;
        });
    }

    public DrawResult fillCanvas(int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Fill Canvas", canvas -> PixelPaintOps.rect(
                (x, y, c) -> recordIfEditable(canvas, x, y, c),
                0, 0, canvas.getWidth(), canvas.getHeight(), color, true));
    }

    public DrawResult clearCanvas() {
        return fillCanvas(0, 0, 0, 0);
    }

    public DrawResult fillRect(int x, int y, int w, int h, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Fill Rect", canvas -> PixelPaintOps.rect(
                (px, py, c) -> recordIfEditable(canvas, px, py, c), x, y, w, h, color, true));
    }

    // ===================== Editor window / face session =====================

    /** Where the editor is and what it is looking at. */
    public EditorStatus getEditorStatus() {
        return onMain((this::editorStatusUnsafe));
    }

    /**
     * Open a texture as a document tab inside the editor (opening the Texture workspace only
     * when it is not open; it is never closed here). Three modes:
     * <ul>
     *   <li>{@code faceId != null}: that model face — same flow as the property panel's
     *       "Edit Texture" button (creates the face material if needed, loads its GPU pixels,
     *       masks to the face polygon, highlights it in the viewport). A face already open in
     *       a tab is just focused, keeping its layers and history.</li>
     *   <li>{@code width/height != null}: a new untitled canvas of that size, in a new tab.</li>
     *   <li>neither: bring the editor forward on its active document (a new untitled 16x16
     *       one when nothing is open).</li>
     * </ul>
     */
    public EditorStatus openEditor(Integer faceId, Integer width, Integer height) {
        return onMain((() -> {
            MainImGuiInterface.TextureEditorPresenter presenter = requirePresenter();
            TextureCreatorController c = requireController();
            if (faceId != null) {
                var panel = mainInterface.getPropertyPanel();
                if (panel == null || panel.getViewportConnector() == null) {
                    throw new IllegalStateException("Viewport is not connected — load a model first");
                }
                var faceData = panel.getViewportConnector().extractFaceData();
                int faceCount = faceData == null ? 0 : faceData.faceCount();
                if (faceId < 0 || faceId >= faceCount) {
                    throw new IllegalArgumentException("Face " + faceId + " out of range [0, "
                            + (faceCount - 1) + "] — see model_face_list_textures");
                }
                if (!panel.openFaceTextureEditor(faceId)) {
                    throw new IllegalArgumentException("Face " + faceId
                            + " could not be opened for editing (unknown face, or no model loaded)");
                }
                // openFaceTextureEditor fires the edit-texture callback which shows the
                // workspace; be explicit in case the callback is not wired.
                presenter.show();
            } else if (width != null || height != null) {
                if (width == null || height == null) {
                    throw new IllegalArgumentException("width and height must be given together");
                }
                if (width < 1 || height < 1 || width > 4096 || height > 4096) {
                    throw new IllegalArgumentException("Canvas size must be within [1, 4096]: "
                            + width + "x" + height);
                }
                c.newTexture(new TextureCreatorState.CanvasSize(width, height));
                presenter.show();
            } else {
                c.ensureDocument();
                presenter.show();
            }
            return editorStatusUnsafe();
        }));
    }

    /**
     * Close one document tab (the active one, or the one named by {@link #inDocument}). A face
     * document is flushed to the model and the model saved, as when the user closes its tab. A
     * texture with unsaved changes is refused unless {@code discard}. The Texture workspace
     * itself stays open, even with no document left — closing it is the user's decision.
     */
    public EditorStatus closeDocument(boolean discard) {
        return onMain((() -> {
            TextureCreatorController c = requireController();
            TextureDocument doc = requireDocument();
            if (doc.needsSavePrompt() && !discard) {
                throw new IllegalStateException("Texture '" + doc.title() + "' (" + doc.id()
                        + ") has unsaved changes — pass save:true to save it and close, or discard:true "
                        + "to drop them");
            }
            c.closeDocuments(List.of(doc), TextureCreatorController.CloseReason.USER);
            return editorStatusUnsafe();
        }));
    }

    /** Push pending canvas edits to the face's GPU texture without closing the editor. */
    public EditorStatus flushToModel() {
        return onMain((() -> {
            flushPreview();
            return editorStatusUnsafe();
        }));
    }

    /** Open a {@code .omt} texture project as a document tab (or focus its tab) and show the editor. */
    public EditorStatus loadProject(String filePath) {
        return onMain((() -> {
            TextureCreatorController c = requireController();
            if (c.openProject(filePath) == null) {
                throw new IllegalArgumentException("Failed to load texture project: " + filePath);
            }
            requirePresenter().show();
            return editorStatusUnsafe();
        }));
    }

    /** The open .omt project's path, or null when the canvas is untitled / a face region. */
    public String currentProjectPath() {
        return onMain((() -> requireDocument().projectPath()));
    }

    /**
     * What an export of the current canvas needs to know: its .omt path (null
     * when untitled / a PNG session), whether it has unsaved edits, and whether
     * the canvas is a model-face session rather than a standalone texture.
     */
    public ProjectSnapshot projectSnapshot() {
        return onMain((() -> {
            TextureDocument doc = requireDocument();
            return new ProjectSnapshot(doc.projectPath(), doc.hasUnsavedChanges(), doc.isFace());
        }));
    }

    /** Save the editor's layer stack as a {@code .omt} texture project. */
    public boolean saveProject(String filePath) {
        return onMain((() -> requireController().saveDocument(requireDocument(), filePath)));
    }

    private void flushPreview() {
        var p = mainInterface.getTextureEditorPresenter();
        if (p != null) p.flush();
    }

    private MainImGuiInterface.TextureEditorPresenter requirePresenter() {
        var p = mainInterface.getTextureEditorPresenter();
        if (p == null) throw new IllegalStateException("Texture editor window is not wired yet");
        return p;
    }

    private EditorStatus editorStatusUnsafe() {
        TextureCreatorImGui ui = mainInterface.getTextureCreator();
        TextureCreatorController c = ui != null ? ui.getController() : null;
        var presenter = mainInterface.getTextureEditorPresenter();
        boolean visible = presenter != null && presenter.isVisible();
        if (c == null) {
            return new EditorStatus(visible, List.of(), null, false, -1, null, null, null, null, null);
        }
        List<DocumentView> docs = new ArrayList<>();
        for (TextureDocument d : c.getDocuments()) {
            docs.add(documentView(d, d == c.getActiveDocument()));
        }
        String tool = ui.getCurrentToolName();
        SymmetryState sym = c.getState().getSymmetryState();
        String symmetry = sym != null ? sym.getMode().name().toLowerCase(Locale.ROOT) : null;
        if (!c.hasOpenDocument()) {
            return new EditorStatus(visible, docs, null, false, -1, null, null, tool, symmetry, null);
        }
        TextureDocument doc = c.getActiveDocument();
        LayerManager lm = doc.getLayerManager();
        UVRegion uv = doc.isFace() ? doc.face().uvRegion() : null;
        int[] region = uv == null ? null : new int[]{
                Math.round(uv.u0() * lm.getCanvasWidth()), Math.round(uv.v0() * lm.getCanvasHeight()),
                Math.round(uv.u1() * lm.getCanvasWidth()), Math.round(uv.v1() * lm.getCanvasHeight())};
        int[] sel = null;
        var selection = doc.getSelectionManager().getActiveSelection();
        if (selection != null && !selection.isEmpty()) {
            var b = selection.getBounds();
            sel = new int[]{b.x, b.y, b.width, b.height};
        }
        return new EditorStatus(visible, docs, doc.id(), doc.isFace(),
                doc.isFace() ? doc.face().materialId() : -1, region, canvasInfo(doc), tool, symmetry, sel);
    }

    private static DocumentView documentView(TextureDocument d, boolean active) {
        LayerManager lm = d.getLayerManager();
        return new DocumentView(d.id(), d.title(), d.isFace() ? "face" : "texture",
                d.isFace() ? null : d.getFilePath(), d.hasUnsavedChanges() && !d.isFace(),
                lm.getCanvasWidth(), lm.getCanvasHeight(), d.isFace() ? d.face().faceId() : null, active);
    }

    // ===================== Layers =====================

    public List<LayerView> addLayer(String name) {
        return runStack("Add Layer", lm -> lm.addLayer(name == null || name.isBlank()
                ? "Layer " + (lm.getLayerCount() + 1) : name));
    }

    public List<LayerView> removeLayer(int index) {
        return runStack("Remove Layer", lm -> {
            checkLayer(lm, index);
            if (lm.getLayerCount() <= 1) {
                throw new IllegalStateException("Cannot remove the last layer");
            }
            lm.removeLayer(index);
        });
    }

    public List<LayerView> duplicateLayer(int index) {
        return runStack("Duplicate Layer", lm -> {
            checkLayer(lm, index);
            lm.duplicateLayer(index);
        });
    }

    public List<LayerView> moveLayer(int from, int to) {
        return runStack("Move Layer", lm -> {
            checkLayer(lm, from);
            checkLayer(lm, to);
            lm.moveLayer(from, to);
        });
    }

    public List<LayerView> setLayer(int index, Boolean active, Boolean visible, String name, Float opacity) {
        return runStack("Set Layer", lm -> {
            checkLayer(lm, index);
            if (name != null && !name.isBlank()) lm.renameLayer(index, name);
            if (visible != null) lm.setLayerVisibility(index, visible);
            if (opacity != null) {
                if (opacity < 0f || opacity > 1f) {
                    throw new IllegalArgumentException("opacity must be in [0, 1]");
                }
                lm.setLayerOpacity(index, opacity);
            }
            if (Boolean.TRUE.equals(active)) lm.setActiveLayer(index);
        });
    }

    /** Merge layer {@code index} onto the layer below it (alpha-over at its opacity) and remove it. */
    public List<LayerView> mergeLayerDown(int index) {
        return runStack("Merge Layer Down", lm -> {
            checkLayer(lm, index);
            if (index == 0) throw new IllegalArgumentException("Layer 0 has nothing below it");
            lm.mergeLayerDown(index);
        });
    }

    private static void checkLayer(LayerManager lm, int index) {
        if (index < 0 || index >= lm.getLayerCount()) {
            throw new IllegalArgumentException("Layer index " + index + " out of range [0, "
                    + (lm.getLayerCount() - 1) + "]");
        }
    }

    private interface StackOp {
        void run(LayerManager lm);
    }

    /** Run a layer-stack mutation as ONE undo entry (snapshot before/after). */
    private List<LayerView> runStack(String description, StackOp op) {
        return onMain((() -> {
            TextureCreatorController c = requireController();
            TextureDocument doc = requireDocument();
            LayerManager lm = doc.getLayerManager();
            LayerStackSnapshot before = LayerStackSnapshot.capture(lm);
            try {
                op.run(lm);
            } catch (RuntimeException e) {
                before.restore(lm);
                throw e;
            }
            doc.getCommandHistory().pushCompleted(new CanvasScriptCommand(
                    before, LayerStackSnapshot.capture(lm), lm, () -> c.notifyModified(doc)));
            c.notifyModified(doc);
            return listLayersUnsafe(lm);
        }));
    }

    private static List<LayerView> listLayersUnsafe(LayerManager lm) {
        List<LayerView> out = new ArrayList<>(lm.getLayerCount());
        for (int i = 0; i < lm.getLayerCount(); i++) {
            Layer l = lm.getLayer(i);
            out.add(new LayerView(i, l.getName(), l.isVisible(), l.getOpacity(),
                    i == lm.getActiveLayerIndex()));
        }
        return out;
    }

    // ===================== Mutate: shapes / noise / outline =====================

    public DrawResult line(int x0, int y0, int x1, int y1, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Line", canvas -> PixelPaintOps.line(
                (px, py, c) -> recordIfEditable(canvas, px, py, c), x0, y0, x1, y1, color));
    }

    public DrawResult rectOutline(int x, int y, int w, int h, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Rect", canvas -> PixelPaintOps.rect(
                (px, py, c) -> recordIfEditable(canvas, px, py, c), x, y, w, h, color, false));
    }

    public DrawResult flood(int x, int y, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Flood Fill", canvas -> {
            if (!canvas.isValidCoordinate(x, y)) {
                throw new IllegalArgumentException("Flood origin out of bounds: (" + x + "," + y + ")");
            }
            return PixelPaintOps.flood(canvas,
                    (px, py, c) -> recordIfEditable(canvas, px, py, c),
                    (px, py) -> canvas.isEditablePixel(px, py)
                            && (!canvas.hasActiveSelection() || canvas.getActiveSelection().contains(px, py)),
                    x, y, color);
        });
    }

    /** Ellipse inscribed in [x,y,w,h] — filled, or a 1px ring. */
    public DrawResult ellipse(int x, int y, int w, int h, int r, int g, int b, int a, boolean filled) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("w and h must be positive");
        return runDraw("Ellipse", canvas -> PixelPaintOps.ellipse(
                (px, py, c) -> recordIfEditable(canvas, px, py, c), x, y, w, h, color, filled));
    }

    /**
     * Apply the editor's procedural noise filter to the active layer (honours
     * the active selection). Noise perturbs existing RGB and keeps alpha, so
     * fill first — noise on a transparent layer does nothing.
     */
    public DrawResult noise(String generator, long seed, float strength, float scale,
                            boolean gradient, float blur, int octaves, float spread, float edgeSoftness) {
        NoiseGenerator gen = parseNoiseGenerator(generator, seed);
        NoiseConfig config = new NoiseConfig(gen, strength, gradient, scale, blur, octaves, spread, edgeSoftness);
        return runDraw("Noise", canvas -> applyFiltered(canvas,
                () -> new NoiseFilter(config).apply(canvas, canvas.getActiveSelection())));
    }

    /**
     * Auto-outline the active layer's opaque silhouette (see
     * {@link PixelPaintOps#outline}): outer border by default, or the edge row
     * with {@code inside}; no colour ⇒ darker/cooler shade of the neighbour.
     */
    public DrawResult outline(boolean inside, int[] rgbaOrNull) {
        Integer fixed = rgbaOrNull == null ? null
                : PixelCanvas.packRGBA(rgbaOrNull[0], rgbaOrNull[1], rgbaOrNull[2], rgbaOrNull[3]);
        return runDraw(inside ? "Inner Outline" : "Outline", canvas -> PixelPaintOps.outline(
                (px, py, c) -> recordIfEditable(canvas, px, py, c),
                canvas.getPixels().clone(), canvas.getWidth(), canvas.getHeight(), inside, fixed));
    }

    /**
     * Paint a character grid (the inverse of {@link #describe}): rows of glyphs,
     * a legend mapping glyph → colour ("#rrggbb[aa]" or "r,g,b[,a]"). '.' leaves
     * the pixel untouched unless {@code clearDots}; ' ' always skips.
     */
    public DrawResult paintGrid(List<String> rows, Map<String, String> legend, int ox, int oy, boolean clearDots) {
        Map<Character, Integer> lookup = PixelTextCodec.legendFrom(legend);
        int[] writes = PixelTextCodec.parseGrid(rows, lookup, ox, oy, clearDots);
        return runDraw("Paint Grid", canvas -> {
            int changed = 0;
            for (int i = 0; i < writes.length; i += 6) {
                int color = PixelCanvas.packRGBA(writes[i + 2], writes[i + 3], writes[i + 4], writes[i + 5]);
                changed += recordIfEditable(canvas, writes[i], writes[i + 1], color);
            }
            return changed;
        });
    }

    /** Run a whole-canvas filter, then diff against the previous pixels so the change is undoable. */
    private int applyFiltered(PixelCanvas canvas, Runnable filter) {
        int[] before = canvas.getPixels().clone();
        filter.run();
        int[] after = canvas.getPixels();
        int w = canvas.getWidth();
        int changed = 0;
        DrawCommand cmd = currentCommand.get();
        for (int i = 0; i < before.length; i++) {
            if (before[i] == after[i]) continue;
            int x = i % w, y = i / w;
            if (!canvas.isEditablePixel(x, y)) {
                after[i] = before[i];
                continue;
            }
            if (cmd != null) cmd.recordPixelChange(x, y, before[i], after[i]);
            changed++;
        }
        return changed;
    }

    private static NoiseGenerator parseNoiseGenerator(String name, long seed) {
        if (name == null || name.isBlank()) name = "simplex";
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "SIMPLEX" -> new SimplexNoiseGenerator(seed);
            case "VALUE" -> new ValueNoiseGenerator(seed);
            case "WHITE" -> new WhiteNoiseGenerator(seed);
            default -> throw new IllegalArgumentException("Unknown noise generator '" + name
                    + "' (valid: simplex, value, white)");
        };
    }

    // ===================== Selection / symmetry / tool / colour =====================

    /** Rectangular selection [x,y,w,h]; null clears. Constrains every later paint op. */
    public EditorStatus setSelection(int[] rectOrNull) {
        return onMain((() -> {
            var st = requireDocument().getSelectionManager();
            if (rectOrNull == null) {
                st.clearSelection();
            } else {
                if (rectOrNull.length != 4 || rectOrNull[2] <= 0 || rectOrNull[3] <= 0) {
                    throw new IllegalArgumentException("rect must be [x,y,w,h] with positive w,h");
                }
                int x = rectOrNull[0], y = rectOrNull[1];
                st.setActiveSelection(new RectangularSelection(x, y,
                        x + rectOrNull[2] - 1, y + rectOrNull[3] - 1));
            }
            return editorStatusUnsafe();
        }));
    }

    public EditorStatus setSymmetry(String mode, Integer offsetX, Integer offsetY, Boolean showAxes) {
        return onMain((() -> {
            SymmetryState sym = requireController().getState().getSymmetryState();
            if (sym == null) throw new IllegalStateException("Symmetry state not available");
            if (mode != null) {
                try {
                    sym.setMode(SymmetryState.SymmetryMode.valueOf(mode.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown symmetry mode '" + mode
                            + "' (valid: none, horizontal, vertical, quadrant)");
                }
            }
            if (offsetX != null) sym.setAxisOffsetX(offsetX);
            if (offsetY != null) sym.setAxisOffsetY(offsetY);
            if (showAxes != null) sym.setShowAxisLines(showAxes);
            return editorStatusUnsafe();
        }));
    }

    /** Select the interactive toolbar tool and/or the current paint colour for the user. */
    public ToolStatus setTool(String toolName, int[] rgbaOrNull) {
        return onMain((() -> {
            TextureCreatorImGui ui = mainInterface.getTextureCreator();
            if (ui == null) throw new IllegalStateException("Texture editor not initialized");
            if (toolName != null && !ui.selectToolByName(toolName)) {
                throw new IllegalArgumentException("Unknown tool '" + toolName + "' (valid: "
                        + String.join(", ", ui.getToolNames()) + ")");
            }
            if (rgbaOrNull != null) {
                ui.getState().setCurrentColor(PixelCanvas.packRGBA(
                        rgbaOrNull[0], rgbaOrNull[1], rgbaOrNull[2], rgbaOrNull[3]));
            }
            return toolStatusUnsafe(ui);
        }));
    }

    public ToolStatus getToolStatus() {
        return onMain((() -> {
            TextureCreatorImGui ui = mainInterface.getTextureCreator();
            if (ui == null) throw new IllegalStateException("Texture editor not initialized");
            return toolStatusUnsafe(ui);
        }));
    }

    private static ToolStatus toolStatusUnsafe(TextureCreatorImGui ui) {
        int[] rgba = PixelCanvas.unpackRGBA(ui.getState().getCurrentColor());
        return new ToolStatus(ui.getCurrentToolName(), ui.getToolNames(), rgba);
    }

    // ===================== Describe (text rendering for vision-less readers) =====================

    /**
     * Render pixels as a palette-indexed glyph grid with legend and structural
     * stats. {@code layer}: null = active layer, -1 = visible composite, else index.
     * {@code rect}: null = whole canvas.
     */
    public PixelTextCodec.Result describe(Integer layer, int[] rectOrNull, PixelTextCodec.Options opt) {
        return onMain((() -> {
            LayerManager lm = requireDocument().getLayerManager();
            PixelCanvas canvas;
            if (layer == null) {
                canvas = lm.getActiveLayer() != null ? lm.getActiveLayer().getCanvas() : null;
                if (canvas == null) throw new IllegalStateException("No active layer");
            } else if (layer < 0) {
                canvas = lm.compositeLayersToCanvas();
            } else {
                checkLayer(lm, layer);
                canvas = lm.getLayer(layer).getCanvas();
            }
            int w = canvas.getWidth(), h = canvas.getHeight();
            int x = 0, y = 0, rw = w, rh = h;
            if (rectOrNull != null) {
                if (rectOrNull.length != 4) throw new IllegalArgumentException("rect must be [x,y,w,h]");
                x = rectOrNull[0];
                y = rectOrNull[1];
                rw = rectOrNull[2];
                rh = rectOrNull[3];
                if (rw <= 0 || rh <= 0 || x < 0 || y < 0 || x + rw > w || y + rh > h) {
                    throw new IllegalArgumentException("rect " + rw + "x" + rh + " at (" + x + "," + y
                            + ") exceeds the " + w + "x" + h + " canvas");
                }
            }
            return PixelTextCodec.describe(canvas.getPixels(), w, x, y, rw, rh, opt);
        }));
    }

    // ===================== Undo / redo =====================

    public boolean undo() {
        return onMain((() -> {
            TextureDocument doc = requireDocument();
            boolean done = doc.getCommandHistory().undo();
            if (done) requireController().notifyModified(doc);
            return done;
        }));
    }

    public boolean redo() {
        return onMain((() -> {
            TextureDocument doc = requireDocument();
            boolean done = doc.getCommandHistory().redo();
            if (done) requireController().notifyModified(doc);
            return done;
        }));
    }

    // ===================== Resize =====================

    /**
     * Resize the GPU texture of the face currently open in the texture editor,
     * nearest-neighbor rescale. UVs are unaffected (normalized within material).
     *
     * @return new canvas info after resize
     */
    public CanvasInfo resizeFaceTexture(int width, int height) {
        return onMain((() -> {
            TextureCreatorImGui ui = mainInterface.getTextureCreator();
            if (ui == null) throw new IllegalStateException("Texture editor not initialized");
            var dialog = ui.getFaceTextureResizeDialog();
            if (dialog == null) {
                throw new IllegalStateException("Face texture resize dialog not wired");
            }
            TextureDocument doc = requireDocument();
            if (!doc.isFace()) {
                throw new IllegalStateException("Texture '" + doc.title()
                        + "' is not a model face — tex_open_editor {face_id} first");
            }
            requireController().activate(doc); // the resize works on the active face document
            if (!dialog.canOpen()) {
                throw new IllegalStateException(
                        "No face region is active — open a face in the texture editor first");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Dimensions must be positive: " + width + "x" + height);
            }
            boolean ok = dialog.resizeCurrentFace(width, height);
            if (!ok) {
                throw new IllegalStateException("Face texture resize failed");
            }
            return getCanvasInfoUnsafe();
        }));
    }

    private CanvasInfo getCanvasInfoUnsafe() {
        return canvasInfo(requireDocument());
    }

    private static CanvasInfo canvasInfo(TextureDocument doc) {
        LayerManager lm = doc.getLayerManager();
        int active = lm.getActiveLayerIndex();
        String activeName = active >= 0 ? lm.getLayer(active).getName() : null;
        CommandHistory hist = doc.getCommandHistory();
        return new CanvasInfo(
                lm.getCanvasWidth(), lm.getCanvasHeight(),
                lm.getLayerCount(), active, activeName,
                hist.canUndo(), hist.canRedo());
    }

    // ===================== Export =====================

    public boolean exportPng(String filePath) {
        return onMain((() -> requireController().exportDocument(requireDocument(), filePath)));
    }

    // ===================== Drawing helpers =====================

    /**
     * Holds the in-progress DrawCommand for the current main-thread operation,
     * so helpers (like applyNoise) can append to it without threading it through
     * every method signature.
     */
    private final ThreadLocal<DrawCommand> currentCommand = new ThreadLocal<>();

    private interface CanvasOp {
        int run(PixelCanvas canvas);
    }

    private DrawResult runDraw(String description, CanvasOp op) {
        return onMain((() -> {
            TextureCreatorController c = requireController();
            TextureDocument doc = requireDocument();
            PixelCanvas canvas = doc.getLayerManager().getActiveLayer() != null
                    ? doc.getLayerManager().getActiveLayer().getCanvas() : null;
            if (canvas == null) throw new IllegalStateException("No active layer");
            canvas.setSelectionManager(doc.getSelectionManager()); // the document's own selection bounds the op

            DrawCommand cmd = new DrawCommand(canvas, description);
            currentCommand.set(cmd);
            int changed;
            try {
                changed = op.run(canvas);
            } finally {
                currentCommand.remove();
            }

            if (cmd.hasChanges()) {
                doc.getCommandHistory().executeCommand(cmd);
                c.notifyModified(doc);
            }
            return new DrawResult(changed, cmd.hasChanges());
        }));
    }

    /**
     * Record an editable pixel change into the current command (and apply it).
     * Returns 1 if a change was recorded, 0 otherwise.
     */
    private int recordIfEditable(PixelCanvas canvas, int x, int y, int newColor) {
        if (!canvas.isValidCoordinate(x, y)) return 0;
        if (!canvas.isEditablePixel(x, y)) return 0;
        if (canvas.hasActiveSelection() && !canvas.getActiveSelection().contains(x, y)) return 0;
        int oldColor = canvas.getPixel(x, y);
        if (oldColor == newColor) return 0;
        DrawCommand cmd = currentCommand.get();
        if (cmd != null) cmd.recordPixelChange(x, y, oldColor, newColor);
        canvas.setPixel(x, y, newColor);
        return 1;
    }

    // ===================== Plumbing =====================

    private TextureCreatorController requireController() {
        TextureCreatorImGui ui = mainInterface.getTextureCreator();
        if (ui == null) throw new IllegalStateException("Texture editor not initialized");
        TextureCreatorController c = ui.getController();
        if (c == null) throw new IllegalStateException("Texture editor controller not available");
        return c;
    }

    /**
     * The document this operation targets: the one named by {@link #inDocument}, else the
     * active one. Refuses with a teaching message when none is open / the id is unknown.
     */
    private TextureDocument requireDocument() {
        TextureCreatorController c = requireController();
        String id = TARGET_DOCUMENT.get();
        if (id != null) {
            TextureDocument doc = c.findDocument(id);
            if (doc == null) {
                throw new IllegalArgumentException("No open texture document '" + id + "' (open: "
                        + (c.hasOpenDocument() ? String.join(", ", c.getDocuments().stream()
                        .map(d -> d.id() + " " + d.title()).toList()) : "none")
                        + ") — see tex_editor_status");
            }
            return doc;
        }
        if (!c.hasOpenDocument()) {
            throw new IllegalStateException("No texture is open in the texture editor — "
                    + "tex_open_editor (face_id, or width+height) or tex_load_project first");
        }
        return c.getActiveDocument();
    }

    private PixelCanvas requireActiveCanvas() {
        LayerManager lm = requireDocument().getLayerManager();
        PixelCanvas canvas = lm.getActiveLayer() != null ? lm.getActiveLayer().getCanvas() : null;
        if (canvas == null) throw new IllegalStateException("No active layer");
        return canvas;
    }

    /** Runs {@code call} on the main thread against the document named by the caller (if any). */
    private static <T> T onMain(java.util.concurrent.Callable<T> call) {
        String target = REQUESTED_DOCUMENT.get();
        return await(MainThreadExecutor.submit(() -> {
            String previous = TARGET_DOCUMENT.get();
            TARGET_DOCUMENT.set(target);
            try {
                return call.call();
            } finally {
                if (previous == null) {
                    TARGET_DOCUMENT.remove();
                } else {
                    TARGET_DOCUMENT.set(previous);
                }
            }
        }));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Texture operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // ===================== DTOs =====================

    public record CanvasInfo(int width, int height, int layerCount,
                              int activeLayerIndex, String activeLayerName,
                              boolean canUndo, boolean canRedo) {}

    public record PixelInfo(int x, int y, int r, int g, int b, int a) {}

    /** Row-major flat [r,g,b,a, ...] pixels for a rectangular region. */
    public record RegionInfo(int x, int y, int width, int height, int[] rgba) {}

    public record LayerView(int index, String name, boolean visible, float opacity, boolean active) {}

    public record DrawResult(int pixelsChanged, boolean recorded) {}

    public record PixelEntry(int x, int y, int r, int g, int b, int a) {}

    /**
     * Editor status: whether the Texture workspace is open, the open documents (tabs) and the
     * active one; the rest describes the active document ({@code faceRegionPx} = its face's UV
     * region in canvas pixels [x0,y0,x1,y1], {@code selection} = [x,y,w,h]); null/-1 when no
     * document is open.
     */
    public record EditorStatus(boolean windowVisible, List<DocumentView> documents, String activeDocument,
                               boolean faceRegionActive, int faceMaterialId,
                               int[] faceRegionPx, CanvasInfo canvas, String currentTool,
                               String symmetry, int[] selection) {}

    /**
     * One open document: id (pass as {@code document}), tab title, kind "face" or "texture",
     * .omt/.png path (textures), unsaved changes, canvas size, face id (faces), active tab.
     */
    public record DocumentView(String id, String title, String kind, String path, boolean dirty,
                               int width, int height, Integer faceId, boolean active) {}

    public record ProjectSnapshot(String path, boolean dirty, boolean faceRegionActive) {}

    public record ToolStatus(String currentTool, List<String> availableTools, int[] currentColor) {}
}
