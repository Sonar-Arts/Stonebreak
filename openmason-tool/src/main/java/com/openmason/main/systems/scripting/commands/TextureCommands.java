package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.rendering.model.gmr.parts.MeshRange;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelPaintOps;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseConfig;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseFilter;
import com.openmason.main.systems.menus.textureCreator.filters.noise.NoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.SimplexNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.ValueNoiseGenerator;
import com.openmason.main.systems.menus.textureCreator.filters.noise.WhiteNoiseGenerator;
import com.openmason.main.systems.scripting.doc.FacePixelStore;
import com.openmason.main.systems.scripting.doc.ModelDocument;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Scripted per-face texture authoring: create a texture for a face selection,
 * then paint it with pixel primitives — the {@code om.tex} / {@code texture_*}
 * side of the scripting funnel.
 *
 * <p>Pixels live on GPU textures, so this domain needs the document's
 * {@link FacePixelStore} (live viewport only; headless runs get a teaching
 * error and author data-level materials/UVs instead). Every paint command
 * mutates a CPU mirror of the texture and immediately flushes the dirty
 * rectangle back to the GPU, so the viewport stays current mid-script.
 *
 * <p>Faces sharing a material share a texture — painting via one face is
 * visible on all of them (the {@code create} command deliberately allocates
 * ONE shared material for the whole selection).
 */
public final class TextureCommands {

    /** Texture size limit, matching the face-texture MCP tools. */
    private static final int MAX_TEXTURE_SIZE = 1024;

    private final ModelCommands owner;
    private final ModelDocument doc;
    private final AnimCommands.Tracer tracer;

    /** CPU mirror per GPU texture id, loaded lazily from the store. */
    private final Map<Integer, PixelCanvas> working = new LinkedHashMap<>();

    TextureCommands(ModelCommands owner, ModelDocument doc, AnimCommands.Tracer tracer) {
        this.owner = owner;
        this.doc = doc;
        this.tracer = tracer;
    }

    // ===================== Results =====================

    /** Result of {@link #create}: the shared material + texture for the selection. */
    public record CreateInfo(int material, String name, int width, int height, int faces) {
    }

    /** One face's texture digest. */
    public record TextureInfo(String part, int face, boolean mapped, int material,
                              String materialName, int width, int height) {
    }

    /** Row-major flat [r,g,b,a, ...] pixels for a rectangular region. */
    public record Region(int x, int y, int width, int height, int[] rgba) {
    }

    // ===================== Commands =====================

    /**
     * Create ONE texture (and material) shared by all selected faces. Faces
     * must not already carry a non-default material. Returns the material.
     */
    public CreateInfo create(String partIdOrName, int[] localFaceIds, int width, int height,
                             int[] fillRGBA, String materialName) {
        FacePixelStore store = requireStore();
        ModelPartDescriptor part = owner.resolve(partIdOrName);
        owner.validateLocalFaces(part, localFaceIds);
        if (width <= 0 || height <= 0 || width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE) {
            throw new CommandException("texture size must be in [1," + MAX_TEXTURE_SIZE
                    + "], got " + width + "x" + height);
        }
        int fill = packColor(fillRGBA != null ? fillRGBA : new int[]{255, 255, 255, 255});

        FaceTextureManager ftm = doc.faceTextures();
        MeshRange range = ModelCommands.requireRange(part);
        int[] globals = new int[localFaceIds.length];
        for (int i = 0; i < localFaceIds.length; i++) {
            int global = range.faceStart() + localFaceIds[i];
            FaceTextureMapping existing = ftm.getFaceMapping(global);
            if (existing != null
                    && existing.materialId() != MaterialDefinition.DEFAULT.materialId()) {
                throw new CommandException("face " + localFaceIds[i] + " of '" + part.name()
                        + "' already has a texture (material " + existing.materialId() + ")",
                        "paint it via om.tex.of / texture_fill, or pick unmapped faces");
            }
            globals[i] = global;
        }
        if (materialName != null && owner.materialIdByName(materialName) != null) {
            throw new CommandException("A material named '" + materialName + "' already exists",
                    "material names are unique; assign it with set_face_material instead");
        }

        int materialId = store.allocateMaterialId();
        if (materialId <= 0) {
            throw new CommandException("Failed to allocate a material id");
        }
        PixelCanvas canvas = new PixelCanvas(width, height);
        canvas.fill(fill);
        int textureId = store.createTexture(canvas);
        if (textureId <= 0) {
            throw new CommandException("Failed to create the GPU texture");
        }
        String name = materialName != null ? materialName : "Face " + globals[0];
        ftm.registerMaterial(new MaterialDefinition(materialId, name, textureId,
                MaterialDefinition.RenderLayer.OPAQUE,
                MaterialDefinition.MaterialProperties.NONE));

        int[] materialIds = new int[globals.length];
        java.util.Arrays.fill(materialIds, materialId);
        store.assignFaceMaterials(globals, materialIds);
        for (int global : globals) {
            disableAutoResize(ftm, global);
        }
        working.put(textureId, canvas);

        int[] traceFaces = localFaceIds;
        int[] traceColor = fillRGBA;
        tracer.trace("texture_create", op -> {
            op.put("part", part.name());
            op.set("faces", intArrayNode(op, traceFaces));
            op.set("size", intArrayNode(op, new int[]{width, height}));
            if (traceColor != null) op.set("color", intArrayNode(op, traceColor));
            if (materialName != null) op.put("name", materialName);
        });
        return new CreateInfo(materialId, name, width, height, localFaceIds.length);
    }

    /** Write pixels from a flat [x,y,r,g,b,a, ...] array. Returns pixels changed. */
    public int setPixels(String partIdOrName, int localFace, int[] flatXYRGBA) {
        if (flatXYRGBA == null || flatXYRGBA.length == 0 || flatXYRGBA.length % 6 != 0) {
            throw new CommandException(
                    "pixels must be a flat [x,y,r,g,b,a, ...] array, 6 ints per pixel");
        }
        return paint(partIdOrName, localFace, "texture_set_pixels",
                op -> op.set("pixels", intArrayNode(op, flatXYRGBA)),
                (target, writer) -> {
                    int changed = 0;
                    for (int i = 0; i < flatXYRGBA.length; i += 6) {
                        int color = PixelCanvas.packRGBA(
                                clampChannel(flatXYRGBA[i + 2]), clampChannel(flatXYRGBA[i + 3]),
                                clampChannel(flatXYRGBA[i + 4]), clampChannel(flatXYRGBA[i + 5]));
                        changed += writer.write(flatXYRGBA[i], flatXYRGBA[i + 1], color);
                    }
                    return changed;
                });
    }

    /** Fill the whole texture, or just {@code rect} [x,y,w,h] when given. */
    public int fill(String partIdOrName, int localFace, int[] rectOrNull, int[] rgba) {
        int color = packColor(rgba);
        int[] rect = validateRect(rectOrNull);
        return paint(partIdOrName, localFace, "texture_fill",
                op -> {
                    op.set("color", intArrayNode(op, rgba));
                    if (rect != null) op.set("rect", intArrayNode(op, rect));
                },
                (target, writer) -> {
                    int x = rect != null ? rect[0] : 0;
                    int y = rect != null ? rect[1] : 0;
                    int w = rect != null ? rect[2] : target.canvas.getWidth();
                    int h = rect != null ? rect[3] : target.canvas.getHeight();
                    return PixelPaintOps.rect(writer, x, y, w, h, color, true);
                });
    }

    /** Rectangle at [x,y,w,h] — filled, or a 1px outline. */
    public int rect(String partIdOrName, int localFace, int[] rect, int[] rgba, boolean filled) {
        int color = packColor(rgba);
        int[] r = validateRect(rect);
        if (r == null) {
            throw new CommandException("rect is required: [x,y,w,h]");
        }
        return paint(partIdOrName, localFace, "texture_rect",
                op -> {
                    op.set("rect", intArrayNode(op, r));
                    op.set("color", intArrayNode(op, rgba));
                    if (filled) op.put("filled", true);
                },
                (target, writer) -> PixelPaintOps.rect(writer, r[0], r[1], r[2], r[3], color, filled));
    }

    /** 1-pixel line from [x0,y0] to [x1,y1]. */
    public int line(String partIdOrName, int localFace, int x0, int y0, int x1, int y1, int[] rgba) {
        int color = packColor(rgba);
        return paint(partIdOrName, localFace, "texture_line",
                op -> {
                    op.set("from", intArrayNode(op, new int[]{x0, y0}));
                    op.set("to", intArrayNode(op, new int[]{x1, y1}));
                    op.set("color", intArrayNode(op, rgba));
                },
                (target, writer) -> PixelPaintOps.line(writer, x0, y0, x1, y1, color));
    }

    /** 4-connected flood fill seeded at (x,y). */
    public int flood(String partIdOrName, int localFace, int x, int y, int[] rgba) {
        int color = packColor(rgba);
        return paint(partIdOrName, localFace, "texture_flood",
                op -> {
                    op.set("at", intArrayNode(op, new int[]{x, y}));
                    op.set("color", intArrayNode(op, rgba));
                },
                (target, writer) -> PixelPaintOps.flood(target.canvas, writer,
                        target.canvas::isValidCoordinate, x, y, color));
    }

    /** Apply procedural noise (same generators/knobs as the texture editor). */
    public int noise(String partIdOrName, int localFace, String generator, long seed,
                     float strength, float scale, boolean gradient,
                     float blur, int octaves, float spread, float edgeSoftness) {
        NoiseGenerator gen = parseNoiseGenerator(generator, seed);
        NoiseConfig config = new NoiseConfig(gen, strength, gradient, scale,
                blur, octaves, spread, edgeSoftness);
        return paint(partIdOrName, localFace, "texture_noise",
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
                (target, writer) -> {
                    PixelCanvas canvas = target.canvas;
                    int[] before = canvas.getPixels().clone();
                    new NoiseFilter(config).apply(canvas, null);
                    int[] after = canvas.getPixels();
                    int w = canvas.getWidth();
                    int changed = 0;
                    for (int i = 0; i < before.length; i++) {
                        if (before[i] != after[i]) {
                            target.dirty.mark(i % w, i / w);
                            changed++;
                        }
                    }
                    return changed;
                });
    }

    /**
     * Resize the face's texture (nearest-neighbor). Re-registers the material
     * onto a fresh GPU texture; UVs are normalized within the material so
     * mappings are unaffected.
     */
    public TextureInfo resize(String partIdOrName, int localFace, int width, int height) {
        FacePixelStore store = requireStore();
        if (width <= 0 || height <= 0 || width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE) {
            throw new CommandException("texture size must be in [1," + MAX_TEXTURE_SIZE
                    + "], got " + width + "x" + height);
        }
        Target target = resolveTarget(partIdOrName, localFace);
        PixelCanvas current = target.canvas;
        if (current.getWidth() != width || current.getHeight() != height) {
            PixelCanvas resized = current.resized(width, height);
            int newTextureId = store.createTexture(resized);
            if (newTextureId <= 0) {
                throw new CommandException("Failed to upload the resized texture");
            }
            MaterialDefinition material = target.material;
            doc.faceTextures().registerMaterial(new MaterialDefinition(
                    material.materialId(), material.name(), newTextureId,
                    material.renderLayer(), material.properties()));

            // One batch reassignment refreshes UVs/mesh for every face sharing
            // the material (they all moved to the new texture).
            int[] faces = doc.faceTextures().getFaceIdsByMaterial()
                    .getOrDefault(material.materialId(), java.util.List.of())
                    .stream().mapToInt(Integer::intValue).toArray();
            int[] materialIds = new int[faces.length];
            java.util.Arrays.fill(materialIds, material.materialId());
            store.assignFaceMaterials(faces, materialIds);

            working.remove(material.textureId());
            working.put(newTextureId, resized);
        }
        disableAutoResize(doc.faceTextures(), target.globalFace);

        tracer.trace("texture_resize", op -> {
            op.put("part", target.part.name());
            op.put("face", localFace);
            op.set("size", intArrayNode(op, new int[]{width, height}));
        });
        return info(partIdOrName, localFace);
    }

    // ===================== Queries =====================

    /** Digest of one face's texture (mapped=false when it has none yet). */
    public TextureInfo info(String partIdOrName, int localFace) {
        ModelPartDescriptor part = owner.resolve(partIdOrName);
        owner.validateLocalFaces(part, new int[]{localFace});
        int global = ModelCommands.requireRange(part).faceStart() + localFace;
        FaceTextureMapping mapping = doc.faceTextures().getFaceMapping(global);
        MaterialDefinition material = mapping != null
                ? doc.faceTextures().getMaterial(mapping.materialId()) : null;
        if (material == null || material.materialId() == MaterialDefinition.DEFAULT.materialId()
                || material.textureId() <= 0) {
            return new TextureInfo(part.name(), localFace, false, 0, null, 0, 0);
        }
        PixelCanvas canvas = mirror(material.textureId());
        return new TextureInfo(part.name(), localFace, true, material.materialId(),
                material.name(), canvas.getWidth(), canvas.getHeight());
    }

    /** Read a rectangular pixel region as flat [r,g,b,a, ...]. */
    public Region region(String partIdOrName, int localFace, int x, int y, int w, int h) {
        Target target = resolveTarget(partIdOrName, localFace);
        PixelCanvas canvas = target.canvas;
        if (w <= 0 || h <= 0 || !canvas.isValidCoordinate(x, y)
                || !canvas.isValidCoordinate(x + w - 1, y + h - 1)) {
            throw new CommandException("region out of bounds: " + w + "x" + h + " at (" + x + ","
                    + y + ") on a " + canvas.getWidth() + "x" + canvas.getHeight() + " texture");
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

    /** A resolved paint target: the face's texture with its CPU mirror. */
    private final class Target {
        final ModelPartDescriptor part;
        final int globalFace;
        final MaterialDefinition material;
        final PixelCanvas canvas;
        final Dirty dirty = new Dirty();

        Target(ModelPartDescriptor part, int globalFace, MaterialDefinition material) {
            this.part = part;
            this.globalFace = globalFace;
            this.material = material;
            this.canvas = mirror(material.textureId());
        }
    }

    @FunctionalInterface
    private interface PaintOp {
        int run(Target target, PixelPaintOps.PixelWriter writer);
    }

    private int paint(String partIdOrName, int localFace, String opName,
                      java.util.function.Consumer<ObjectNode> traceFill, PaintOp op) {
        Target target = resolveTarget(partIdOrName, localFace);
        PixelPaintOps.PixelWriter writer = (x, y, color) -> {
            if (!target.canvas.isValidCoordinate(x, y)) return 0;
            if (target.canvas.getPixel(x, y) == color) return 0;
            target.canvas.setPixel(x, y, color);
            target.dirty.mark(x, y);
            return 1;
        };
        int changed = op.run(target, writer);
        flush(target);

        tracer.trace(opName, node -> {
            node.put("part", target.part.name());
            node.put("face", localFace);
            traceFill.accept(node);
        });
        return changed;
    }

    private void flush(Target target) {
        if (!target.dirty.has) return;
        int x = target.dirty.minX;
        int y = target.dirty.minY;
        int w = target.dirty.maxX - x + 1;
        int h = target.dirty.maxY - y + 1;
        requireStore().writeRegion(target.material.textureId(), x, y, w, h,
                target.canvas.getPixelsAsRGBABytes(x, y, w, h));
    }

    private Target resolveTarget(String partIdOrName, int localFace) {
        requireStore();
        ModelPartDescriptor part = owner.resolve(partIdOrName);
        owner.validateLocalFaces(part, new int[]{localFace});
        int global = ModelCommands.requireRange(part).faceStart() + localFace;
        FaceTextureMapping mapping = doc.faceTextures().getFaceMapping(global);
        MaterialDefinition material = mapping != null
                ? doc.faceTextures().getMaterial(mapping.materialId()) : null;
        if (material == null || material.materialId() == MaterialDefinition.DEFAULT.materialId()
                || material.textureId() <= 0) {
            throw new CommandException("face " + localFace + " of '" + part.name()
                    + "' has no texture yet",
                    "create one first: texture_create / om.tex.create(part.faces(...), size=(w,h))");
        }
        return new Target(part, global, material);
    }

    private PixelCanvas mirror(int textureId) {
        PixelCanvas cached = working.get(textureId);
        if (cached != null) return cached;
        FacePixelStore store = requireStore();
        int[] dims = store.textureSize(textureId);
        byte[] rgba = store.readPixels(textureId);
        if (dims == null || rgba == null) {
            throw new CommandException("Failed to read GPU texture " + textureId);
        }
        PixelCanvas canvas = PixelCanvas.fromRGBABytes(dims[0], dims[1], rgba);
        working.put(textureId, canvas);
        return canvas;
    }

    private FacePixelStore requireStore() {
        FacePixelStore store = doc.pixels();
        if (store == null) {
            throw new CommandException("face-texture pixels need the live editor",
                    "headless runs author data-level materials/UVs only "
                            + "(define_material / set_face_material / set_face_uv)");
        }
        return store;
    }

    private static void disableAutoResize(FaceTextureManager ftm, int globalFaceId) {
        FaceTextureMapping current = ftm.getFaceMapping(globalFaceId);
        if (current == null || !current.autoResize()) return;
        ftm.setFaceMapping(current.withAutoResize(false));
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

    private static int clampChannel(int c) {
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
            throw new CommandException("generator is required",
                    "valid: simplex, value, white");
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

    /** Dirty-rectangle accumulator for one paint command. */
    private static final class Dirty {
        boolean has;
        int minX, minY, maxX, maxY;

        void mark(int x, int y) {
            if (!has) {
                minX = maxX = x;
                minY = maxY = y;
                has = true;
                return;
            }
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
    }
}
