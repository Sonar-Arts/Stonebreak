package com.openmason.main.systems.mcp;

import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseConfig;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseFilter;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.SimplexNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.ValueNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.WhiteNoiseGenerator;
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

    public DrawResult setPixel(int x, int y, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Set Pixel", canvas -> recordIfEditable(canvas, x, y, color));
    }

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
        return runDraw("Fill Canvas", canvas -> {
            int changed = 0;
            for (int y = 0; y < canvas.getHeight(); y++) {
                for (int x = 0; x < canvas.getWidth(); x++) {
                    changed += recordIfEditable(canvas, x, y, color);
                }
            }
            return changed;
        });
    }

    public DrawResult clearCanvas() {
        return fillCanvas(0, 0, 0, 0);
    }

    public DrawResult fillRect(int x, int y, int w, int h, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Fill Rect", canvas -> drawRect(canvas, x, y, w, h, color, true));
    }

    public DrawResult drawRect(int x, int y, int w, int h, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Draw Rect", canvas -> drawRect(canvas, x, y, w, h, color, false));
    }

    public DrawResult drawLine(int x0, int y0, int x1, int y1, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Draw Line", canvas -> bresenhamLine(canvas, x0, y0, x1, y1, color));
    }

    public DrawResult floodFill(int x, int y, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw("Flood Fill", canvas -> {
            if (!canvas.isValidCoordinate(x, y)) return 0;
            int target = canvas.getPixel(x, y);
            if (target == color) return 0;
            return floodFillInternal(canvas, x, y, target, color);
        });
    }

    public DrawResult applyNoise(String generator, long seed, float strength,
                                  float scale, boolean gradient,
                                  float blur, int octaves, float spread, float edgeSoftness) {
        NoiseGenerator gen = parseNoiseGenerator(generator, seed);
        NoiseConfig config = new NoiseConfig(gen, strength, gradient, scale,
                blur, octaves, spread, edgeSoftness);
        return runDraw("Noise", canvas -> {
            // Snapshot before/after applying the filter so we can build an undoable command.
            int w = canvas.getWidth(), h = canvas.getHeight();
            int[] before = new int[w * h];
            System.arraycopy(canvas.getPixels(), 0, before, 0, before.length);

            new NoiseFilter(config).apply(canvas, canvas.getActiveSelection());

            int[] after = canvas.getPixels();
            DrawCommand cmd = currentCommand.get();
            int changed = 0;
            for (int i = 0; i < before.length; i++) {
                if (before[i] != after[i]) {
                    int x = i % w;
                    int y = i / w;
                    cmd.recordPixelChange(x, y, before[i], after[i]);
                    changed++;
                }
            }
            return changed;
        });
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

    // ===================== Mutate: layers =====================

    public LayerView addLayer(String name) {
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            LayerManager lm = c.getLayerManager();
            lm.addLayer(name);
            c.notifyLayerModified();
            int idx = lm.getActiveLayerIndex();
            Layer l = lm.getLayer(idx);
            return new LayerView(idx, l.getName(), l.isVisible(), l.getOpacity(), true);
        }));
    }

    public boolean removeLayer(int index) {
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            LayerManager lm = c.getLayerManager();
            if (index < 0 || index >= lm.getLayerCount() || lm.getLayerCount() <= 1) {
                return false;
            }
            lm.removeLayer(index);
            c.notifyLayerModified();
            return true;
        }));
    }

    public boolean setActiveLayer(int index) {
        return await(MainThreadExecutor.submit(() -> {
            LayerManager lm = requireController().getLayerManager();
            if (index < 0 || index >= lm.getLayerCount()) return false;
            lm.setActiveLayer(index);
            return true;
        }));
    }

    public boolean setLayerVisibility(int index, boolean visible) {
        return await(MainThreadExecutor.submit(() -> {
            TextureCreatorController c = requireController();
            LayerManager lm = c.getLayerManager();
            if (index < 0 || index >= lm.getLayerCount()) return false;
            lm.setLayerVisibility(index, visible);
            c.notifyLayerModified();
            return true;
        }));
    }

    public boolean renameLayer(int index, String name) {
        return await(MainThreadExecutor.submit(() -> {
            LayerManager lm = requireController().getLayerManager();
            if (index < 0 || index >= lm.getLayerCount()) return false;
            lm.renameLayer(index, name);
            return true;
        }));
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

    private int drawRect(PixelCanvas canvas, int x, int y, int w, int h, int color, boolean filled) {
        if (w <= 0 || h <= 0) return 0;
        int x1 = x + w - 1;
        int y1 = y + h - 1;
        int changed = 0;
        for (int yy = y; yy <= y1; yy++) {
            for (int xx = x; xx <= x1; xx++) {
                if (!filled && xx != x && xx != x1 && yy != y && yy != y1) continue;
                changed += recordIfEditable(canvas, xx, yy, color);
            }
        }
        return changed;
    }

    private int bresenhamLine(PixelCanvas canvas, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int changed = 0;
        int x = x0, y = y0;
        while (true) {
            changed += recordIfEditable(canvas, x, y, color);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
        return changed;
    }

    private int floodFillInternal(PixelCanvas canvas, int startX, int startY,
                                   int targetColor, int fillColor) {
        boolean[][] visited = new boolean[canvas.getWidth()][canvas.getHeight()];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        queue.offer(new int[]{startX, startY});
        int changed = 0;
        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1];
            if (!canvas.isValidCoordinate(x, y) || visited[x][y]) continue;
            visited[x][y] = true;
            if (!canvas.isEditablePixel(x, y)) continue;
            if (canvas.hasActiveSelection() && !canvas.getActiveSelection().contains(x, y)) continue;
            if (canvas.getPixel(x, y) != targetColor) continue;
            changed += recordIfEditable(canvas, x, y, fillColor);
            queue.offer(new int[]{x + 1, y});
            queue.offer(new int[]{x - 1, y});
            queue.offer(new int[]{x, y + 1});
            queue.offer(new int[]{x, y - 1});
        }
        return changed;
    }

    private static NoiseGenerator parseNoiseGenerator(String name, long seed) {
        if (name == null) throw new IllegalArgumentException("generator is required");
        return switch (name.trim().toUpperCase()) {
            case "SIMPLEX" -> new SimplexNoiseGenerator(seed);
            case "VALUE" -> new ValueNoiseGenerator(seed);
            case "WHITE" -> new WhiteNoiseGenerator(seed);
            default -> throw new IllegalArgumentException(
                    "Unknown noise generator '" + name + "'. Valid: SIMPLEX, VALUE, WHITE");
        };
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

    public record LayerView(int index, String name, boolean visible, float opacity, boolean active) {}

    public record DrawResult(int pixelsChanged, boolean recorded) {}

    public record PixelEntry(int x, int y, int r, int g, int b, int a) {}
}
