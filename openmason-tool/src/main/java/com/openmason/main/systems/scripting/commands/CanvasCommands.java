package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelPaintOps;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseConfig;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseFilter;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.SimplexNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.ValueNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.WhiteNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.scripting.doc.CanvasSurface;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Scripted texture-editor canvas painting and layer management — the
 * {@code om.canvas} / {@code canvas_*} side of the scripting funnel.
 *
 * <p>Live-only: the texture editor window must be open (the surface is null
 * otherwise and every command raises a teaching error). Paint commands target
 * the ACTIVE layer and honor the editor's own semantics: layer shape mask and
 * active selection constrain writes, exactly like painting with the mouse.
 *
 * <p>PNG exports are DEFERRED like animation saves: {@code exportPng}
 * validates and queues; the executor flushes only after the whole script
 * succeeds, so a failing script writes nothing.
 */
public final class CanvasCommands {

    private record PendingExport(String absolutePath) {
    }

    private final CanvasSurface surface;
    private final AnimCommands.Tracer tracer;
    private final List<PendingExport> pendingExports = new ArrayList<>();

    CanvasCommands(CanvasSurface surfaceOrNull, AnimCommands.Tracer tracer) {
        this.surface = surfaceOrNull;
        this.tracer = tracer;
    }

    // ===================== Results =====================

    /** Canvas digest. */
    public record CanvasInfo(int width, int height, int layerCount,
                             int activeLayer, String activeLayerName) {
    }

    /** One layer's digest. */
    public record LayerInfo(int index, String name, boolean visible, float opacity,
                            boolean active) {
    }

    /** Row-major flat [r,g,b,a, ...] pixels for a rectangular region. */
    public record Region(int x, int y, int width, int height, int[] rgba) {
    }

    // ===================== Painting (active layer) =====================

    /** Write pixels from a flat [x,y,r,g,b,a, ...] array. Returns pixels changed. */
    public int setPixels(int[] flatXYRGBA) {
        if (flatXYRGBA == null || flatXYRGBA.length == 0 || flatXYRGBA.length % 6 != 0) {
            throw new CommandException(
                    "pixels must be a flat [x,y,r,g,b,a, ...] array, 6 ints per pixel");
        }
        return paint("canvas_set_pixels",
                op -> op.set("pixels", intArrayNode(op, flatXYRGBA)),
                (canvas, writer) -> {
                    int changed = 0;
                    for (int i = 0; i < flatXYRGBA.length; i += 6) {
                        int color = PixelCanvas.packRGBA(
                                channel(flatXYRGBA[i + 2]), channel(flatXYRGBA[i + 3]),
                                channel(flatXYRGBA[i + 4]), channel(flatXYRGBA[i + 5]));
                        changed += writer.write(flatXYRGBA[i], flatXYRGBA[i + 1], color);
                    }
                    return changed;
                });
    }

    /** Fill the whole active layer, or just {@code rect} [x,y,w,h] when given. */
    public int fill(int[] rectOrNull, int[] rgba) {
        int color = packColor(rgba);
        int[] rect = validateRect(rectOrNull);
        return paint("canvas_fill",
                op -> {
                    op.set("color", intArrayNode(op, rgba));
                    if (rect != null) op.set("rect", intArrayNode(op, rect));
                },
                (canvas, writer) -> {
                    int x = rect != null ? rect[0] : 0;
                    int y = rect != null ? rect[1] : 0;
                    int w = rect != null ? rect[2] : canvas.getWidth();
                    int h = rect != null ? rect[3] : canvas.getHeight();
                    return PixelPaintOps.rect(writer, x, y, w, h, color, true);
                });
    }

    /** Rectangle at [x,y,w,h] — filled, or a 1px outline. */
    public int rect(int[] rect, int[] rgba, boolean filled) {
        int color = packColor(rgba);
        int[] r = validateRect(rect);
        if (r == null) {
            throw new CommandException("rect is required: [x,y,w,h]");
        }
        return paint("canvas_rect",
                op -> {
                    op.set("rect", intArrayNode(op, r));
                    op.set("color", intArrayNode(op, rgba));
                    if (filled) op.put("filled", true);
                },
                (canvas, writer) -> PixelPaintOps.rect(writer, r[0], r[1], r[2], r[3], color, filled));
    }

    /** 1-pixel line from [x0,y0] to [x1,y1]. */
    public int line(int x0, int y0, int x1, int y1, int[] rgba) {
        int color = packColor(rgba);
        return paint("canvas_line",
                op -> {
                    op.set("from", intArrayNode(op, new int[]{x0, y0}));
                    op.set("to", intArrayNode(op, new int[]{x1, y1}));
                    op.set("color", intArrayNode(op, rgba));
                },
                (canvas, writer) -> PixelPaintOps.line(writer, x0, y0, x1, y1, color));
    }

    /** 4-connected flood fill; masked/unselected pixels block the fill (editor semantics). */
    public int flood(int x, int y, int[] rgba) {
        int color = packColor(rgba);
        return paint("canvas_flood",
                op -> {
                    op.set("at", intArrayNode(op, new int[]{x, y}));
                    op.set("color", intArrayNode(op, rgba));
                },
                (canvas, writer) -> PixelPaintOps.flood(canvas, writer,
                        (px, py) -> isEditable(canvas, px, py), x, y, color));
    }

    /** Apply procedural noise to the active layer (honors the active selection). */
    public int noise(String generator, long seed, float strength, float scale,
                     boolean gradient, float blur, int octaves, float spread,
                     float edgeSoftness) {
        NoiseGenerator gen = parseNoiseGenerator(generator, seed);
        NoiseConfig config = new NoiseConfig(gen, strength, gradient, scale,
                blur, octaves, spread, edgeSoftness);
        return paint("canvas_noise",
                op -> {
                    op.put("generator", generator.trim().toUpperCase(Locale.ROOT));
                    if (seed != 0) op.put("seed", seed);
                    op.put("strength", strength);
                    op.put("scale", scale);
                    if (gradient) op.put("gradient", true);
                    if (blur != 0) op.put("blur", blur);
                    if (octaves != 1) op.put("octaves", octaves);
                    op.put("spread", spread);
                    if (edgeSoftness != 0) op.put("edge_softness", edgeSoftness);
                },
                (canvas, writer) -> {
                    int[] before = canvas.getPixels().clone();
                    new NoiseFilter(config).apply(canvas, canvas.getActiveSelection());
                    int[] after = canvas.getPixels();
                    int changed = 0;
                    for (int i = 0; i < before.length; i++) {
                        if (before[i] != after[i]) changed++;
                    }
                    return changed;
                });
    }

    // ===================== Layers =====================

    /** Add a new empty layer on top; it becomes the active layer. */
    public LayerInfo addLayer(String name) {
        if (name == null || name.isBlank()) {
            throw new CommandException("layer name is required");
        }
        LayerManager lm = requireSurface().layers();
        surface.beginMutation();
        lm.addLayer(name);
        surface.notifyModified();

        tracer.trace("canvas_add_layer", op -> op.put("name", name));
        int idx = lm.getActiveLayerIndex();
        Layer layer = lm.getLayer(idx);
        return new LayerInfo(idx, layer.getName(), layer.isVisible(), layer.getOpacity(), true);
    }

    /** Remove the layer at {@code index}; refuses to remove the last layer. */
    public void removeLayer(int index) {
        LayerManager lm = requireSurface().layers();
        requireLayerIndex(lm, index);
        if (lm.getLayerCount() <= 1) {
            throw new CommandException("cannot remove the last layer");
        }
        surface.beginMutation();
        lm.removeLayer(index);
        surface.notifyModified();
        tracer.trace("canvas_remove_layer", op -> op.put("index", index));
    }

    /** Update any subset of a layer's name/visibility/opacity/active flag. */
    public LayerInfo setLayer(int index, Boolean active, Boolean visible,
                              String name, Float opacity) {
        LayerManager lm = requireSurface().layers();
        requireLayerIndex(lm, index);
        if (active == null && visible == null && name == null && opacity == null) {
            throw new CommandException(
                    "pass at least one of active, visible, name, opacity");
        }
        if (opacity != null && (opacity < 0f || opacity > 1f)) {
            throw new CommandException("opacity must be in 0..1, got " + opacity);
        }
        surface.beginMutation();
        if (name != null) lm.renameLayer(index, name);
        if (visible != null) lm.setLayerVisibility(index, visible);
        if (opacity != null) lm.setLayerOpacity(index, opacity);
        if (active != null && active) lm.setActiveLayer(index);
        surface.notifyModified();

        tracer.trace("canvas_set_layer", op -> {
            op.put("index", index);
            if (active != null) op.put("active", active);
            if (visible != null) op.put("visible", visible);
            if (name != null) op.put("name", name);
            if (opacity != null) op.put("opacity", opacity);
        });
        Layer layer = lm.getLayer(index);
        return new LayerInfo(index, layer.getName(), layer.isVisible(), layer.getOpacity(),
                index == lm.getActiveLayerIndex());
    }

    // ===================== Export (deferred) =====================

    /**
     * Queue a PNG export of the flattened visible layers. Validated now,
     * written by the executor only after the whole script succeeds.
     */
    public void exportPng(String path) {
        requireSurface();
        if (path == null || path.isBlank()) {
            throw new CommandException("export path is required");
        }
        Path resolved = Path.of(path);
        if (!resolved.isAbsolute()) {
            throw new CommandException("relative export path '" + path
                    + "' needs a working directory",
                    "canvas exports run live — use an absolute path");
        }
        pendingExports.add(new PendingExport(resolved.normalize().toString()));
        tracer.trace("canvas_export_png", op -> op.put("path", path));
    }

    /**
     * Write all queued exports. Called by the executor only after the script
     * succeeded; throws on the first failure.
     *
     * @return absolute paths written, in queue order
     */
    public List<String> flushExports() {
        List<String> written = new ArrayList<>();
        for (PendingExport pending : pendingExports) {
            if (!surface.exportPng(pending.absolutePath())) {
                throw new CommandException("Failed to write " + pending.absolutePath(),
                        "check the directory exists and is writable");
            }
            written.add(pending.absolutePath());
        }
        pendingExports.clear();
        return written;
    }

    // ===================== Queries =====================

    public CanvasInfo info() {
        LayerManager lm = requireSurface().layers();
        int active = lm.getActiveLayerIndex();
        String activeName = active >= 0 ? lm.getLayer(active).getName() : null;
        return new CanvasInfo(lm.getCanvasWidth(), lm.getCanvasHeight(),
                lm.getLayerCount(), active, activeName);
    }

    public List<LayerInfo> layerInfos() {
        LayerManager lm = requireSurface().layers();
        List<LayerInfo> out = new ArrayList<>(lm.getLayerCount());
        for (int i = 0; i < lm.getLayerCount(); i++) {
            Layer layer = lm.getLayer(i);
            out.add(new LayerInfo(i, layer.getName(), layer.isVisible(), layer.getOpacity(),
                    i == lm.getActiveLayerIndex()));
        }
        return out;
    }

    /** Read a rectangular region of the ACTIVE layer as flat [r,g,b,a, ...]. */
    public Region region(int x, int y, int w, int h) {
        PixelCanvas canvas = requireActiveCanvas();
        if (w <= 0 || h <= 0 || !canvas.isValidCoordinate(x, y)
                || !canvas.isValidCoordinate(x + w - 1, y + h - 1)) {
            throw new CommandException("region out of bounds: " + w + "x" + h + " at (" + x + ","
                    + y + ") on a " + canvas.getWidth() + "x" + canvas.getHeight() + " canvas");
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
        return new Region(x, y, w, h, rgba);
    }

    // ===================== Internals =====================

    @FunctionalInterface
    private interface PaintOp {
        int run(PixelCanvas canvas, PixelPaintOps.PixelWriter writer);
    }

    private int paint(String opName, Consumer<ObjectNode> traceFill, PaintOp op) {
        PixelCanvas canvas = requireActiveCanvas();
        surface.beginMutation();
        PixelPaintOps.PixelWriter writer = (x, y, color) -> {
            if (!isEditable(canvas, x, y)) return 0;
            if (canvas.getPixel(x, y) == color) return 0;
            canvas.setPixel(x, y, color);
            return 1;
        };
        int changed = op.run(canvas, writer);
        surface.notifyModified();

        tracer.trace(opName, traceFill);
        return changed;
    }

    /** The editor's write guards: bounds, shape mask, active selection. */
    private static boolean isEditable(PixelCanvas canvas, int x, int y) {
        if (!canvas.isValidCoordinate(x, y)) return false;
        if (!canvas.isEditablePixel(x, y)) return false;
        return !canvas.hasActiveSelection() || canvas.getActiveSelection().contains(x, y);
    }

    private CanvasSurface requireSurface() {
        if (surface == null) {
            throw new CommandException("canvas ops need the texture editor open",
                    "open it in Open Mason first — canvas scripting is live-only");
        }
        return surface;
    }

    private PixelCanvas requireActiveCanvas() {
        PixelCanvas canvas = requireSurface().activeCanvas();
        if (canvas == null) {
            throw new CommandException("the texture editor has no active layer");
        }
        return canvas;
    }

    private static void requireLayerIndex(LayerManager lm, int index) {
        if (index < 0 || index >= lm.getLayerCount()) {
            throw new CommandException("layer index " + index + " out of range 0-"
                    + (lm.getLayerCount() - 1));
        }
    }

    private static int packColor(int[] rgba) {
        if (rgba == null || rgba.length != 4) {
            throw new CommandException("color must be [r,g,b,a] with values 0..255");
        }
        for (int c : rgba) {
            if (c < 0 || c > 255) {
                throw new CommandException("color components must be 0..255, got " + c);
            }
        }
        return PixelCanvas.packRGBA(rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    private static int channel(int c) {
        if (c < 0 || c > 255) {
            throw new CommandException("color components must be 0..255, got " + c);
        }
        return c;
    }

    private static int[] validateRect(int[] rect) {
        if (rect == null) return null;
        if (rect.length != 4 || rect[2] <= 0 || rect[3] <= 0) {
            throw new CommandException("rect must be [x,y,w,h] with positive w and h");
        }
        return rect;
    }

    private static NoiseGenerator parseNoiseGenerator(String name, long seed) {
        if (name == null || name.isBlank()) {
            throw new CommandException("generator is required", "valid: simplex, value, white");
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "SIMPLEX" -> new SimplexNoiseGenerator(seed);
            case "VALUE" -> new ValueNoiseGenerator(seed);
            case "WHITE" -> new WhiteNoiseGenerator(seed);
            default -> throw new CommandException("Unknown noise generator '" + name + "'",
                    "valid: simplex, value, white");
        };
    }

    private static ArrayNode intArrayNode(ObjectNode owner, int[] values) {
        ArrayNode n = owner.arrayNode();
        for (int v : values) n.add(v);
        return n;
    }
}
