package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AttachmentStore}: CRUD, selection, change notification, and
 * model-space world math ({@code T(pos) · R_xyz(rot)}).
 */
class AttachmentStoreTest {

    private static OMOFormat.AttachmentPointEntry socket(String id, String name,
                                                         float x, float y, float z) {
        return new OMOFormat.AttachmentPointEntry(id, name, "part-1", "head",
                x, y, z, 0f, 0f, 0f);
    }

    @Test
    void putGetRemoveAndSize() {
        AttachmentStore store = new AttachmentStore();
        assertTrue(store.isEmpty());

        store.put(socket("a1", "face", 0, 1.5f, 0.25f));
        store.put(socket("a2", "hat", 0, 2f, 0));
        assertEquals(2, store.size());
        assertEquals("face", store.getById("a1").name());

        // put with same id replaces
        store.put(socket("a1", "face_moved", 1, 1, 1));
        assertEquals(2, store.size());
        assertEquals("face_moved", store.getById("a1").name());

        assertTrue(store.remove("a2"));
        assertFalse(store.remove("a2"));
        assertEquals(1, store.size());
    }

    @Test
    void setPointsReplacesAndNullClears() {
        AttachmentStore store = new AttachmentStore();
        store.setPoints(List.of(socket("a1", "s1", 0, 0, 0), socket("a2", "s2", 1, 1, 1)));
        assertEquals(2, store.size());

        store.setPoints(null);
        assertTrue(store.isEmpty());
        assertNull(store.getById("a1"));
    }

    @Test
    void removingSelectedClearsSelection() {
        AttachmentStore store = new AttachmentStore();
        store.put(socket("a1", "s1", 0, 0, 0));
        store.setSelectedAttachmentId("a1");
        store.remove("a1");
        assertNull(store.getSelectedAttachmentId());
    }

    @Test
    void onChangeFiresOnMutation() {
        AttachmentStore store = new AttachmentStore();
        AtomicInteger fired = new AtomicInteger();
        store.setOnChange(fired::incrementAndGet);

        store.put(socket("a1", "s1", 0, 0, 0));   // 1
        store.setPoints(List.of());                // 2
        assertEquals(2, fired.get());
    }

    @Test
    void worldTransformIsTranslationTimesRotationTimesScale() {
        AttachmentStore store = new AttachmentStore();
        store.put(new OMOFormat.AttachmentPointEntry("a1", "s1", null, null,
                1f, 2f, 3f, 0f, 90f, 0f, 0.5f, 2f, 0.5f));

        Matrix4f world = store.getWorldTransform("a1");
        Matrix4f expected = new Matrix4f()
                .translate(1, 2, 3)
                .rotateXYZ(0f, (float) Math.toRadians(90), 0f)
                .scale(0.5f, 2f, 0.5f);
        assertTrue(expected.equals(world, 1e-5f));

        Vector3f pos = store.getWorldPosition("a1");
        assertEquals(new Vector3f(1, 2, 3), pos);

        assertNull(store.getWorldTransform("unknown"));
        assertNull(store.getWorldPosition("unknown"));
    }

    @Test
    void snapshotHistoryUndoesAndRedoesMutations() {
        AttachmentStore store = new AttachmentStore();
        AttachmentCommandHistory history = new AttachmentCommandHistory(store);

        AttachmentSnapshot before = AttachmentSnapshot.capture(store);
        store.put(socket("a1", "face", 0, 1, 0));
        store.setSelectedAttachmentId("a1");
        AttachmentSnapshot after = AttachmentSnapshot.capture(store);
        history.push(before, after, "Create Socket: face");

        assertTrue(history.canUndo());
        assertTrue(history.undo());
        assertTrue(store.isEmpty());
        assertNull(store.getSelectedAttachmentId());

        assertTrue(history.canRedo());
        assertTrue(history.redo());
        assertEquals(1, store.size());
        assertEquals("a1", store.getSelectedAttachmentId());
    }

    @Test
    void equalSnapshotsAreNotPushed() {
        AttachmentStore store = new AttachmentStore();
        AttachmentCommandHistory history = new AttachmentCommandHistory(store);
        AttachmentSnapshot snap = AttachmentSnapshot.capture(store);
        history.push(snap, AttachmentSnapshot.capture(store), "no-op");
        assertFalse(history.canUndo());
    }
}
