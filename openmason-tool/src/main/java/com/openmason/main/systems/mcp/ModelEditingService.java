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

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        return await(MainThreadExecutor.submit(() -> {
            ensureModelLoaded();
            ModelPartManager pm = requirePartManager();
            ModelPart geometry = PartShapeFactory.create(shape, name, size);
            ModelPartDescriptor created = pm.addPart(name, geometry);
return PartView.from(created);
        }));
    }

    public boolean deletePart(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return false;
            boolean removed = requirePartManager().removePart(p.get().id());
            return removed;
        }));
    }

    public Optional<PartView> duplicatePart(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> source = resolve(idOrName);
            if (source.isEmpty()) return Optional.<PartView>empty();
            Optional<ModelPartDescriptor> dup = requirePartManager().duplicatePart(source.get().id());
            return dup.map(PartView::from);
        }));
    }

    public Optional<PartView> renamePart(String idOrName, String newName) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<PartView>empty();
            Optional<ModelPartDescriptor> renamed = requirePartManager().renamePart(p.get().id(), newName);
            return renamed.map(PartView::from);
        }));
    }

    public Optional<PartView> setPartTransform(String idOrName,
                                               Vector3f origin, Vector3f position,
                                               Vector3f rotation, Vector3f scale) {
        return await(MainThreadExecutor.submit(() -> {
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
        }));
    }

    public Optional<PartView> translatePart(String idOrName, Vector3f delta) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<PartView>empty();
            ModelPartManager pm = requirePartManager();
            pm.translatePart(p.get().id(), delta);
return pm.getPartById(p.get().id()).map(PartView::from);
        }));
    }

    public Optional<PartView> rotatePart(String idOrName, Vector3f eulerDeltaDegrees) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<PartView>empty();
            ModelPartManager pm = requirePartManager();
            pm.rotatePart(p.get().id(), eulerDeltaDegrees);
return pm.getPartById(p.get().id()).map(PartView::from);
        }));
    }

    public Optional<PartView> scalePart(String idOrName, Vector3f factors) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<PartView>empty();
            ModelPartManager pm = requirePartManager();
            pm.scalePart(p.get().id(), factors);
return pm.getPartById(p.get().id()).map(PartView::from);
        }));
    }

    public Optional<PartView> setPartVisibility(String idOrName, boolean visible) {
        return await(MainThreadExecutor.submit(() -> {
            Optional<ModelPartDescriptor> p = resolve(idOrName);
            if (p.isEmpty()) return Optional.<PartView>empty();
            ModelPartManager pm = requirePartManager();
            pm.setPartVisible(p.get().id(), visible);
return pm.getPartById(p.get().id()).map(PartView::from);
        }));
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
