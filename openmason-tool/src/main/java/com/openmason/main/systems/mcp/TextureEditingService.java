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
 * GL/main thread, mutates the active layer canvas, registers an undoable
 * {@link DrawCommand} when pixels change, and notifies the controller so the
 * GPU preview is invalidated.
 */
public final class TextureEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final MainImGuiInterface mainInterface;

    public TextureEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    // ===================== Read =====================

    public CanvasInfo getCanvasInfo() {
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            LayerManager lm = c.getLayerManager();
            int active = lm.getActiveLayerIndex();
            String activeName = active >= 0 ? lm.getLayer(active).getName() : null;
            CommandHistory hist = c.getCommandHistory();
            return new CanvasInfo(
                    lm.getCanvasWidth(), lm.getCanvasHeight(),
                    lm.getLayerCount(), active, activeName,
                    hist.canUndo(), hist.canRedo());
        }));
    }

    public PixelInfo getPixel(int x, int y) {
        return await(MainThreadExecutor.submit(() -> {
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
        return await(MainThreadExecutor.submit(() -> {
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
        return await(MainThreadExecutor.submit(() -> {
            LayerManager lm = requireController().getLayerManager();
            List<LayerView> out = new ArrayList<>(lm.getLayerCount());
            for (int i = 0; i < lm.getLayerCount(); i++) {
                Layer l = lm.getLayer(i);
                out.add(new LayerView(i, l.getName(), l.isVisible(), l.getOpacity(),
                        i == lm.getActiveLayerIndex()));
            }
            return out;
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
        return await(MainThreadExecutor.submit(this::editorStatusUnsafe));
    }

    /**
     * Summon the texture editor window. Three modes:
     * <ul>
     *   <li>{@code faceId != null}: open that model face for editing — same flow as
     *       the property panel's "Edit Texture" button (creates the face material if
     *       needed, loads its GPU pixels into the canvas, masks to the face polygon,
     *       highlights it in the viewport) and shows the window.</li>
     *   <li>{@code width/height != null}: start a fresh standalone canvas of that
     *       size (closes any active face session first) and show the window.</li>
     *   <li>neither: just show the window over whatever canvas is current.</li>
     * </ul>
     */
    public EditorStatus openEditor(Integer faceId, Integer width, Integer height) {
        return await(MainThreadExecutor.submit(() -> {
            MainImGuiInterface.TextureEditorPresenter presenter = requirePresenter();
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
                // window; be explicit in case the callback is not wired.
                presenter.show();
            } else if (width != null || height != null) {
                if (width == null || height == null) {
                    throw new IllegalArgumentException("width and height must be given together");
                }
                if (width < 1 || height < 1 || width > 4096 || height > 4096) {
                    throw new IllegalArgumentException("Canvas size must be within [1, 4096]: "
                            + width + "x" + height);
                }
                TextureCreatorController c = requireController();
                if (c.isFaceRegionActive()) {
                    flushPreview();
                    c.closeFaceRegion();
                    var panel = mainInterface.getPropertyPanel();
                    if (panel != null) panel.clearEditingFace();
                }
                c.newTexture(new TextureCreatorState.CanvasSize(width, height));
                presenter.show();
            } else {
                presenter.show();
            }
            return editorStatusUnsafe();
        }));
    }

    /**
     * Close the texture editor window the way its X button does: flush canvas
     * edits to the face texture, close the face region, clear the viewport
     * highlight and auto-save the model when a face was being edited.
     */
    public EditorStatus closeEditor() {
        return await(MainThreadExecutor.submit(() -> {
            requirePresenter().close();
            return editorStatusUnsafe();
        }));
    }

    /** Push pending canvas edits to the face's GPU texture without closing the editor. */
    public EditorStatus flushToModel() {
        return await(MainThreadExecutor.submit(() -> {
            flushPreview();
            return editorStatusUnsafe();
        }));
    }

    /** Load a {@code .omt} texture project into the editor and show it. */
    public EditorStatus loadProject(String filePath) {
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            if (c.isFaceRegionActive()) {
                flushPreview();
                c.closeFaceRegion();
            }
            if (!c.loadProject(filePath)) {
                throw new IllegalArgumentException("Failed to load texture project: " + filePath);
            }
            requirePresenter().show();
            return editorStatusUnsafe();
        }));
    }

    /** Save the editor's layer stack as a {@code .omt} texture project. */
    public boolean saveProject(String filePath) {
        return await(MainThreadExecutor.submit(() -> requireController().saveProject(filePath)));
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
            return new EditorStatus(visible, false, -1, null, null, null, null, null);
        }
        LayerManager lm = c.getLayerManager();
        int active = lm.getActiveLayerIndex();
        CommandHistory hist = c.getCommandHistory();
        CanvasInfo info = new CanvasInfo(lm.getCanvasWidth(), lm.getCanvasHeight(),
                lm.getLayerCount(), active, active >= 0 ? lm.getLayer(active).getName() : null,
                hist.canUndo(), hist.canRedo());
        UVRegion uv = c.getFaceRegionUV();
        int[] region = uv == null ? null : new int[]{
                Math.round(uv.u0() * lm.getCanvasWidth()), Math.round(uv.v0() * lm.getCanvasHeight()),
                Math.round(uv.u1() * lm.getCanvasWidth()), Math.round(uv.v1() * lm.getCanvasHeight())};
        TextureCreatorState st = c.getState();
        String tool = ui.getCurrentToolName();
        SymmetryState sym = st.getSymmetryState();
        String symmetry = sym != null ? sym.getMode().name().toLowerCase(Locale.ROOT) : null;
        int[] sel = null;
        if (st.hasSelection() && st.getCurrentSelection() != null) {
            var b = st.getCurrentSelection().getBounds();
            sel = new int[]{b.x, b.y, b.width, b.height};
        }
        return new EditorStatus(visible, c.isFaceRegionActive(), c.getFaceRegionMaterialId(),
                region, info, tool, symmetry, sel);
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
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            LayerManager lm = c.getLayerManager();
            LayerStackSnapshot before = LayerStackSnapshot.capture(lm);
            try {
                op.run(lm);
            } catch (RuntimeException e) {
                before.restore(lm);
                throw e;
            }
            c.getCommandHistory().pushCompleted(new CanvasScriptCommand(
                    before, LayerStackSnapshot.capture(lm), lm, c::notifyLayerModified));
            c.notifyLayerModified();
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
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorState st = requireController().getState();
            if (rectOrNull == null) {
                st.clearSelection();
            } else {
                if (rectOrNull.length != 4 || rectOrNull[2] <= 0 || rectOrNull[3] <= 0) {
                    throw new IllegalArgumentException("rect must be [x,y,w,h] with positive w,h");
                }
                int x = rectOrNull[0], y = rectOrNull[1];
                st.setCurrentSelection(new RectangularSelection(x, y,
                        x + rectOrNull[2] - 1, y + rectOrNull[3] - 1));
            }
            return editorStatusUnsafe();
        }));
    }

    public EditorStatus setSymmetry(String mode, Integer offsetX, Integer offsetY, Boolean showAxes) {
        return await(MainThreadExecutor.submit(() -> {
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
        return await(MainThreadExecutor.submit(() -> {
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
        return await(MainThreadExecutor.submit(() -> {
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
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            LayerManager lm = c.getLayerManager();
            PixelCanvas canvas;
            if (layer == null) {
                canvas = c.getActiveLayerCanvas();
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
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            boolean done = c.getCommandHistory().undo();
            if (done) c.notifyLayerModified();
            return done;
        }));
    }

    public boolean redo() {
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            boolean done = c.getCommandHistory().redo();
            if (done) c.notifyLayerModified();
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
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorImGui ui = mainInterface.getTextureCreator();
            if (ui == null) throw new IllegalStateException("Texture editor not initialized");
            var dialog = ui.getFaceTextureResizeDialog();
            if (dialog == null) {
                throw new IllegalStateException("Face texture resize dialog not wired");
            }
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
        TextureCreatorController c = requireController();
        LayerManager lm = c.getLayerManager();
        int active = lm.getActiveLayerIndex();
        String activeName = active >= 0 ? lm.getLayer(active).getName() : null;
        CommandHistory hist = c.getCommandHistory();
        return new CanvasInfo(
                lm.getCanvasWidth(), lm.getCanvasHeight(),
                lm.getLayerCount(), active, activeName,
                hist.canUndo(), hist.canRedo());
    }

    // ===================== Export =====================

    public boolean exportPng(String filePath) {
        return await(MainThreadExecutor.submit(() -> requireController().exportTexture(filePath)));
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
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            PixelCanvas canvas = c.getActiveLayerCanvas();
            if (canvas == null) throw new IllegalStateException("No active layer");

            DrawCommand cmd = new DrawCommand(canvas, description);
            currentCommand.set(cmd);
            int changed;
            try {
                changed = op.run(canvas);
            } finally {
                currentCommand.remove();
            }

            if (cmd.hasChanges()) {
                c.getCommandHistory().executeCommand(cmd);
                c.notifyLayerModified();
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

    private PixelCanvas requireActiveCanvas() {
        PixelCanvas canvas = requireController().getActiveLayerCanvas();
        if (canvas == null) throw new IllegalStateException("No active layer");
        return canvas;
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
     * Editor window + session status. {@code faceRegionPx} is the active face's
     * UV region in canvas pixels [x0,y0,x1,y1]; {@code selection} is [x,y,w,h].
     */
    public record EditorStatus(boolean windowVisible, boolean faceRegionActive, int faceMaterialId,
                               int[] faceRegionPx, CanvasInfo canvas, String currentTool,
                               String symmetry, int[] selection) {}

    public record ToolStatus(String currentTool, List<String> availableTools, int[] currentColor) {}
}
