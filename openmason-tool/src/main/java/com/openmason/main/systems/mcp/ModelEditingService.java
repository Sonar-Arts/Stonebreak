package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.model.ModelPart;
import com.openmason.engine.rendering.model.gmr.parts.MeshRange;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import org.joml.Matrix4f;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-safe facade over Open Mason's part-editing surface.
 *
 * <p>Every method marshals to the main/GL thread via {@link MainThreadExecutor}
 * and blocks the caller until the operation completes. Designed for the MCP
 * server, which runs on its own HTTP threads but mutates GL-owned mesh buffers.
 */
public final class ModelEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final MainImGuiInterface mainInterface;

    public ModelEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    // ===================== Read =====================

    public ModelInfo getModelInfo() {
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = mainInterface.getViewport3D();
            ModelPartManager pm = vp != null ? vp.getPartManager() : null;
            int partCount = pm != null ? pm.getPartCount() : 0;
            return new ModelInfo(vp != null && pm != null, partCount);
        }));
    }

    public List<PartView> listParts() {
        return await(MainThreadExecutor.submit(() -> {
            ModelPartManager pm = requirePartManager();
            return pm.getAllParts().stream().map(PartView::from).toList();
        }));
    }

    public Optional<PartView> getPart(String idOrName) {
        return await(MainThreadExecutor.submit(() -> resolve(idOrName).map(PartView::from)));
    }

    /**
     * Deep inspection of a part for debugging: descriptor + mesh range + local bounds
     * + transform matrix, with raw vertex/index/face-mapping arrays gated by flags
     * so callers can keep responses small by default.
     */
    public Optional<PartDetail> inspectPart(String idOrName,
                                             boolean includeVertices,
                                             boolean includeTexCoords,
                                             boolean includeIndices,
                                             boolean includeFaceMapping) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<PartDetail>empty();
            ModelPartManager pm = requirePartManager();
            PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(p.get().id());
            return Optional.of(PartDetail.from(
                    p.get(), geo,
                    includeVertices, includeTexCoords, includeIndices, includeFaceMapping));
        }));
    }

    public Set<String> getSelection() {
        return await(MainThreadExecutor.submit(() -> requirePartManager().getSelectedPartIds()));
    }

    // ===================== Mutate: parts =====================

    public PartView createPart(String shapeName, String name, Vector3f size) {
        PartShapeFactory.Shape shape = parseShape(shapeName);
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Create Part: " + name, () -> {
                    ensureModelLoaded();
                    ModelPartManager pm = requirePartManager();
                    ModelPart geometry = PartShapeFactory.create(shape, name, size);
                    ModelPartDescriptor created = pm.addPart(name, geometry);
                    return PartView.from(created);
                })));
    }

    public boolean deletePart(String idOrName) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Delete Part", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return false;
                    return requirePartManager().removePart(p.get().id());
                })));
    }

    public Optional<PartView> duplicatePart(String idOrName) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Duplicate Part", () -> {
                    Optional<ModelPartDescriptor> source = resolve(idOrName);
                    if (source.isEmpty()) return Optional.<PartView>empty();
                    Optional<ModelPartDescriptor> dup = requirePartManager().duplicatePart(source.get().id());
                    return dup.map(PartView::from);
                })));
    }

    public Optional<PartView> renamePart(String idOrName, String newName) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Rename Part", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    Optional<ModelPartDescriptor> renamed = requirePartManager().renamePart(p.get().id(), newName);
                    return renamed.map(PartView::from);
                })));
    }

    public Optional<PartView> setPartTransform(String idOrName,
                                               Vector3f origin, Vector3f position,
                                               Vector3f rotation, Vector3f scale) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Set Part Transform", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    ModelPartManager pm = requirePartManager();
                    PartTransform current = p.get().transform();
                    PartTransform next = new PartTransform(
                            origin != null ? new Vector3f(origin) : new Vector3f(current.origin()),
                            position != null ? new Vector3f(position) : new Vector3f(current.position()),
                            rotation != null ? new Vector3f(rotation) : new Vector3f(current.rotation()),
                            scale != null ? new Vector3f(scale) : new Vector3f(current.scale())
                    );
                    pm.setPartTransform(p.get().id(), next);
                    return pm.getPartById(p.get().id()).map(PartView::from);
                })));
    }

    public Optional<PartView> translatePart(String idOrName, Vector3f delta) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Translate Part", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    ModelPartManager pm = requirePartManager();
                    pm.translatePart(p.get().id(), delta);
                    return pm.getPartById(p.get().id()).map(PartView::from);
                })));
    }

    public Optional<PartView> rotatePart(String idOrName, Vector3f eulerDeltaDegrees) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Rotate Part", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    ModelPartManager pm = requirePartManager();
                    pm.rotatePart(p.get().id(), eulerDeltaDegrees);
                    return pm.getPartById(p.get().id()).map(PartView::from);
                })));
    }

    public Optional<PartView> scalePart(String idOrName, Vector3f factors) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Scale Part", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    ModelPartManager pm = requirePartManager();
                    pm.scalePart(p.get().id(), factors);
                    return pm.getPartById(p.get().id()).map(PartView::from);
                })));
    }

    public Optional<PartView> setPartVisibility(String idOrName, boolean visible) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Set Part Visibility", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    ModelPartManager pm = requirePartManager();
                    pm.setPartVisible(p.get().id(), visible);
                    return pm.getPartById(p.get().id()).map(PartView::from);
                })));
    }

    public boolean selectPart(String idOrName, boolean additive) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return false;
            ModelPartManager pm = requirePartManager();
            if (!additive) pm.deselectAllParts();
            pm.selectPart(p.get().id());
            return true;
        }));
    }

    public boolean focusCameraOn(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return false;
            ModelPartManager pm = requirePartManager();
            pm.deselectAllParts();
            pm.selectPart(p.get().id());
            return true;
        }));
    }

    // ===================== Mutate: mesh elements =====================

    public Optional<List<VertexView>> listPartVertices(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<List<VertexView>>empty();
            PartMeshRebuilder.PartGeometry geo = requirePartManager().getPartGeometry(p.get().id());
            if (geo == null || geo.vertices() == null) return Optional.of(List.<VertexView>of());
            float[] verts = geo.vertices();
            int count = verts.length / 3;
            List<VertexView> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int o = i * 3;
                out.add(new VertexView(i, new Vec3(verts[o], verts[o + 1], verts[o + 2])));
            }
            return Optional.of(out);
        }));
    }

    public Optional<List<EdgeView>> listPartEdges(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<List<EdgeView>>empty();
            PartMeshRebuilder.PartGeometry geo = requirePartManager().getPartGeometry(p.get().id());
            if (geo == null || geo.indices() == null) return Optional.of(List.<EdgeView>of());
            int[] indices = geo.indices();
            int triCount = indices.length / 3;
            LinkedHashSet<Long> seen = new LinkedHashSet<>();
            for (int t = 0; t < triCount; t++) {
                int v0 = indices[t * 3], v1 = indices[t * 3 + 1], v2 = indices[t * 3 + 2];
                seen.add(edgeKey(v0, v1));
                seen.add(edgeKey(v1, v2));
                seen.add(edgeKey(v2, v0));
            }
            List<EdgeView> out = new ArrayList<>(seen.size());
            int idx = 0;
            for (long key : seen) {
                int a = (int) (key >> 32);
                int b = (int) (key & 0xFFFFFFFFL);
                out.add(new EdgeView(idx++, a, b));
            }
            return Optional.of(out);
        }));
    }

    public Optional<List<FaceView>> listPartFaces(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<List<FaceView>>empty();
            PartMeshRebuilder.PartGeometry geo = requirePartManager().getPartGeometry(p.get().id());
            if (geo == null || geo.indices() == null || geo.triangleToFaceId() == null) {
                return Optional.of(List.<FaceView>of());
            }
            int[] indices = geo.indices();
            int[] triFace = geo.triangleToFaceId();
            Map<Integer, LinkedHashSet<Integer>> faceVerts = new TreeMap<>();
            for (int t = 0; t < triFace.length; t++) {
                int faceId = triFace[t];
                LinkedHashSet<Integer> set = faceVerts.computeIfAbsent(faceId, k -> new LinkedHashSet<>());
                set.add(indices[t * 3]);
                set.add(indices[t * 3 + 1]);
                set.add(indices[t * 3 + 2]);
            }
            List<FaceView> out = new ArrayList<>(faceVerts.size());
            for (Map.Entry<Integer, LinkedHashSet<Integer>> e : faceVerts.entrySet()) {
                int[] arr = new int[e.getValue().size()];
                int i = 0;
                for (int v : e.getValue()) arr[i++] = v;
                out.add(new FaceView(e.getKey(), arr));
            }
            return Optional.of(out);
        }));
    }

    public Optional<PartView> moveVertex(String idOrName, int localIndex, Vector3f vec, boolean absolute) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Move Vertex", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    applyVertexMove(p.get(), new int[] {localIndex}, vec, absolute);
                    return requirePartManager().getPartById(p.get().id()).map(PartView::from);
                })));
    }

    public Optional<PartView> moveEdge(String idOrName, int edgeIndex, Vector3f delta) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Move Edge", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    int[] verts = edgeVertexIndices(p.get(), edgeIndex);
                    applyVertexMove(p.get(), verts, delta, false);
                    return requirePartManager().getPartById(p.get().id()).map(PartView::from);
                })));
    }

    public Optional<PartView> setPartGeometry(String idOrName,
                                               float[] vertices,
                                               int[] indices,
                                               float[] texCoords,
                                               int[] triangleToFaceId) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Set Part Geometry", () -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<PartView>empty();
            if (vertices == null || vertices.length < 3 || vertices.length % 3 != 0) {
                throw new IllegalArgumentException("vertices must be a non-empty array with length divisible by 3");
            }
            if (indices == null || indices.length < 3 || indices.length % 3 != 0) {
                throw new IllegalArgumentException("indices must be a non-empty array with length divisible by 3");
            }
            int vertexCount = vertices.length / 3;
            int triangleCount = indices.length / 3;
            for (int idx : indices) {
                if (idx < 0 || idx >= vertexCount) {
                    throw new IllegalArgumentException("index " + idx + " out of bounds (vertex count " + vertexCount + ")");
                }
            }
            float[] uvs = (texCoords != null && texCoords.length == vertexCount * 2)
                    ? texCoords
                    : new float[vertexCount * 2];
            int[] triFace;
            if (triangleToFaceId != null && triangleToFaceId.length == triangleCount) {
                triFace = triangleToFaceId;
            } else {
                triFace = new int[triangleCount];
                for (int i = 0; i < triangleCount; i++) triFace[i] = i;
            }
            PartMeshRebuilder.PartGeometry geo = PartMeshRebuilder.PartGeometry.of(
                    vertices, uvs, indices, triFace);
            ModelPartManager pm = requirePartManager();
            if (!pm.replacePartGeometry(p.get().id(), geo)) return Optional.<PartView>empty();
            return pm.getPartById(p.get().id()).map(PartView::from);
                })));
    }

    // ===================== Undo / redo =====================

    public boolean undo() {
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = mainInterface.getViewport3D();
            if (vp == null || vp.getCommandHistory() == null) return false;
            if (!vp.getCommandHistory().canUndo()) return false;
            vp.getCommandHistory().undo();
            return true;
        }));
    }

    public boolean redo() {
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = mainInterface.getViewport3D();
            if (vp == null || vp.getCommandHistory() == null) return false;
            if (!vp.getCommandHistory().canRedo()) return false;
            vp.getCommandHistory().redo();
            return true;
        }));
    }

    public boolean canUndo() {
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = mainInterface.getViewport3D();
            return vp != null && vp.getCommandHistory() != null && vp.getCommandHistory().canUndo();
        }));
    }

    public boolean canRedo() {
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = mainInterface.getViewport3D();
            return vp != null && vp.getCommandHistory() != null && vp.getCommandHistory().canRedo();
        }));
    }

    public Optional<PartView> moveFace(String idOrName, int localFaceId, Vector3f delta) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Move Face", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    int[] verts = faceVertexIndices(p.get(), localFaceId);
                    applyVertexMove(p.get(), verts, delta, false);
                    return requirePartManager().getPartById(p.get().id()).map(PartView::from);
                })));
    }

    private void applyVertexMove(ModelPartDescriptor part, int[] localIndices, Vector3f vec, boolean absolute) {
        MeshRange range = part.meshRange();
        if (range == null) throw new IllegalStateException("Part has no mesh range: " + part.id());
        ModelPartManager pm = requirePartManager();
        PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(part.id());
        if (geo == null || geo.vertices() == null) {
            throw new IllegalStateException("Part has no geometry: " + part.id());
        }
        float[] verts = geo.vertices();
        for (int local : localIndices) {
            if (local < 0 || local >= range.vertexCount()) {
                throw new IllegalArgumentException("Local vertex index out of bounds: " + local
                        + " (part has " + range.vertexCount() + " vertices)");
            }
            Vector3f next;
            if (absolute) {
                next = new Vector3f(vec);
            } else {
                int o = local * 3;
                next = new Vector3f(verts[o], verts[o + 1], verts[o + 2]).add(vec);
            }
            pm.updatePartVertex(part.id(), local, next.x, next.y, next.z);
        }
    }

    private int[] edgeVertexIndices(ModelPartDescriptor part, int edgeIndex) {
        PartMeshRebuilder.PartGeometry geo = requirePartManager().getPartGeometry(part.id());
        if (geo == null || geo.indices() == null) {
            throw new IllegalStateException("Part has no geometry: " + part.id());
        }
        int[] indices = geo.indices();
        int triCount = indices.length / 3;
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (int t = 0; t < triCount; t++) {
            int v0 = indices[t * 3], v1 = indices[t * 3 + 1], v2 = indices[t * 3 + 2];
            seen.add(edgeKey(v0, v1));
            seen.add(edgeKey(v1, v2));
            seen.add(edgeKey(v2, v0));
        }
        if (edgeIndex < 0 || edgeIndex >= seen.size()) {
            throw new IllegalArgumentException("Edge index out of bounds: " + edgeIndex
                    + " (part has " + seen.size() + " edges)");
        }
        int i = 0;
        for (long key : seen) {
            if (i++ == edgeIndex) {
                return new int[] {(int) (key >> 32), (int) (key & 0xFFFFFFFFL)};
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private int[] faceVertexIndices(ModelPartDescriptor part, int localFaceId) {
        PartMeshRebuilder.PartGeometry geo = requirePartManager().getPartGeometry(part.id());
        if (geo == null || geo.indices() == null || geo.triangleToFaceId() == null) {
            throw new IllegalStateException("Part has no face geometry: " + part.id());
        }
        int[] indices = geo.indices();
        int[] triFace = geo.triangleToFaceId();
        LinkedHashSet<Integer> verts = new LinkedHashSet<>();
        for (int t = 0; t < triFace.length; t++) {
            if (triFace[t] != localFaceId) continue;
            verts.add(indices[t * 3]);
            verts.add(indices[t * 3 + 1]);
            verts.add(indices[t * 3 + 2]);
        }
        if (verts.isEmpty()) {
            throw new IllegalArgumentException("Face id not found in part: " + localFaceId);
        }
        int[] out = new int[verts.size()];
        int i = 0;
        for (int v : verts) out[i++] = v;
        return out;
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    // ===================== Helpers =====================

    private void ensureModelLoaded() {
        ViewportController vp = mainInterface.getViewport3D();
        ModelOperationService ops = mainInterface.getModelOperations();
        if (vp == null || ops == null) {
            throw new IllegalStateException("Editor not initialized");
        }
        if (vp.getPartManager() == null || vp.getPartManager().getPartCount() == 0) {
            ops.newModel();
        }
    }

    private ModelPartManager requirePartManager() {
        ViewportController vp = mainInterface.getViewport3D();
        if (vp == null) throw new IllegalStateException("Viewport not initialized");
        ModelPartManager pm = vp.getPartManager();
        if (pm == null) throw new IllegalStateException("Part manager not available");
        return pm;
    }

    private Optional<ModelPartDescriptor> resolve(String idOrName) {
        ModelPartManager pm = requirePartManager();
        Optional<ModelPartDescriptor> byId = pm.getPartById(idOrName);
        if (byId.isPresent()) return byId;
        return pm.getPartByName(idOrName);
    }

    private static PartShapeFactory.Shape parseShape(String name) {
        if (name == null) throw new IllegalArgumentException("shape is required");
        try {
            return PartShapeFactory.Shape.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown shape '" + name
                    + "'. Valid shapes: CUBE, PYRAMID, PANE, SPRITE");
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // ===================== DTOs =====================

    public record ModelInfo(boolean modelLoaded, int partCount) {}

    public record Vec3(float x, float y, float z) {
        public static Vec3 from(Vector3f v) {
            return new Vec3(v.x, v.y, v.z);
        }
    }

    public record MeshRangeView(int vertexStart, int vertexCount,
                                 int indexStart, int indexCount,
                                 int faceStart, int faceCount) {
        public static MeshRangeView from(MeshRange r) {
            if (r == null) return null;
            return new MeshRangeView(
                    r.vertexStart(), r.vertexCount(),
                    r.indexStart(), r.indexCount(),
                    r.faceStart(), r.faceCount());
        }
    }

    public record Bounds(Vec3 min, Vec3 max, Vec3 center, Vec3 size) {
        public static Bounds fromVertices(float[] vertices) {
            if (vertices == null || vertices.length < 3) return null;
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int i = 0; i + 2 < vertices.length; i += 3) {
                float x = vertices[i], y = vertices[i + 1], z = vertices[i + 2];
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
            }
            Vec3 min = new Vec3(minX, minY, minZ);
            Vec3 max = new Vec3(maxX, maxY, maxZ);
            Vec3 center = new Vec3((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);
            Vec3 size = new Vec3(maxX - minX, maxY - minY, maxZ - minZ);
            return new Bounds(min, max, center, size);
        }
    }

    public record PartDetail(
            PartView part,
            MeshRangeView meshRange,
            Bounds localBounds,
            float[] transformMatrix,
            float[] vertices,
            float[] texCoords,
            int[] indices,
            int[] triangleToFaceId,
            boolean hasGeometry
    ) {
        public static PartDetail from(ModelPartDescriptor d,
                                      PartMeshRebuilder.PartGeometry geo,
                                      boolean includeVertices,
                                      boolean includeTexCoords,
                                      boolean includeIndices,
                                      boolean includeFaceMapping) {
            PartView view = PartView.from(d);
            MeshRangeView range = MeshRangeView.from(d.meshRange());
            float[] verts = geo != null ? geo.vertices() : null;
            Bounds bounds = Bounds.fromVertices(verts);
            Matrix4f m = d.transform().toMatrix();
            float[] mat = new float[16];
            m.get(mat);
            return new PartDetail(
                    view,
                    range,
                    bounds,
                    mat,
                    includeVertices ? verts : null,
                    includeTexCoords && geo != null ? geo.texCoords() : null,
                    includeIndices && geo != null ? geo.indices() : null,
                    includeFaceMapping && geo != null ? geo.triangleToFaceId() : null,
                    geo != null
            );
        }
    }

    public record VertexView(int localIndex, Vec3 position) {}

    public record EdgeView(int edgeIndex, int vertexA, int vertexB) {}

    public record FaceView(int localFaceId, int[] vertexIndices) {}

    public record PartView(String id, String name, boolean visible, boolean locked,
                           Vec3 origin, Vec3 position, Vec3 rotation, Vec3 scale,
                           int vertexCount, int triangleCount) {
        public static PartView from(ModelPartDescriptor d) {
            PartTransform t = d.transform();
            int vc = d.meshRange() != null ? d.meshRange().vertexCount() : 0;
            int ic = d.meshRange() != null ? d.meshRange().indexCount() : 0;
            return new PartView(
                    d.id(), d.name(), d.visible(), d.locked(),
                    Vec3.from(t.origin()), Vec3.from(t.position()),
                    Vec3.from(t.rotation()), Vec3.from(t.scale()),
                    vc, ic / 3
            );
        }
    }
}
