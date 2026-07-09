package com.openmason.main.systems.mcp;

import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelPaintOps;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.util.ArrayList;
import java.util.List;
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
}
