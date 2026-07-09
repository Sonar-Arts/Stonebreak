package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.model.gmr.extraction.GMRFaceExtractor;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureSizer;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelPaintOps;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.services.commands.FaceTextureRegionCommand;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Headless per-face texture editing surface for the MCP server.
 *
 * <p>Provides direct access to each model face's GPU texture without requiring
 * the texture editor window to be open. The kept tools are SESSIONLESS one-shot
 * edits (read the texture, apply, upload the dirty region, one undo step) —
 * multi-step painting moved to the scripting surface ({@code om.tex} via
 * run_python_script / {@code texture_*} ops), which batches a whole paint job
 * into one atomic, single-undo run.
 *
 * <p>Faces sharing a material share the texture — editing via one face updates
 * every face referencing that material, by design.
 */
public final class FaceTextureEditingService {

    private static final Logger logger = LoggerFactory.getLogger(FaceTextureEditingService.class);
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final MainImGuiInterface mainInterface;
    private final OMTTextureLoader textureLoader = new OMTTextureLoader();

    public FaceTextureEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    // ===================== Read =====================

    public List<FaceTextureInfo> listFaceTextures() {
        return await(MainThreadExecutor.submit(() -> {
            IViewportConnector connector = requireConnector();
            FaceTextureManager ftm = requireFtm(connector);
            GMRFaceExtractor.FaceExtractionResult faceData = connector.extractFaceData();

            List<FaceTextureInfo> out = new ArrayList<>();
            if (faceData != null) {
                int[] verticesPerFace = faceData.verticesPerFace();
                for (int faceId = 0; faceId < verticesPerFace.length; faceId++) {
                    if (verticesPerFace[faceId] <= 0) continue;
                    out.add(buildInfo(connector, ftm, faceData, faceId));
                }
            } else {
                // No geometry — fall back to enumerating mappings only.
                for (FaceTextureMapping mapping : ftm.getAllMappings()) {
                    out.add(buildInfoFromMapping(connector, ftm, null, mapping));
                }
            }
            out.sort((a, b) -> Integer.compare(a.faceId(), b.faceId()));
            return out;
        }));
    }

    // ===================== Create per-face textures =====================

    /**
     * Allocate per-face materials + GPU textures for several faces in one
     * operation. Safer than creating textures one call at a time at mass
     * scale:
     * <ul>
     *   <li>The whole batch is validated up front and rejected atomically if any
     *       face is invalid — no partial application.</li>
     *   <li>The mesh's UVs are regenerated and re-uploaded exactly once instead of
     *       once per face, eliminating the O(N²) regeneration and the compounding
     *       vertex-seam duplication that produced duplication artifacts at scale.</li>
     *   <li>Material ids come from the renderer's single allocator, so they can
     *       never collide and silently overwrite each other.</li>
     *   <li>The entire batch is one undo step.</li>
     * </ul>
     */
    public List<CreateResult> createFaceTextures(List<CreateSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("No faces supplied to create textures for");
        }
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Create Face Textures (" + specs.size() + " faces)",
                () -> {
            IViewportConnector connector = requireConnector();
            FaceTextureManager ftm = requireFtm(connector);
            GMRFaceExtractor.FaceExtractionResult faceData = connector.extractFaceData();
            if (faceData == null) {
                throw new IllegalStateException("Model has no face geometry");
            }
            int faceCount = faceData.verticesPerFace().length;

            // --- Validate the entire batch before mutating anything ---
            Set<Integer> seen = new HashSet<>();
            for (CreateSpec spec : specs) {
                if (spec.width() <= 0 || spec.height() <= 0
                        || spec.width() > 1024 || spec.height() > 1024) {
                    throw new IllegalArgumentException(
                            "Dimensions must be in [1,1024] (face " + spec.faceId()
                                    + "): " + spec.width() + "x" + spec.height());
                }
                if (spec.faceId() < 0 || spec.faceId() >= faceCount
                        || faceData.verticesPerFace()[spec.faceId()] <= 0) {
                    throw new IllegalArgumentException("Face " + spec.faceId() + " does not exist");
                }
                if (!seen.add(spec.faceId())) {
                    throw new IllegalArgumentException("Duplicate face " + spec.faceId() + " in batch");
                }
                if (ftm.hasFaceMapping(spec.faceId())) {
                    FaceTextureMapping existing = ftm.getFaceMapping(spec.faceId());
                    MaterialDefinition existingMat = ftm.getMaterial(existing.materialId());
                    if (existingMat != null && existingMat.materialId() != 0) {
                        throw new IllegalStateException("Face " + spec.faceId()
                                + " already has a non-default material (" + existing.materialId()
                                + ") — use model_face_open instead");
                    }
                }
            }

            // --- Upload textures + register materials (roll back GPU on failure) ---
            int[] faceIds = new int[specs.size()];
            int[] materialIds = new int[specs.size()];
            List<Integer> uploadedTextures = new ArrayList<>(specs.size());
            try {
                for (int i = 0; i < specs.size(); i++) {
                    CreateSpec spec = specs.get(i);
                    int materialId = connector.allocateMaterialId();
                    if (materialId <= 0) {
                        throw new IllegalStateException(
                                "Failed to allocate a material id for face " + spec.faceId());
                    }
                    PixelCanvas canvas = new PixelCanvas(spec.width(), spec.height());
                    canvas.fill(PixelCanvas.packRGBA(spec.r(), spec.g(), spec.b(), spec.a()));
                    int gpuTextureId = textureLoader.uploadPixelCanvasToGPU(canvas);
                    if (gpuTextureId <= 0) {
                        throw new IllegalStateException(
                                "Failed to upload GPU texture for face " + spec.faceId());
                    }
                    uploadedTextures.add(gpuTextureId);

                    MaterialDefinition material = new MaterialDefinition(
                            materialId, "Face " + spec.faceId(), gpuTextureId,
                            MaterialDefinition.RenderLayer.OPAQUE,
                            MaterialDefinition.MaterialProperties.NONE);
                    ftm.registerMaterial(material);

                    faceIds[i] = spec.faceId();
                    materialIds[i] = materialId;
                }
            } catch (RuntimeException e) {
                for (int tex : uploadedTextures) {
                    org.lwjgl.opengl.GL11.glDeleteTextures(tex);
                }
                throw e;
            }

            // --- Single mesh regeneration + upload for the whole batch ---
            connector.setFaceTextures(faceIds, materialIds);

            // Per-face follow-up: opt out of geometry auto-resize + build results.
            List<CreateResult> results = new ArrayList<>(specs.size());
            for (int i = 0; i < specs.size(); i++) {
                CreateSpec spec = specs.get(i);
                disableAutoResize(ftm, spec.faceId());
                int[] suggested = connector.computeFaceTextureDimensions(
                        spec.faceId(), FaceTextureSizer.DEFAULT_PIXELS_PER_UNIT);
                int sw = suggested != null ? suggested[0] : 0;
                int sh = suggested != null ? suggested[1] : 0;
                results.add(new CreateResult(spec.faceId(), materialIds[i], uploadedTextures.get(i),
                        spec.width(), spec.height(), sw, sh));
            }

            logger.info("Created {} face textures in a single batch", specs.size());
            return results;
                })));
    }

    // ===================== Sessionless pixel access =====================

    /** Bulk region read — one call instead of one pixel round trip per pixel. */
    public RegionInfo getRegion(int faceId, int x, int y, int w, int h) {
        return await(MainThreadExecutor.submit(() -> {
            Target t = resolveTarget(faceId);
            if (w <= 0 || h <= 0 || !t.canvas().isValidCoordinate(x, y)
                    || !t.canvas().isValidCoordinate(x + w - 1, y + h - 1)) {
                throw new IllegalArgumentException("Region out of bounds: " + w + "x" + h
                        + " at (" + x + "," + y + ") on " + t.width()
                        + "x" + t.height() + " texture");
            }
            int[] rgba = new int[w * h * 4];
            int i = 0;
            for (int yy = y; yy < y + h; yy++) {
                for (int xx = x; xx < x + w; xx++) {
                    int[] px = PixelCanvas.unpackRGBA(t.canvas().getPixel(xx, yy));
                    rgba[i++] = px[0];
                    rgba[i++] = px[1];
                    rgba[i++] = px[2];
                    rgba[i++] = px[3];
                }
            }
            return new RegionInfo(faceId, x, y, w, h, rgba);
        }));
    }

    // ===================== Sessionless one-shot mutations =====================

    public DrawResult setPixels(int faceId, List<PixelEntry> pixels) {
        return runDraw(faceId, "Face " + faceId + " Set Pixels", edit -> {
            int changed = 0;
            for (PixelEntry p : pixels) {
                int color = PixelCanvas.packRGBA(p.r(), p.g(), p.b(), p.a());
                changed += edit.write(p.x(), p.y(), color);
            }
            return changed;
        });
    }

    public DrawResult fillFace(int faceId, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, "Face " + faceId + " Fill", edit ->
                PixelPaintOps.rect(edit::write, 0, 0,
                        edit.target.width(), edit.target.height(), color, true));
    }

    public DrawResult clearFace(int faceId) {
        return fillFace(faceId, 0, 0, 0, 0);
    }

    public DrawResult fillRect(int faceId, int x, int y, int w, int h, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, "Face " + faceId + " Fill Rect", edit ->
                PixelPaintOps.rect(edit::write, x, y, w, h, color, true));
    }

    // ===================== Resize =====================

    public ResizeResult resize(int faceId, int newWidth, int newHeight) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Resize Face Texture (face " + faceId + ")",
                () -> {
            if (newWidth <= 0 || newHeight <= 0 || newWidth > 1024 || newHeight > 1024) {
                throw new IllegalArgumentException(
                        "Dimensions must be in [1,1024]: " + newWidth + "x" + newHeight);
            }
            IViewportConnector connector = requireConnector();
            FaceTextureManager ftm = requireFtm(connector);
            FaceTextureMapping mapping = ftm.getFaceMapping(faceId);
            if (mapping == null) {
                throw new IllegalArgumentException("Face " + faceId + " has no texture mapping");
            }
            MaterialDefinition material = ftm.getMaterial(mapping.materialId());
            if (material == null || material.textureId() <= 0) {
                throw new IllegalStateException(
                        "Material " + mapping.materialId() + " has no GPU texture");
            }
            int[] dims = connector.getTextureDimensions(material.textureId());
            byte[] pixels = connector.readTexturePixels(material.textureId());
            if (dims == null || pixels == null) {
                throw new IllegalStateException(
                        "Failed to read GPU texture for material " + material.materialId());
            }
            if (dims[0] == newWidth && dims[1] == newHeight) {
                // Dimensions already match, but caller's intent to lock the size still
                // applies — disable auto-resize so future geometry edits don't fight it.
                disableAutoResize(ftm, faceId);
                return new ResizeResult(faceId, material.materialId(),
                        material.textureId(), dims[0], dims[1], false);
            }

            PixelCanvas oldCanvas = PixelCanvas.fromRGBABytes(dims[0], dims[1], pixels);
            PixelCanvas resized = oldCanvas.resized(newWidth, newHeight);
            int newTexId = textureLoader.uploadPixelCanvasToGPU(resized);
            if (newTexId <= 0) {
                throw new IllegalStateException("Failed to upload resized texture");
            }
            MaterialDefinition updated = new MaterialDefinition(
                    material.materialId(), material.name(), newTexId,
                    material.renderLayer(), material.properties());
            ftm.registerMaterial(updated);
            connector.setFaceTexture(faceId, material.materialId());

            // MCP explicitly chose the new dimensions — opt out of auto-resize so
            // the editor will not silently rescale this face when geometry changes.
            disableAutoResize(ftm, faceId);

            logger.info("Resized face {} texture from {}x{} to {}x{}",
                    faceId, dims[0], dims[1], newWidth, newHeight);
            return new ResizeResult(faceId, material.materialId(),
                    newTexId, newWidth, newHeight, true);
                })));
    }

    // ===================== Mapping helpers =====================

    /**
     * Mark the given face as opt-out from the editor's geometry-driven auto-resize.
     * Used after MCP tools explicitly size a face's texture: the caller's chosen
     * dimensions should not be silently rewritten by the UI auto-resizer.
     *
     * <p>No-op when the face has no mapping (e.g. faces still on the default
     * material that haven't been allocated a per-face texture).
     */
    private static void disableAutoResize(FaceTextureManager ftm, int faceId) {
        FaceTextureMapping current = ftm.getFaceMapping(faceId);
        if (current == null || !current.autoResize()) {
            return;
        }
        ftm.setFaceMapping(current.withAutoResize(false));
    }

    // ===================== Internal helpers =====================

    /** A face's texture resolved for direct editing (fresh GPU readback). */
    private record Target(int faceId, int materialId, int gpuTextureId,
                          int width, int height, byte[] originalRGBA, PixelCanvas canvas) {
    }

    private Target resolveTarget(int faceId) {
        IViewportConnector connector = requireConnector();
        FaceTextureManager ftm = requireFtm(connector);
        FaceTextureMapping mapping = ftm.getFaceMapping(faceId);
        if (mapping == null) {
            throw new IllegalArgumentException("Face " + faceId
                    + " has no texture mapping — create one with model_face_create_textures");
        }
        MaterialDefinition material = ftm.getMaterial(mapping.materialId());
        if (material == null || material.textureId() <= 0) {
            throw new IllegalStateException(
                    "Material " + mapping.materialId() + " has no GPU texture");
        }
        int[] dims = connector.getTextureDimensions(material.textureId());
        byte[] rgba = connector.readTexturePixels(material.textureId());
        if (dims == null || rgba == null) {
            throw new IllegalStateException(
                    "Failed to read GPU texture " + material.textureId() + " for face " + faceId);
        }
        return new Target(faceId, mapping.materialId(), material.textureId(),
                dims[0], dims[1], rgba, PixelCanvas.fromRGBABytes(dims[0], dims[1], rgba));
    }

    /** Dirty-rect edit over a resolved target; feeds {@link PixelPaintOps}. */
    private static final class Edit {
        final Target target;
        boolean hasDirty;
        int minX, minY, maxX, maxY;

        Edit(Target target) {
            this.target = target;
        }

        int write(int x, int y, int color) {
            PixelCanvas canvas = target.canvas();
            if (!canvas.isValidCoordinate(x, y)) return 0;
            if (canvas.getPixel(x, y) == color) return 0;
            canvas.setPixel(x, y, color);
            if (!hasDirty) {
                minX = maxX = x;
                minY = maxY = y;
                hasDirty = true;
            } else {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
            return 1;
        }
    }

    private interface CanvasOp {
        int run(Edit edit);
    }

    /**
     * One-shot read-modify-write: resolve the face's texture, apply the op,
     * upload the dirty region, record one undo step.
     */
    private DrawResult runDraw(int faceId, String description, CanvasOp op) {
        return await(MainThreadExecutor.submit(() -> {
            Target t = resolveTarget(faceId);
            Edit edit = new Edit(t);
            int changed = op.run(edit);
            if (edit.hasDirty) {
                int x = edit.minX;
                int y = edit.minY;
                int w = edit.maxX - x + 1;
                int h = edit.maxY - y + 1;
                byte[] before = sliceRegion(t.originalRGBA(), t.width(), x, y, w, h);
                byte[] after = t.canvas().getPixelsAsRGBABytes(x, y, w, h);
                requireConnector().updateTextureRegion(t.gpuTextureId(), x, y, w, h, after);

                ViewportController vp = mainInterface.getViewport3D();
                if (vp != null && vp.getCommandHistory() != null && vp.getModelRenderer() != null) {
                    vp.getCommandHistory().pushCompleted(new FaceTextureRegionCommand(
                            vp.getModelRenderer(), t.gpuTextureId(), x, y, w, h,
                            before, after, description));
                }
                logger.debug("{}: dirty region {}x{} at ({},{})", description, w, h, x, y);
            }
            return new DrawResult(faceId, changed, edit.hasDirty);
        }));
    }

    /** Slice a rectangular RGBA region out of a full-texture byte array. */
    private static byte[] sliceRegion(byte[] full, int texW, int x, int y, int w, int h) {
        byte[] out = new byte[w * h * 4];
        for (int row = 0; row < h; row++) {
            int srcOff = ((y + row) * texW + x) * 4;
            int dstOff = row * w * 4;
            System.arraycopy(full, srcOff, out, dstOff, w * 4);
        }
        return out;
    }

    private FaceTextureInfo buildInfo(IViewportConnector connector,
                                       FaceTextureManager ftm,
                                       GMRFaceExtractor.FaceExtractionResult faceData,
                                       int faceId) {
        FaceTextureMapping mapping = ftm.getFaceMapping(faceId);
        if (mapping != null) {
            return buildInfoFromMapping(connector, ftm, faceData, mapping);
        }
        // Unmapped face — report defaults so callers can still see the face.
        float[] normal = computeFaceNormal(faceData, faceId);
        String orientation = orientationLabel(normal);
        int vertexCount = vertexCountFor(faceData, faceId);
        String partName = connector.getPartNameForFace(faceId);
        int[] suggested = connector.computeFaceTextureDimensions(
                faceId, FaceTextureSizer.DEFAULT_PIXELS_PER_UNIT);
        int sw = suggested != null ? suggested[0] : 0;
        int sh = suggested != null ? suggested[1] : 0;
        return new FaceTextureInfo(
                faceId,
                0, "Default (no per-face mapping)",
                0, 0, 0,
                vertexCount,
                normal, orientation,
                0,
                new float[]{0.0f, 0.0f, 1.0f, 1.0f},
                partName,
                false,
                sw, sh,
                true);
    }

    private FaceTextureInfo buildInfoFromMapping(IViewportConnector connector,
                                                  FaceTextureManager ftm,
                                                  GMRFaceExtractor.FaceExtractionResult faceData,
                                                  FaceTextureMapping mapping) {
        MaterialDefinition material = ftm.getMaterial(mapping.materialId());
        String materialName = material != null ? material.name() : null;
        int gpuTexId = material != null ? material.textureId() : 0;
        int w = 0, h = 0;
        if (gpuTexId > 0) {
            int[] dims = connector.getTextureDimensions(gpuTexId);
            if (dims != null) { w = dims[0]; h = dims[1]; }
        }

        float[] normal = computeFaceNormal(faceData, mapping.faceId());
        String orientation = orientationLabel(normal);
        int vertexCount = vertexCountFor(faceData, mapping.faceId());
        String partName = connector.getPartNameForFace(mapping.faceId());

        FaceTextureMapping.UVRegion uv = mapping.uvRegion();
        int[] suggested = connector.computeFaceTextureDimensions(
                mapping.faceId(), FaceTextureSizer.DEFAULT_PIXELS_PER_UNIT);
        int sw = suggested != null ? suggested[0] : 0;
        int sh = suggested != null ? suggested[1] : 0;
        return new FaceTextureInfo(
                mapping.faceId(),
                mapping.materialId(),
                materialName,
                gpuTexId,
                w, h,
                vertexCount,
                normal,
                orientation,
                mapping.uvRotation().degrees(),
                new float[]{uv.u0(), uv.v0(), uv.u1(), uv.v1()},
                partName,
                true,
                sw, sh,
                mapping.autoResize());
    }

    private static float[] computeFaceNormal(GMRFaceExtractor.FaceExtractionResult faceData, int faceId) {
        if (faceData == null || faceId < 0 || faceId >= faceData.faceCount()) {
            return new float[]{0, 0, 0};
        }
        int vCount = faceData.verticesPerFace()[faceId];
        if (vCount < 3) return new float[]{0, 0, 0};
        int off = faceData.faceOffsets()[faceId];
        float[] p = faceData.positions();
        // n = (v1 - v0) × (v2 - v0) — outward normal for CCW winding
        float ax = p[off + 3] - p[off];
        float ay = p[off + 4] - p[off + 1];
        float az = p[off + 5] - p[off + 2];
        float bx = p[off + 6] - p[off];
        float by = p[off + 7] - p[off + 1];
        float bz = p[off + 8] - p[off + 2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return new float[]{0, 0, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    private static String orientationLabel(float[] n) {
        float ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        float max = Math.max(ax, Math.max(ay, az));
        if (max < 0.9f) return "OBLIQUE";
        if (max == ax) return n[0] > 0 ? "+X" : "-X";
        if (max == ay) return n[1] > 0 ? "+Y" : "-Y";
        return n[2] > 0 ? "+Z" : "-Z";
    }

    private static int vertexCountFor(GMRFaceExtractor.FaceExtractionResult faceData, int faceId) {
        if (faceData == null || faceId < 0 || faceId >= faceData.faceCount()) return 0;
        return faceData.verticesPerFace()[faceId];
    }

    private IViewportConnector requireConnector() {
        IViewportConnector c = mainInterface.getPropertyPanel().getViewportConnector();
        if (c == null || !c.isConnected()) {
            throw new IllegalStateException("Viewport is not connected — load a model first");
        }
        return c;
    }

    private FaceTextureManager requireFtm(IViewportConnector connector) {
        FaceTextureManager ftm = connector.getFaceTextureManager();
        if (ftm == null) {
            throw new IllegalStateException("Face texture manager unavailable — load a model first");
        }
        return ftm;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Face texture operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // ===================== DTOs =====================

    /**
     * @param suggestedWidth  geometry-derived width (pixels) for this face at the
     *                        default pixels-per-unit. Useful as a reference even
     *                        when no per-face texture is allocated yet.
     * @param suggestedHeight geometry-derived height (pixels)
     * @param autoResize      whether the editor may auto-rescale this face's
     *                        texture if geometry changes. False after MCP has
     *                        explicitly sized the texture.
     */
    public record FaceTextureInfo(
            int faceId, int materialId, String materialName,
            int gpuTextureId, int textureWidth, int textureHeight,
            int vertexCount, float[] normal, String orientation,
            int uvRotationDegrees, float[] uvRegion, String partName,
            boolean hasMapping,
            int suggestedWidth, int suggestedHeight,
            boolean autoResize) {}

    /**
     * @param suggestedWidth  geometry-derived width (pixels) that the editor would
     *                        have picked for this face. Returned so the caller can
     *                        detect when its chosen size diverged from what the
     *                        geometry naturally wants.
     * @param suggestedHeight geometry-derived height (pixels)
     */
    public record CreateResult(int faceId, int materialId, int gpuTextureId,
                                int width, int height,
                                int suggestedWidth, int suggestedHeight) {}

    /** One face's texture-creation request in a {@link #createFaceTextures} batch. */
    public record CreateSpec(int faceId, int width, int height,
                              int r, int g, int b, int a) {}

    public record ResizeResult(int faceId, int materialId, int gpuTextureId,
                                int width, int height, boolean changed) {}

    /** Row-major flat [r,g,b,a, ...] pixels for a rectangular region. */
    public record RegionInfo(int faceId, int x, int y, int width, int height, int[] rgba) {}

    public record DrawResult(int faceId, int pixelsChanged, boolean hasDirty) {}

    public record PixelEntry(int x, int y, int r, int g, int b, int a) {}
}
