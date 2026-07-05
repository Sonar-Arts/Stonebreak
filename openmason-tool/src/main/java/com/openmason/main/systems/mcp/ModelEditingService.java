package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonValue;
import com.openmason.engine.rendering.model.GenericModelRenderer;
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

    /**
     * Token-efficient part listing: compact rows by default, full
     * {@link PartView}s with {@code detail}, optional case-insensitive
     * substring filter on the name.
     */
    public List<?> listParts(boolean detail, String nameFilter) {
        return await(MainThreadExecutor.submit(() -> {
            ModelPartManager pm = requirePartManager();
            String filter = nameFilter != null ? nameFilter.toLowerCase() : null;
            var stream = pm.getAllParts().stream()
                    .filter(p -> filter == null || p.name().toLowerCase().contains(filter));
            return detail
                    ? stream.map(PartView::from).toList()
                    : stream.map(PartSummary::from).toList();
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

    public Optional<VerticesView> listPartVertices(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<VerticesView>empty();
            PartMeshRebuilder.PartGeometry geo = requirePartManager().getPartGeometry(p.get().id());
            if (geo == null || geo.vertices() == null) {
                return Optional.of(new VerticesView(0, new float[0]));
            }
            float[] verts = geo.vertices();
            return Optional.of(new VerticesView(verts.length / 3, verts.clone()));
        }));
    }

    public Optional<EdgesView> listPartEdges(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<EdgesView>empty();
            PartMeshRebuilder.PartGeometry geo = requirePartManager().getPartGeometry(p.get().id());
            if (geo == null || geo.indices() == null) {
                return Optional.of(new EdgesView(0, new int[0]));
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
            int[] pairs = new int[seen.size() * 2];
            int idx = 0;
            for (long key : seen) {
                pairs[idx++] = (int) (key >> 32);
                pairs[idx++] = (int) (key & 0xFFFFFFFFL);
            }
            return Optional.of(new EdgesView(seen.size(), pairs));
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

    /**
     * Insert a vertex on a part-local edge at parametric position {@code t}
     * (exclusive 0..1). The edge index comes from {@code part_mesh};
     * its part-local vertex pair is resolved to unique vertex indices and the
     * subdivision runs through the GMR topology op.
     */
    public Optional<SubdivideEdgeResult> subdivideEdge(String idOrName, int edgeIndex, float t) {
        if (!(t > 0.0f && t < 1.0f)) {
            throw new IllegalArgumentException("t must be strictly between 0 and 1, got " + t);
        }
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Insert Vertex on Edge", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<SubdivideEdgeResult>empty();
                    int[] localVerts = edgeVertexIndices(p.get(), edgeIndex);
                    GenericModelRenderer gmr = requireModelRenderer();
                    MeshRange range = requireMeshRange(p.get());
                    int uniqueA = toUniqueVertex(gmr, range, localVerts[0]);
                    int uniqueB = toUniqueVertex(gmr, range, localVerts[1]);
                    int newVertex = gmr.subdivideEdgeAtParameter(uniqueA, uniqueB, t);
                    if (newVertex < 0) {
                        throw new IllegalStateException("Edge subdivision failed for edge "
                                + edgeIndex + " at t=" + t);
                    }
                    ModelPartManager pm = requirePartManager();
                    Optional<ModelPartDescriptor> refreshed = pm.getPartById(p.get().id());
                    int newLocal = localVertexForUnique(gmr, refreshed.orElse(null), newVertex);
                    return Optional.of(new SubdivideEdgeResult(
                            newVertex, newLocal, refreshed.map(PartView::from).orElse(null)));
                })));
    }

    /**
     * Uniformly scale the given part-local faces about the selection's
     * area-weighted centroid (vertices shared with unselected faces move too —
     * Blender face-scale semantics).
     */
    public Optional<PartView> scaleFaces(String idOrName, int[] localFaceIds, float factor) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, "Scale Faces", () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<PartView>empty();
                    int[] globalIds = toGlobalFaceIds(p.get(), localFaceIds);
                    if (!requireModelRenderer().scaleFaces(globalIds, factor, null)) {
                        throw new IllegalStateException("Scale faces failed for "
                                + globalIds.length + " faces at factor " + factor);
                    }
                    return requirePartManager().getPartById(p.get().id()).map(PartView::from);
                })));
    }

    /**
     * Inset each of the given part-local faces individually (even-thickness
     * border of quads; each original face id keeps its inner cap). Returns the
     * new border-quad face ids.
     */
    public Optional<FacesOpResult> insetFaces(String idOrName, int[] localFaceIds, float amount) {
        return facesTopologyOp(idOrName, localFaceIds, "Inset Faces",
                (gmr, globalIds) -> gmr.insetFaces(globalIds, amount));
    }

    /**
     * Extrude each of the given part-local faces individually along its normal
     * (each original face id keeps the moved cap). Returns the new side-quad
     * face ids.
     */
    public Optional<FacesOpResult> extrudeFaces(String idOrName, int[] localFaceIds, float distance) {
        return facesTopologyOp(idOrName, localFaceIds, "Extrude Faces",
                (gmr, globalIds) -> gmr.extrudeFaces(globalIds, distance));
    }

    /** Shared inset/extrude plumbing: resolve, run, localize the new face ids. */
    private Optional<FacesOpResult> facesTopologyOp(String idOrName, int[] localFaceIds,
                                                    String label, FacesOp op) {
        return await(MainThreadExecutor.submit(() -> McpUndoCapture.runRecorded(
                mainInterface, label, () -> {
                    Optional<ModelPartDescriptor> p = resolve(idOrName);
                    if (p.isEmpty()) return Optional.<FacesOpResult>empty();
                    int[] globalIds = toGlobalFaceIds(p.get(), localFaceIds);
                    int[] newGlobalIds = op.apply(requireModelRenderer(), globalIds);
                    if (newGlobalIds == null) {
                        throw new IllegalStateException(label + " failed for "
                                + globalIds.length + " faces");
                    }
                    ModelPartManager pm = requirePartManager();
                    Optional<ModelPartDescriptor> refreshed = pm.getPartById(p.get().id());
                    int[] newLocalIds = localizeFaceIds(refreshed.orElse(null), newGlobalIds);
                    return Optional.of(new FacesOpResult(
                            newLocalIds, newGlobalIds, refreshed.map(PartView::from).orElse(null)));
                })));
    }

    @FunctionalInterface
    private interface FacesOp {
        int[] apply(GenericModelRenderer gmr, int[] globalFaceIds);
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

    private GenericModelRenderer requireModelRenderer() {
        ViewportController vp = mainInterface.getViewport3D();
        GenericModelRenderer gmr = vp != null ? vp.getModelRenderer() : null;
        if (gmr == null) throw new IllegalStateException("Model renderer not available");
        return gmr;
    }

    private static MeshRange requireMeshRange(ModelPartDescriptor part) {
        MeshRange range = part.meshRange();
        if (range == null) throw new IllegalStateException("Part has no mesh range: " + part.id());
        return range;
    }

    /** Resolve a part-local vertex index to its GMR unique vertex index. */
    private static int toUniqueVertex(GenericModelRenderer gmr, MeshRange range, int localVertex) {
        int unique = gmr.getUniqueIndexForMeshVertex(range.toGlobalVertex(localVertex));
        if (unique < 0) {
            throw new IllegalStateException("No unique vertex for part-local vertex " + localVertex);
        }
        return unique;
    }

    /**
     * Resolve part-local face ids to global face ids ({@code faceStart + local}
     * — the ids in a part's MeshRange are global-contiguous).
     */
    private static int[] toGlobalFaceIds(ModelPartDescriptor part, int[] localFaceIds) {
        if (localFaceIds == null || localFaceIds.length == 0) {
            throw new IllegalArgumentException("local_face_ids must be a non-empty array");
        }
        MeshRange range = requireMeshRange(part);
        int[] globalIds = new int[localFaceIds.length];
        for (int i = 0; i < localFaceIds.length; i++) {
            int local = localFaceIds[i];
            if (local < 0 || local >= range.faceCount()) {
                throw new IllegalArgumentException("Local face id out of bounds: " + local
                        + " (part has " + range.faceCount() + " faces)");
            }
            globalIds[i] = range.faceStart() + local;
        }
        return globalIds;
    }

    /**
     * Map global face ids back to part-local ids against the part's refreshed
     * (post-op) mesh range; -1 for ids that fall outside the part.
     */
    private static int[] localizeFaceIds(ModelPartDescriptor refreshedPart, int[] globalFaceIds) {
        int[] localIds = new int[globalFaceIds.length];
        MeshRange range = refreshedPart != null ? refreshedPart.meshRange() : null;
        for (int i = 0; i < globalFaceIds.length; i++) {
            localIds[i] = (range != null && range.containsFace(globalFaceIds[i]))
                    ? range.toLocalFace(globalFaceIds[i])
                    : -1;
        }
        return localIds;
    }

    /**
     * Best-effort part-local vertex index for a unique vertex against the
     * part's refreshed (post-op) mesh range; -1 when it cannot be mapped.
     */
    private static int localVertexForUnique(GenericModelRenderer gmr,
                                            ModelPartDescriptor refreshedPart, int uniqueVertex) {
        MeshRange range = refreshedPart != null ? refreshedPart.meshRange() : null;
        if (range == null) return -1;
        int[] meshIndices = gmr.getMeshIndicesForUniqueVertex(uniqueVertex);
        if (meshIndices == null) return -1;
        for (int meshIndex : meshIndices) {
            if (range.containsVertex(meshIndex)) {
                return range.toLocalVertex(meshIndex);
            }
        }
        return -1;
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

    /**
     * Resolve a part by id or name. Never returns empty for an unknown part —
     * throws a teaching error instead (silent nulls read as success to LLMs).
     */
    private Optional<ModelPartDescriptor> resolve(String idOrName) {
        ModelPartManager pm = requirePartManager();
        Optional<ModelPartDescriptor> byId = pm.getPartById(idOrName);
        if (byId.isPresent()) return byId;
        Optional<ModelPartDescriptor> byName = pm.getPartByName(idOrName);
        if (byName.isPresent()) return byName;
        throw McpErrors.unknownEntity("part", idOrName,
                pm.getAllParts().stream().map(ModelPartDescriptor::name).toList(),
                "list_parts");
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

    /** Serializes as a compact {@code [x, y, z]} array to keep MCP responses small. */
    public record Vec3(float x, float y, float z) {
        public static Vec3 from(Vector3f v) {
            return new Vec3(v.x, v.y, v.z);
        }

        @JsonValue
        public float[] xyz() {
            return new float[] {x, y, z};
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

    /** Flat positions array: vertex i is at positions[3i .. 3i+2]; local index == i. */
    public record VerticesView(int count, float[] positions) {}

    /** Flat vertex-index pairs: edge i is (vertexPairs[2i], vertexPairs[2i+1]); edge index == i. */
    public record EdgesView(int count, int[] vertexPairs) {}

    public record FaceView(int faceId, int[] vertices) {}

    /**
     * Result of subdivide_edge: the new vertex's GMR unique index plus a
     * best-effort part-local index (-1 when it could not be mapped back).
     */
    public record SubdivideEdgeResult(int newUniqueVertexId, int newLocalVertexIndex, PartView part) {}

    /**
     * Result of inset_faces / extrude_faces: the newly created face ids, both
     * part-local (-1 where a new face fell outside the part's refreshed range)
     * and global.
     */
    public record FacesOpResult(int[] newLocalFaceIds, int[] newGlobalFaceIds, PartView part) {}

    /** Compact list row: enough to address the part and see its weight. */
    public record PartSummary(String name, String id, int verts, int tris, boolean visible) {
        public static PartSummary from(ModelPartDescriptor d) {
            int vc = d.meshRange() != null ? d.meshRange().vertexCount() : 0;
            int ic = d.meshRange() != null ? d.meshRange().indexCount() : 0;
            return new PartSummary(d.name(), d.id(), vc, ic / 3, d.visible());
        }
    }

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
