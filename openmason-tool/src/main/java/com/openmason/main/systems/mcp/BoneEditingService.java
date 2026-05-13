package com.openmason.main.systems.mcp;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.skeleton.BoneCommandHistory;
import com.openmason.main.systems.skeleton.BoneSnapshot;
import com.openmason.main.systems.skeleton.BoneStore;
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
 * Thread-safe facade over Open Mason's skeleton/bone editing surface.
 *
 * <p>Every method marshals to the main/GL thread via {@link MainThreadExecutor}
 * and blocks the caller until the operation completes. Mirrors the contract
 * established by {@link ModelEditingService}.
 */
public final class BoneEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final MainImGuiInterface mainInterface;
    /**
     * BoneCommandHistory is per-BoneStore. Keyed by identity so a fresh store
     * (model reload) gets a fresh history without ever holding the previous
     * store's lifetime.
     */
    private final Map<BoneStore, BoneCommandHistory> historyByStore = new IdentityHashMap<>();

    public BoneEditingService(MainImGuiInterface mainInterface) {
        this.mainInterface = mainInterface;
    }

    /**
     * Lazily fetch (or create) the undo history for the active bone store.
     * Returns {@code null} if no store is loaded.
     */
    public BoneCommandHistory getHistory() {
        BoneStore store = optStore();
        if (store == null) return null;
        return historyByStore.computeIfAbsent(store, BoneCommandHistory::new);
    }

    /** Common before/after capture wrapper for bone mutations. */
    private <T> T recorded(String description, Supplier<T> mutation) {
        BoneStore store = optStore();
        BoneCommandHistory history = getHistory();
        if (store == null || history == null) return mutation.get();
        BoneSnapshot before = BoneSnapshot.capture(store);
        T result = mutation.get();
        BoneSnapshot after = BoneSnapshot.capture(store);
        history.push(before, after, description);
        return result;
    }

    // ===================== Read =====================

    public SkeletonInfo getSkeletonInfo() {
        return await(MainThreadExecutor.submit(() -> {
            BoneStore store = optStore();
            int count = store != null ? store.size() : 0;
            String selected = store != null ? store.getSelectedBoneId() : null;
            return new SkeletonInfo(store != null, count, selected);
        }));
    }

    public List<BoneView> listBones() {
        return await(MainThreadExecutor.submit(() -> {
            BoneStore store = requireStore();
            List<BoneView> out = new ArrayList<>(store.size());
            for (OMOFormat.BoneEntry b : store.getBones()) {
                out.add(BoneView.from(b, store));
            }
            return out;
        }));
    }

    public Optional<BoneView> getBone(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            BoneStore store = requireStore();
            OMOFormat.BoneEntry bone = resolve(store, idOrName);
            return Optional.ofNullable(bone).map(b -> BoneView.from(b, store));
        }));
    }

    public Optional<String> getSelectedBone() {
        return await(MainThreadExecutor.submit(() -> {
            BoneStore store = optStore();
            return store != null ? Optional.ofNullable(store.getSelectedBoneId()) : Optional.<String>empty();
        }));
    }

    // ===================== Mutate =====================

    public BoneView createBone(String name, String parentBoneId,
                                Vector3f origin, Vector3f position,
                                Vector3f rotation, Vector3f endpoint) {
        return await(MainThreadExecutor.submit(() -> recorded("Create Bone: " + name, () -> {
            BoneStore store = requireStore();
            String id = UUID.randomUUID().toString();
            String parent = (parentBoneId == null || parentBoneId.isBlank()) ? null : resolveId(store, parentBoneId);
            Vector3f o = origin != null ? origin : new Vector3f();
            Vector3f p = position != null ? position : new Vector3f();
            Vector3f r = rotation != null ? rotation : new Vector3f();
            Vector3f e = endpoint != null ? endpoint : new Vector3f(0, 1, 0);
            OMOFormat.BoneEntry entry = new OMOFormat.BoneEntry(
                    id, name, parent,
                    o.x, o.y, o.z,
                    p.x, p.y, p.z,
                    r.x, r.y, r.z,
                    e.x, e.y, e.z);
            store.put(entry);
            return BoneView.from(entry, store);
        })));
    }

    public boolean deleteBone(String idOrName) {
        return await(MainThreadExecutor.submit(() -> recorded("Delete Bone", () -> {
            BoneStore store = requireStore();
            OMOFormat.BoneEntry bone = resolve(store, idOrName);
            if (bone == null) return false;
            return store.remove(bone.id());
        })));
    }

    public Optional<BoneView> renameBone(String idOrName, String newName) {
        return await(MainThreadExecutor.submit(() -> recorded("Rename Bone", () -> {
            BoneStore store = requireStore();
            OMOFormat.BoneEntry b = resolve(store, idOrName);
            if (b == null) return Optional.<BoneView>empty();
            OMOFormat.BoneEntry updated = new OMOFormat.BoneEntry(
                    b.id(), newName, b.parentBoneId(),
                    b.originX(), b.originY(), b.originZ(),
                    b.posX(), b.posY(), b.posZ(),
                    b.rotX(), b.rotY(), b.rotZ(),
                    b.endpointX(), b.endpointY(), b.endpointZ());
            store.put(updated);
            return Optional.of(BoneView.from(updated, store));
        })));
    }

    public Optional<BoneView> setBoneTransform(String idOrName,
                                                Vector3f origin, Vector3f position,
                                                Vector3f rotation, Vector3f endpoint) {
        return await(MainThreadExecutor.submit(() -> recorded("Set Bone Transform", () -> {
            BoneStore store = requireStore();
            OMOFormat.BoneEntry b = resolve(store, idOrName);
            if (b == null) return Optional.<BoneView>empty();
            float ox = origin != null ? origin.x : b.originX();
            float oy = origin != null ? origin.y : b.originY();
            float oz = origin != null ? origin.z : b.originZ();
            float px = position != null ? position.x : b.posX();
            float py = position != null ? position.y : b.posY();
            float pz = position != null ? position.z : b.posZ();
            float rx = rotation != null ? rotation.x : b.rotX();
            float ry = rotation != null ? rotation.y : b.rotY();
            float rz = rotation != null ? rotation.z : b.rotZ();
            float ex = endpoint != null ? endpoint.x : b.endpointX();
            float ey = endpoint != null ? endpoint.y : b.endpointY();
            float ez = endpoint != null ? endpoint.z : b.endpointZ();
            OMOFormat.BoneEntry updated = new OMOFormat.BoneEntry(
                    b.id(), b.name(), b.parentBoneId(),
                    ox, oy, oz, px, py, pz, rx, ry, rz, ex, ey, ez);
            store.put(updated);
            return Optional.of(BoneView.from(updated, store));
        })));
    }

    public Optional<BoneView> setBoneParent(String idOrName, String parentIdOrName) {
        return await(MainThreadExecutor.submit(() -> recorded("Set Bone Parent", () -> {
            BoneStore store = requireStore();
            OMOFormat.BoneEntry b = resolve(store, idOrName);
            if (b == null) return Optional.<BoneView>empty();
            String parentId = (parentIdOrName == null || parentIdOrName.isBlank())
                    ? null : resolveId(store, parentIdOrName);
            if (parentId != null && parentId.equals(b.id())) {
                throw new IllegalArgumentException("A bone cannot be its own parent");
            }
            OMOFormat.BoneEntry updated = new OMOFormat.BoneEntry(
                    b.id(), b.name(), parentId,
                    b.originX(), b.originY(), b.originZ(),
                    b.posX(), b.posY(), b.posZ(),
                    b.rotX(), b.rotY(), b.rotZ(),
                    b.endpointX(), b.endpointY(), b.endpointZ());
            store.put(updated);
            return Optional.of(BoneView.from(updated, store));
        })));
    }

    // ===================== Undo / redo =====================

    public boolean undo() {
        return await(MainThreadExecutor.submit(() -> {
            BoneCommandHistory h = getHistory();
            return h != null && h.undo();
        }));
    }

    public boolean redo() {
        return await(MainThreadExecutor.submit(() -> {
            BoneCommandHistory h = getHistory();
            return h != null && h.redo();
        }));
    }

    public boolean canUndo() {
        return await(MainThreadExecutor.submit(() -> {
            BoneCommandHistory h = getHistory();
            return h != null && h.canUndo();
        }));
    }

    public boolean canRedo() {
        return await(MainThreadExecutor.submit(() -> {
            BoneCommandHistory h = getHistory();
            return h != null && h.canRedo();
        }));
    }

    public boolean selectBone(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            BoneStore store = requireStore();
            if (idOrName == null || idOrName.isBlank()) {
                store.setSelectedBoneId(null);
                return true;
            }
            OMOFormat.BoneEntry b = resolve(store, idOrName);
            if (b == null) return false;
            store.setSelectedBoneId(b.id());
            return true;
        }));
    }

    public boolean clearBones() {
        return await(MainThreadExecutor.submit(() -> recorded("Clear Bones", () -> {
            BoneStore store = requireStore();
            store.clear();
            store.setSelectedBoneId(null);
            return true;
        })));
    }

    public Optional<Vec3> getBoneHeadWorld(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            BoneStore store = requireStore();
            OMOFormat.BoneEntry b = resolve(store, idOrName);
            if (b == null) return Optional.<Vec3>empty();
            Vector3f v = store.getJointWorldPosition(b.id());
            return v == null ? Optional.<Vec3>empty() : Optional.of(new Vec3(v.x, v.y, v.z));
        }));
    }

    public Optional<Vec3> getBoneTailWorld(String idOrName) {
        return await(MainThreadExecutor.submit(() -> {
            BoneStore store = requireStore();
            OMOFormat.BoneEntry b = resolve(store, idOrName);
            if (b == null) return Optional.<Vec3>empty();
            Vector3f v = store.getTailWorldPosition(b.id());
            return v == null ? Optional.<Vec3>empty() : Optional.of(new Vec3(v.x, v.y, v.z));
        }));
    }

    // ===================== Helpers =====================

    private BoneStore optStore() {
        ViewportController vp = mainInterface.getViewport3D();
        return vp != null ? vp.getBoneStore() : null;
    }

    private BoneStore requireStore() {
        BoneStore s = optStore();
        if (s == null) throw new IllegalStateException("Bone store not available — viewport not initialized");
        return s;
    }

    private static OMOFormat.BoneEntry resolve(BoneStore store, String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        OMOFormat.BoneEntry byId = store.getById(idOrName);
        if (byId != null) return byId;
        for (OMOFormat.BoneEntry b : store.getBones()) {
            if (idOrName.equals(b.name())) return b;
        }
        return null;
    }

    private static String resolveId(BoneStore store, String idOrName) {
        OMOFormat.BoneEntry b = resolve(store, idOrName);
        if (b == null) {
            throw new IllegalArgumentException("Bone not found: " + idOrName);
        }
        return b.id();
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

    public record SkeletonInfo(boolean available, int boneCount, String selectedBoneId) {}

    public record Vec3(float x, float y, float z) {}

    public record BoneView(
            String id, String name, String parentBoneId, boolean root,
            Vec3 origin, Vec3 position, Vec3 rotation, Vec3 endpoint,
            Vec3 headWorld, Vec3 tailWorld
    ) {
        public static BoneView from(OMOFormat.BoneEntry b, BoneStore store) {
            Vector3f head = store != null ? store.getJointWorldPosition(b.id()) : null;
            Vector3f tail = store != null ? store.getTailWorldPosition(b.id()) : null;
            return new BoneView(
                    b.id(), b.name(), b.parentBoneId(), b.isRoot(),
                    new Vec3(b.originX(), b.originY(), b.originZ()),
                    new Vec3(b.posX(), b.posY(), b.posZ()),
                    new Vec3(b.rotX(), b.rotY(), b.rotZ()),
                    new Vec3(b.endpointX(), b.endpointY(), b.endpointZ()),
                    head != null ? new Vec3(head.x, head.y, head.z) : null,
                    tail != null ? new Vec3(tail.x, tail.y, tail.z) : null);
        }
    }
}
