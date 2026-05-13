package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.model.gmr.extraction.GMRFaceExtractor;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Headless per-face texture editing surface for the MCP server.
 *
 * <p>Provides direct access to each model face's GPU texture without requiring the
 * texture editor window to be open. Edits are session-based: the caller opens a
 * face, mutates an in-memory {@link PixelCanvas} via texture-editor-style
 * primitives, then commits the dirty region back to the GPU via
 * {@code glTexSubImage2D}, or discards.
 *
 * <p>Sessions are keyed by {@code faceId}. Multiple faces can have concurrent
 * sessions. Sharing a material means committing one face's edits will update
 * every face referencing that material's texture — by design, mirroring how
 * the texture editor's preview pipeline behaves.
 */
public final class FaceTextureEditingService {

    private static final Logger logger = LoggerFactory.getLogger(FaceTextureEditingService.class);
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final MainImGuiInterface mainInterface;
    private final OMTTextureLoader textureLoader = new OMTTextureLoader();
    private final Map<Integer, Session> sessions = new HashMap<>();

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

    public FaceTextureInfo getFaceInfo(int faceId) {
        return await(MainThreadExecutor.submit(() -> {
            IViewportConnector connector = requireConnector();
            FaceTextureManager ftm = requireFtm(connector);
            GMRFaceExtractor.FaceExtractionResult faceData = connector.extractFaceData();
            if (faceData != null && (faceId < 0 || faceId >= faceData.verticesPerFace().length
                    || faceData.verticesPerFace()[faceId] <= 0)) {
                throw new IllegalArgumentException("Face " + faceId + " does not exist in the loaded model");
            }
            return buildInfo(connector, ftm, faceData, faceId);
        }));
    }

    // ===================== Create per-face texture =====================

    /**
     * Allocate a new material + GPU texture for a face that currently has no
     * explicit mapping (falling back to the default material). After this call,
     * the face has its own editable texture surfaced via the rest of the MCP
     * tools (open / set_pixels / commit / resize).
     */
    public CreateResult createFaceTexture(int faceId, int width, int height,
                                           int r, int g, int b, int a) {
        return await(MainThreadExecutor.submit(() -> {
            if (width <= 0 || height <= 0 || width > 1024 || height > 1024) {
                throw new IllegalArgumentException(
                        "Dimensions must be in [1,1024]: " + width + "x" + height);
            }
            IViewportConnector connector = requireConnector();
            FaceTextureManager ftm = requireFtm(connector);
            GMRFaceExtractor.FaceExtractionResult faceData = connector.extractFaceData();
            if (faceData == null || faceId < 0 || faceId >= faceData.verticesPerFace().length
                    || faceData.verticesPerFace()[faceId] <= 0) {
                throw new IllegalArgumentException("Face " + faceId + " does not exist");
            }
            if (ftm.hasFaceMapping(faceId)) {
                FaceTextureMapping existing = ftm.getFaceMapping(faceId);
                MaterialDefinition existingMat = ftm.getMaterial(existing.materialId());
                if (existingMat != null && existingMat.materialId() != 0) {
                    throw new IllegalStateException("Face " + faceId
                            + " already has a non-default material (" + existing.materialId()
                            + ") — use model_face_open instead");
                }
            }

            int newMaterialId = 1;
            for (MaterialDefinition m : ftm.getAllMaterials()) {
                if (m.materialId() >= newMaterialId) newMaterialId = m.materialId() + 1;
            }

            PixelCanvas canvas = new PixelCanvas(width, height);
            canvas.fill(PixelCanvas.packRGBA(r, g, b, a));
            int gpuTextureId = textureLoader.uploadPixelCanvasToGPU(canvas);
            if (gpuTextureId <= 0) {
                throw new IllegalStateException("Failed to upload new GPU texture for face " + faceId);
            }

            MaterialDefinition material = new MaterialDefinition(
                    newMaterialId, "Face " + faceId, gpuTextureId,
                    MaterialDefinition.RenderLayer.OPAQUE,
                    MaterialDefinition.MaterialProperties.NONE);
            ftm.registerMaterial(material);
            connector.setFaceTexture(faceId, newMaterialId);

            logger.info("Created face texture: face={} material={} gpuTex={} {}x{}",
                    faceId, newMaterialId, gpuTextureId, width, height);
            return new CreateResult(faceId, newMaterialId, gpuTextureId, width, height);
        }));
    }

    // ===================== Session lifecycle =====================

    public SessionInfo openSession(int faceId) {
        return await(MainThreadExecutor.submit(() -> {
            if (sessions.containsKey(faceId)) {
                Session existing = sessions.get(faceId);
                return new SessionInfo(faceId, existing.canvas.getWidth(), existing.canvas.getHeight(),
                        existing.materialId, existing.gpuTextureId, true);
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
            byte[] rgba = connector.readTexturePixels(material.textureId());
            if (dims == null || rgba == null) {
                throw new IllegalStateException(
                        "Failed to read GPU texture " + material.textureId() + " for face " + faceId);
            }
            PixelCanvas canvas = pixelCanvasFromRGBA(dims[0], dims[1], rgba);
            sessions.put(faceId, new Session(faceId, mapping.materialId(),
                    material.textureId(), canvas));
            return new SessionInfo(faceId, dims[0], dims[1],
                    mapping.materialId(), material.textureId(), false);
        }));
    }

    public CommitResult commitSession(int faceId) {
        return await(MainThreadExecutor.submit(() -> {
            Session session = sessions.get(faceId);
            if (session == null) {
                throw new IllegalStateException("No open session for face " + faceId);
            }
            if (!session.hasDirty) {
                sessions.remove(faceId);
                return new CommitResult(faceId, 0, 0, 0, 0, 0, true);
            }
            IViewportConnector connector = requireConnector();
            int x = session.dirtyMinX;
            int y = session.dirtyMinY;
            int w = session.dirtyMaxX - x + 1;
            int h = session.dirtyMaxY - y + 1;
            byte[] sub = session.canvas.getPixelsAsRGBABytes(x, y, w, h);
            connector.updateTextureRegion(session.gpuTextureId, x, y, w, h, sub);
            sessions.remove(faceId);
            logger.debug("Committed face {} session: dirty region {}x{} at ({},{})",
                    faceId, w, h, x, y);
            return new CommitResult(faceId, x, y, w, h, w * h, true);
        }));
    }

    public boolean discardSession(int faceId) {
        return await(MainThreadExecutor.submit(() -> sessions.remove(faceId) != null));
    }

    public List<Integer> listOpenSessions() {
        return await(MainThreadExecutor.submit(() -> new ArrayList<>(sessions.keySet())));
    }

    // ===================== Read pixel =====================

    public PixelInfo getPixel(int faceId, int x, int y) {
        return await(MainThreadExecutor.submit(() -> {
            Session s = requireSession(faceId);
            if (!s.canvas.isValidCoordinate(x, y)) {
                throw new IllegalArgumentException("Pixel out of bounds: (" + x + "," + y + ")");
            }
            int[] rgba = PixelCanvas.unpackRGBA(s.canvas.getPixel(x, y));
            return new PixelInfo(x, y, rgba[0], rgba[1], rgba[2], rgba[3]);
        }));
    }

    // ===================== Mutating primitives =====================

    public DrawResult setPixel(int faceId, int x, int y, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, s -> writePixel(s, x, y, color));
    }

    public DrawResult setPixels(int faceId, List<PixelEntry> pixels) {
        return runDraw(faceId, s -> {
            int changed = 0;
            for (PixelEntry p : pixels) {
                int color = PixelCanvas.packRGBA(p.r(), p.g(), p.b(), p.a());
                changed += writePixel(s, p.x(), p.y(), color);
            }
            return changed;
        });
    }

    public DrawResult fillFace(int faceId, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, s -> {
            int changed = 0;
            for (int y = 0; y < s.canvas.getHeight(); y++) {
                for (int x = 0; x < s.canvas.getWidth(); x++) {
                    changed += writePixel(s, x, y, color);
                }
            }
            return changed;
        });
    }

    public DrawResult clearFace(int faceId) {
        return fillFace(faceId, 0, 0, 0, 0);
    }

    public DrawResult fillRect(int faceId, int x, int y, int w, int h, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, s -> drawRectInternal(s, x, y, w, h, color, true));
    }

    public DrawResult drawRect(int faceId, int x, int y, int w, int h, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, s -> drawRectInternal(s, x, y, w, h, color, false));
    }

    public DrawResult drawLine(int faceId, int x0, int y0, int x1, int y1,
                                int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, s -> bresenhamLine(s, x0, y0, x1, y1, color));
    }

    public DrawResult floodFill(int faceId, int sx, int sy, int r, int g, int b, int a) {
        int color = PixelCanvas.packRGBA(r, g, b, a);
        return runDraw(faceId, s -> {
            if (!s.canvas.isValidCoordinate(sx, sy)) return 0;
            int target = s.canvas.getPixel(sx, sy);
            if (target == color) return 0;
            return floodFillInternal(s, sx, sy, target, color);
        });
    }

    // ===================== Resize =====================

    public ResizeResult resize(int faceId, int newWidth, int newHeight) {
        return await(MainThreadExecutor.submit(() -> {
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
                return new ResizeResult(faceId, material.materialId(),
                        material.textureId(), dims[0], dims[1], false);
            }

            PixelCanvas oldCanvas = pixelCanvasFromRGBA(dims[0], dims[1], pixels);
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

            // Drop any session bound to the old GPU texture — including sessions
            // on other faces sharing this material, since the material's textureId
            // has been swapped.
            int oldTextureId = material.textureId();
            sessions.values().removeIf(s -> s.gpuTextureId == oldTextureId);

            logger.info("Resized face {} texture from {}x{} to {}x{}",
                    faceId, dims[0], dims[1], newWidth, newHeight);
            return new ResizeResult(faceId, material.materialId(),
                    newTexId, newWidth, newHeight, true);
        }));
    }

    // ===================== Internal helpers =====================

    private interface CanvasOp {
        int run(Session session);
    }

    private DrawResult runDraw(int faceId, CanvasOp op) {
        return await(MainThreadExecutor.submit(() -> {
            Session s = requireSession(faceId);
            int changed = op.run(s);
            return new DrawResult(faceId, changed, s.hasDirty);
        }));
    }

    private int writePixel(Session s, int x, int y, int color) {
        if (!s.canvas.isValidCoordinate(x, y)) return 0;
        int old = s.canvas.getPixel(x, y);
        if (old == color) return 0;
        s.canvas.setPixel(x, y, color);
        s.markDirty(x, y);
        return 1;
    }

    private int drawRectInternal(Session s, int x, int y, int w, int h, int color, boolean filled) {
        if (w <= 0 || h <= 0) return 0;
        int x1 = x + w - 1;
        int y1 = y + h - 1;
        int changed = 0;
        for (int yy = y; yy <= y1; yy++) {
            for (int xx = x; xx <= x1; xx++) {
                if (!filled && xx != x && xx != x1 && yy != y && yy != y1) continue;
                changed += writePixel(s, xx, yy, color);
            }
        }
        return changed;
    }

    private int bresenhamLine(Session s, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int changed = 0;
        int x = x0, y = y0;
        while (true) {
            changed += writePixel(s, x, y, color);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
        return changed;
    }

    private int floodFillInternal(Session s, int startX, int startY, int target, int fillColor) {
        int w = s.canvas.getWidth();
        int h = s.canvas.getHeight();
        boolean[][] visited = new boolean[w][h];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.offer(new int[]{startX, startY});
        int changed = 0;
        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1];
            if (x < 0 || x >= w || y < 0 || y >= h || visited[x][y]) continue;
            visited[x][y] = true;
            if (s.canvas.getPixel(x, y) != target) continue;
            changed += writePixel(s, x, y, fillColor);
            queue.offer(new int[]{x + 1, y});
            queue.offer(new int[]{x - 1, y});
            queue.offer(new int[]{x, y + 1});
            queue.offer(new int[]{x, y - 1});
        }
        return changed;
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
        return new FaceTextureInfo(
                faceId,
                0, "Default (no per-face mapping)",
                0, 0, 0,
                vertexCount,
                normal, orientation,
                0,
                new float[]{0.0f, 0.0f, 1.0f, 1.0f},
                partName,
                false);
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
                true);
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

    private static PixelCanvas pixelCanvasFromRGBA(int width, int height, byte[] rgba) {
        PixelCanvas canvas = new PixelCanvas(width, height);
        int[] pixels = canvas.getPixels();
        int count = Math.min(pixels.length, rgba.length / 4);
        for (int i = 0; i < count; i++) {
            int off = i * 4;
            pixels[i] = PixelCanvas.packRGBA(
                    rgba[off] & 0xFF, rgba[off + 1] & 0xFF,
                    rgba[off + 2] & 0xFF, rgba[off + 3] & 0xFF);
        }
        return canvas;
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

    private Session requireSession(int faceId) {
        Session s = sessions.get(faceId);
        if (s == null) {
            throw new IllegalStateException(
                    "No open session for face " + faceId + " — call model_face_open first");
        }
        return s;
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

    // ===================== Session struct =====================

    private static final class Session {
        final int faceId;
        final int materialId;
        final int gpuTextureId;
        final PixelCanvas canvas;
        boolean hasDirty;
        int dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY;

        Session(int faceId, int materialId, int gpuTextureId, PixelCanvas canvas) {
            this.faceId = faceId;
            this.materialId = materialId;
            this.gpuTextureId = gpuTextureId;
            this.canvas = canvas;
        }

        void markDirty(int x, int y) {
            if (!hasDirty) {
                dirtyMinX = dirtyMaxX = x;
                dirtyMinY = dirtyMaxY = y;
                hasDirty = true;
                return;
            }
            if (x < dirtyMinX) dirtyMinX = x;
            if (x > dirtyMaxX) dirtyMaxX = x;
            if (y < dirtyMinY) dirtyMinY = y;
            if (y > dirtyMaxY) dirtyMaxY = y;
        }
    }

    // ===================== DTOs =====================

    public record FaceTextureInfo(
            int faceId, int materialId, String materialName,
            int gpuTextureId, int textureWidth, int textureHeight,
            int vertexCount, float[] normal, String orientation,
            int uvRotationDegrees, float[] uvRegion, String partName,
            boolean hasMapping) {}

    public record CreateResult(int faceId, int materialId, int gpuTextureId,
                                int width, int height) {}

    public record SessionInfo(int faceId, int width, int height,
                               int materialId, int gpuTextureId, boolean alreadyOpen) {}

    public record CommitResult(int faceId, int x, int y, int width, int height,
                                int pixelsCommitted, boolean closed) {}

    public record ResizeResult(int faceId, int materialId, int gpuTextureId,
                                int width, int height, boolean changed) {}

    public record PixelInfo(int x, int y, int r, int g, int b, int a) {}

    public record DrawResult(int faceId, int pixelsChanged, boolean hasDirty) {}

    public record PixelEntry(int x, int y, int r, int g, int b, int a) {}
}
