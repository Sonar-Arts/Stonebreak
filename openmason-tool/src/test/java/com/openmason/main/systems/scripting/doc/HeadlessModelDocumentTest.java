package com.openmason.main.systems.scripting.doc;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring smoke test: the GL-free document rebuilds its combined mesh through
 * the part manager and exposes it as OMO MeshData — no GL context anywhere.
 */
class HeadlessModelDocumentTest {

    @Test
    void emptyDocumentHasNoMeshData() {
        assertNull(new HeadlessModelDocument().extractMeshData());
    }

    @Test
    void addingACubeProducesCombinedMeshData() {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ModelPartDescriptor part = doc.parts().addPartFromGeometry(
                "body",
                PartShapeFactory.createGeometry(PartShapeFactory.Shape.CUBE, "body", new Vector3f(2, 2, 2)),
                new Vector3f());

        assertNotNull(part.meshRange(), "part should get a mesh range from the rebuild");
        OMOFormat.MeshData mesh = doc.extractMeshData();
        assertNotNull(mesh);
        assertTrue(mesh.vertices().length > 0);
        assertEquals(mesh.indices().length / 3, mesh.triangleToFaceId().length);
        assertEquals("FLAT", mesh.uvMode());
    }

    @Test
    void inconsistentGeometryIsRejectedBeforeRegistration() {
        // Indices referencing vertices past the array must be refused up front —
        // once registered, such a part poisons every later combined rebuild
        // (out-of-bounds deep in MeshImporter, long after the bad data entered).
        HeadlessModelDocument doc = new HeadlessModelDocument();
        var badGeo = com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder.PartGeometry.of(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, // 3 vertices
                new float[6],
                new int[]{0, 1, 3},                       // index 3 out of range
                new int[]{0});
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> doc.parts().addPartFromGeometry("bad", badGeo, new Vector3f()));
        assertEquals(0, doc.parts().getPartCount(), "nothing may be registered on rejection");
        assertNull(doc.extractMeshData());
    }

    @Test
    void removingTheOnlyPartClearsMeshData() {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ModelPartDescriptor part = doc.parts().addPartFromGeometry(
                "body",
                PartShapeFactory.createGeometry(PartShapeFactory.Shape.CUBE, "body", new Vector3f(1, 1, 1)),
                new Vector3f());
        doc.parts().removePart(part.id());
        assertNull(doc.extractMeshData());
    }
}
