package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OmoPartRestorerTest {
    @Test
    void loneBoundPartKeepsItsBindingThroughEditsAndSave() {
        var manager = new ModelPartManager();
        var entry = new OMOFormat.PartEntry("hand", "fist", 0,0,0, 0,0,0, 0,0,0, 1,1,1,
                0,3,0,3,0,1,true,false,null,"hand-bone");
        var mesh = new OMOFormat.MeshData(new float[]{0,0,0, 1,0,0, 0,1,0},
                null, new int[]{0,1,2}, new int[]{0}, "FLAT");
        OmoPartRestorer.restore(manager, mesh, List.of(entry), null);
        var part = manager.getPartById("hand").orElseThrow();
        assertEquals("hand-bone", part.boneId());
        assertEquals("hand-bone", part.withName("renamed").withTransform(part.transform())
                .withMeshRange(part.meshRange()).withVisible(false).withLocked(true).withParent("arm").boneId());
        var saved = OmoExportAssembler.extractPartEntries(manager, mesh);
        assertNotNull(saved, "a bound identity part cannot use partless synthesis");
        assertEquals("hand-bone", saved.getFirst().boneId());
        OmoPartRestorer.restore(manager, mesh, saved, null);
        assertEquals("hand-bone", manager.getPartById("hand").orElseThrow().boneId());
    }

    private static OMOFormat.PartEntry entry(String id, String parent, int vertexStart,
                                             int faceStart, int faceCount, float x) {
        return new OMOFormat.PartEntry(id, id, 0, 0, 0, x, 0, 0, 0, 0, 0, 1, 1, 1,
                vertexStart, 3, vertexStart, 3, faceStart, faceCount, true, false, parent);
    }

    private static OMOFormat.MeshData mesh() {
        return new OMOFormat.MeshData(new float[]{12,0,0, 13,0,0, 12,1,0, 10,0,0, 11,0,0, 10,1,0},
                new float[12], new int[]{0,1,2,3,4,5}, new int[]{0,3}, "FLAT");
    }

    @Test
    void childBeforeParentRetainsFaceAssignmentsTransformsAndSparseSlots() {
        ModelPartManager manager = new ModelPartManager();
        AtomicInteger rebuilds = new AtomicInteger();
        AtomicReference<PartMeshRebuilder.RebuildResult> output = new AtomicReference<>();
        manager.setMeshConsumer(r -> { rebuilds.incrementAndGet(); output.set(r); });
        var entries = List.of(entry("child", "parent", 0, 0, 3, 2),
                entry("parent", null, 3, 3, 1, 10));
        var result = OmoPartRestorer.restore(manager, mesh(), entries, null);
        assertEquals(List.of("child", "parent"), manager.getAllParts().stream().map(ModelPartDescriptor::id).toList());
        assertEquals(1, rebuilds.get(), "one rebuild, irrespective of hierarchy depth/part count");
        assertArrayEquals(mesh().vertices(), output.get().combinedVertices(), 1e-6f);
        assertArrayEquals(new int[]{0,3}, output.get().triangleToFaceId());
        assertEquals(3, manager.getPartById("child").orElseThrow().meshRange().faceCount());
        assertEquals(3, result.faceIds().get(3));
        assertEquals("parent", manager.getPartById("child").orElseThrow().parentId());
        // Save/export and restore a second time: the numbering must remain stable.
        var saved = new OMOFormat.MeshData(output.get().combinedVertices(), output.get().combinedTexCoords(),
                output.get().combinedIndices(), output.get().triangleToFaceId(), "FLAT");
        var savedEntries = OmoExportAssembler.extractPartEntries(manager, saved);
        OmoPartRestorer.restore(manager, saved, savedEntries, null);
        assertArrayEquals(new int[]{0,3}, output.get().triangleToFaceId());
        assertArrayEquals(mesh().vertices(), output.get().combinedVertices(), 1e-6f);
    }

    @Test
    void reorderedSavedEntriesRemapTexturesByPartInsteadOfGlobalPosition() {
        ModelPartManager manager = new ModelPartManager();
        var result = OmoPartRestorer.restore(manager, mesh(),
                List.of(entry("parent", null, 3, 3, 1, 10), entry("child", "parent", 0, 0, 3, 2)), null);
        assertEquals(0, result.faceIds().get(3), "parent's texture follows its new face range");
        assertEquals(1, result.faceIds().get(0), "child's texture follows its new face range");
    }

    @Test
    void corruptPartIsSkippedWithoutGivingItsTextureToTheNextPart() {
        ModelPartManager manager = new ModelPartManager();
        var result = OmoPartRestorer.restore(manager, mesh(),
                List.of(entry("corrupt", null, 900, 0, 3, 0), entry("parent", null, 3, 3, 1, 10)), null);
        assertEquals(List.of("corrupt"), result.skippedParts());
        assertFalse(result.faceIds().containsKey(0));
        assertEquals(0, result.faceIds().get(3));
    }

    @Test
    void denseModelRestoresWithOneRebuild() {
        int count = 50, faces = 1500, total = count * faces;
        float[] vertices = new float[total * 9];
        int[] indices = new int[total * 3], mapping = new int[total];
        var entries = new java.util.ArrayList<OMOFormat.PartEntry>();
        for (int t = 0; t < total; t++) {
            int v = t * 9;
            vertices[v] = t; vertices[v+3] = t+0.25f; vertices[v+6] = t; vertices[v+7] = 0.25f;
            indices[t*3] = t*3; indices[t*3+1] = t*3+1; indices[t*3+2] = t*3+2;
            mapping[t] = t;
        }
        for (int p = 0; p < count; p++) entries.add(new OMOFormat.PartEntry("p"+p,"p"+p,
                0,0,0,0,0,0,0,0,0,1,1,1, p*faces*3,faces*3,p*faces*3,faces*3,
                p*faces,faces,true,false,p == count-1 ? null : "p"+(p+1)));
        ModelPartManager manager = new ModelPartManager();
        AtomicInteger rebuilds = new AtomicInteger();
        manager.setMeshConsumer(r -> { rebuilds.incrementAndGet(); assertEquals(total*3,r.combinedIndices().length); });
        var result = OmoPartRestorer.restore(manager,
                new OMOFormat.MeshData(vertices,null,indices,mapping,"FLAT"),entries,null);
        assertEquals(1,rebuilds.get());
        assertEquals(total,result.faceIds().size());
        assertEquals(count,manager.getPartCount());
    }
}
