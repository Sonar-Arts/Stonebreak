package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonValue;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.skeleton.AttachmentCommandHistory;
import com.openmason.main.systems.skeleton.AttachmentSnapshot;
import com.openmason.main.systems.skeleton.AttachmentStore;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Thread-safe facade over Open Mason's attachment point (socket) editing
 * surface. Sockets are named frames — position + rotation + scale in
 * rest-pose model space, bound to a host part — that translate, rotate, and
 * scale whatever model is attached to them at runtime.
 *
 * <p>Every method marshals to the main/GL thread via {@link MainThreadExecutor}
 * and blocks the caller until the operation completes. Mirrors the contract
 * established by {@link BoneEditingService}.
 */
public final class AttachmentEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final MainImGuiInterface mainInterface;
    /**
     * AttachmentCommandHistory is per-AttachmentStore. Keyed by identity so a
     * fresh store (model reload) gets a fresh history without ever holding the
     * previous store's lifetime.
     */
    private final Map<AttachmentStore, AttachmentCommandHistory> historyByStore = new IdentityHashMap<>();

    public AttachmentEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    /**
     * Lazily fetch (or create) the undo history for the active attachment store.
     * Returns {@code null} if no store is loaded.
     */
    public AttachmentCommandHistory getHistory() {
        AttachmentStore store = optStore();
        if (store == null) return null;
        return historyByStore.computeIfAbsent(store, AttachmentCommandHistory::new);
    }

    /** Common before/after capture wrapper for socket mutations. */
    private <T> T recorded(String description, Supplier<T> mutation) {
        AttachmentStore store = optStore();
        AttachmentCommandHistory history = getHistory();
        if (store == null || history == null) return mutation.get();
        AttachmentSnapshot before = AttachmentSnapshot.capture(store);
        T result = mutation.get();
        AttachmentSnapshot after = AttachmentSnapshot.capture(store);
        history.push(before, after, description);
        return result;
    }

    // ===================== Read =====================

    public AttachmentsInfo getAttachmentsInfo() {
        return await(MainThreadExecutor.submit(() -> {
            AttachmentStore store = optStore();
            int count = store != null ? store.size() : 0;
            String selected = store != null ? store.getSelectedAttachmentId() : null;
            return new AttachmentsInfo(store != null, count, selected);
        }));
    }

    public List<AttachmentView> listAttachments() {
        return await(MainThreadExecutor.submit(() -> {
            AttachmentStore store = requireStore();
            List<AttachmentView> out = new ArrayList<>(store.size());
            for (OMOFormat.AttachmentPointEntry p : store.getPoints()) {
                out.add(AttachmentView.from(p));
            }
            return out;
        }));
    }

    public Optional<AttachmentView> getAttachment(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AttachmentStore store = requireStore();
            OMOFormat.AttachmentPointEntry p = resolve(store, idOrName);
            return Optional.ofNullable(p).map(AttachmentView::from);
        }));
    }

    public Optional<String> getSelectedAttachment() {
        return await(MainThreadExecutor.submit(() -> {
            AttachmentStore store = optStore();
            return store != null
                    ? Optional.ofNullable(store.getSelectedAttachmentId())
                    : Optional.<String>empty();
        }));
    }

    // ===================== Mutate =====================

    public AttachmentView createAttachment(String name, String parentPart,
                                           Vector3f position, Vector3f rotation, Vector3f scale) {
        return await(MainThreadExecutor.submit(() -> recorded("Create Socket: " + name, () -> {
            AttachmentStore store = requireStore();
            ModelPartDescriptor part = resolveParentPart(parentPart);

            // Default a part-bound socket to the part's pivot so it appears on the part.
            Vector3f p = position;
            if (p == null && part != null) {
                var partWorld = requirePartManager().getEffectiveWorldMatrix(part.id());
                p = partWorld != null ? partWorld.transformPosition(new Vector3f()) : new Vector3f();
            }
            if (p == null) p = new Vector3f();
            Vector3f r = rotation != null ? rotation : new Vector3f();
            Vector3f s = scale != null ? scale : new Vector3f(1, 1, 1);

            OMOFormat.AttachmentPointEntry entry = new OMOFormat.AttachmentPointEntry(
                    UUID.randomUUID().toString(), name,
                    part != null ? part.id() : null,
                    part != null ? part.name() : null,
                    p.x, p.y, p.z,
                    r.x, r.y, r.z,
                    s.x, s.y, s.z);
            store.put(entry);
            store.setSelectedAttachmentId(entry.id());
            return AttachmentView.from(entry);
        })));
    }

    public boolean deleteAttachment(String idOrName) {
        return await(MainThreadExecutor.submit(() -> recorded("Delete Socket", () -> {
            AttachmentStore store = requireStore();
            OMOFormat.AttachmentPointEntry p = resolve(store, idOrName);
            if (p == null) return false;
            return store.remove(p.id());
        })));
    }

    public Optional<AttachmentView> renameAttachment(String idOrName, String newName) {
        return await(MainThreadExecutor.submit(() -> recorded("Rename Socket", () -> {
            AttachmentStore store = requireStore();
            OMOFormat.AttachmentPointEntry p = resolve(store, idOrName);
            if (p == null) return Optional.<AttachmentView>empty();
            OMOFormat.AttachmentPointEntry updated = new OMOFormat.AttachmentPointEntry(
                    p.id(), newName, p.parentPartId(), p.parentPartName(),
                    p.posX(), p.posY(), p.posZ(),
                    p.rotX(), p.rotY(), p.rotZ(),
                    p.scaleX(), p.scaleY(), p.scaleZ());
            store.put(updated);
            return Optional.of(AttachmentView.from(updated));
        })));
    }

    public Optional<AttachmentView> setAttachmentTransform(String idOrName,
                                                           Vector3f position, Vector3f rotation,
                                                           Vector3f scale) {
        return await(MainThreadExecutor.submit(() -> recorded("Set Socket Transform", () -> {
            AttachmentStore store = requireStore();
            OMOFormat.AttachmentPointEntry p = resolve(store, idOrName);
            if (p == null) return Optional.<AttachmentView>empty();
            float px = position != null ? position.x : p.posX();
            float py = position != null ? position.y : p.posY();
            float pz = position != null ? position.z : p.posZ();
            float rx = rotation != null ? rotation.x : p.rotX();
            float ry = rotation != null ? rotation.y : p.rotY();
            float rz = rotation != null ? rotation.z : p.rotZ();
            float sx = scale != null ? scale.x : p.scaleX();
            float sy = scale != null ? scale.y : p.scaleY();
            float sz = scale != null ? scale.z : p.scaleZ();
            OMOFormat.AttachmentPointEntry updated = new OMOFormat.AttachmentPointEntry(
                    p.id(), p.name(), p.parentPartId(), p.parentPartName(),
                    px, py, pz, rx, ry, rz, sx, sy, sz);
            store.put(updated);
            return Optional.of(AttachmentView.from(updated));
        })));
    }

    public Optional<AttachmentView> setAttachmentParent(String idOrName, String parentPart) {
        return await(MainThreadExecutor.submit(() -> recorded("Set Socket Parent", () -> {
            AttachmentStore store = requireStore();
            OMOFormat.AttachmentPointEntry p = resolve(store, idOrName);
            if (p == null) return Optional.<AttachmentView>empty();
            ModelPartDescriptor part = resolveParentPart(parentPart);
            OMOFormat.AttachmentPointEntry updated = new OMOFormat.AttachmentPointEntry(
                    p.id(), p.name(),
                    part != null ? part.id() : null,
                    part != null ? part.name() : null,
                    p.posX(), p.posY(), p.posZ(),
                    p.rotX(), p.rotY(), p.rotZ(),
                    p.scaleX(), p.scaleY(), p.scaleZ());
            store.put(updated);
            return Optional.of(AttachmentView.from(updated));
        })));
    }

    public boolean selectAttachment(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AttachmentStore store = requireStore();
            if (idOrName == null || idOrName.isBlank()) {
                store.setSelectedAttachmentId(null);
                return true;
            }
            OMOFormat.AttachmentPointEntry p = resolve(store, idOrName);
            if (p == null) return false;
            store.setSelectedAttachmentId(p.id());
            return true;
        }));
    }

    public boolean clearAttachments() {
        return await(MainThreadExecutor.submit(() -> recorded("Clear Sockets", () -> {
            AttachmentStore store = requireStore();
            store.clear();
            return true;
        })));
    }

    // ===================== Undo / redo =====================

    public boolean undo() {
        return await(MainThreadExecutor.submit(() -> {
            AttachmentCommandHistory h = getHistory();
            return h != null && h.undo();
        }));
    }

    public boolean redo() {
        return await(MainThreadExecutor.submit(() -> {
            AttachmentCommandHistory h = getHistory();
            return h != null && h.redo();
        }));
    }

    // ===================== Helpers =====================

    private AttachmentStore optStore() {
        ViewportController vp = mainInterface.getViewport3D();
        return vp != null ? vp.getAttachmentStore() : null;
    }

    private AttachmentStore requireStore() {
        AttachmentStore s = optStore();
        if (s == null) {
            throw new IllegalStateException("Attachment store not available — viewport not initialized");
        }
        return s;
    }

    private ModelPartManager requirePartManager() {
        ViewportController vp = mainInterface.getViewport3D();
        ModelPartManager pm = vp != null ? vp.getPartManager() : null;
        if (pm == null) {
            throw new IllegalStateException("Part manager not available — viewport not initialized");
        }
        return pm;
    }

    /** Part by id, then name; null/blank → model root; unknown → error. */
    private ModelPartDescriptor resolveParentPart(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        ModelPartManager pm = requirePartManager();
        Optional<ModelPartDescriptor> byId = pm.getPartById(idOrName);
        if (byId.isPresent()) return byId.get();
        for (ModelPartDescriptor part : pm.getAllParts()) {
            if (idOrName.equals(part.name())) return part;
        }
        throw new IllegalArgumentException("Part not found: " + idOrName);
    }

    private static OMOFormat.AttachmentPointEntry resolve(AttachmentStore store, String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        OMOFormat.AttachmentPointEntry byId = store.getById(idOrName);
        if (byId != null) return byId;
        for (OMOFormat.AttachmentPointEntry p : store.getPoints()) {
            if (idOrName.equalsIgnoreCase(p.name())) return p;
        }
        return null;
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

    public record AttachmentsInfo(boolean available, int socketCount, String selectedAttachmentId) {}

    /** Serializes as a compact {@code [x, y, z]} array to keep MCP responses small. */
    public record Vec3(float x, float y, float z) {
        @JsonValue
        public float[] xyz() {
            return new float[] {x, y, z};
        }
    }

    public record AttachmentView(
            String id, String name,
            String parentPartId, String parentPartName, boolean modelRoot,
            Vec3 position, Vec3 rotation, Vec3 scale
    ) {
        public static AttachmentView from(OMOFormat.AttachmentPointEntry p) {
            return new AttachmentView(
                    p.id(), p.name(),
                    p.parentPartId(), p.parentPartName(), p.isModelRoot(),
                    new Vec3(p.posX(), p.posY(), p.posZ()),
                    new Vec3(p.rotX(), p.rotY(), p.rotZ()),
                    new Vec3(p.scaleX(), p.scaleY(), p.scaleZ()));
        }
    }
}
