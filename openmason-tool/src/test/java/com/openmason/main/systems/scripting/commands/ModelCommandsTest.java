package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCommandsTest {

    private HeadlessModelDocument doc;
    private ModelCommands cmds;

    @BeforeEach
    void setUp() {
        doc = new HeadlessModelDocument();
        cmds = new ModelCommands(doc, new ObjectMapper());
    }

    // ===================== Parts =====================

    @Test
    void createPartWithTransformAndParent() {
        cmds.createPart("cube", "body", new Vector3f(8, 4, 6), new Vector3f(0, 4, 0), null, null);
        ModelCommands.PartInfo leg = cmds.createPart(
                "cube", "leg", new Vector3f(1, 3, 1), new Vector3f(3, 1.5f, 2),
                new Vector3f(0, 0, 15), "body");

        assertEquals("leg", leg.name());
        assertEquals(6, cmds.info("body").faces());
        assertEquals(List.of("body", "leg"), cmds.partNames());
        assertEquals(2, cmds.summary().totals().parts());
        // parent applied
        var legPart = doc.parts().getPartByName("leg").orElseThrow();
        var bodyPart = doc.parts().getPartByName("body").orElseThrow();
        assertEquals(bodyPart.id(), legPart.parentId());
    }

    @Test
    void createPartRejectsBadShapeDuplicateNameAndBadSize() {
        CommandException badShape = assertThrows(CommandException.class,
                () -> cmds.createPart("cyllinder", "a", null, null, null, null));
        assertTrue(badShape.hint().contains("cylinder"));

        cmds.createPart("cube", "a", null, null, null, null);
        assertThrows(CommandException.class,
                () -> cmds.createPart("cube", "a", null, null, null, null));

        assertThrows(CommandException.class,
                () -> cmds.createPart("cube", "b", new Vector3f(0, 1, 1), null, null, null));
    }

    @Test
    void unknownPartErrorsTeach() {
        cmds.createPart("cube", "body", null, null, null, null);
        CommandException e = assertThrows(CommandException.class, () -> cmds.info("bodyy"));
        assertTrue(e.getMessage().contains("body"), "error should list known parts");
    }

    @Test
    void hierarchyCycleIsRejected() {
        cmds.createPart("cube", "a", null, null, null, null);
        cmds.createPart("cube", "b", null, null, null, "a");
        assertThrows(CommandException.class, () -> cmds.setParent("a", "b", true));
    }

    @Test
    void duplicateCopiesGeometryAndOffsetsPosition() {
        cmds.createPart("cube", "leg", new Vector3f(1, 3, 1), new Vector3f(3, 0, 2), null, null);
        ModelCommands.PartInfo copy = cmds.duplicatePart("leg", "leg2", new Vector3f(-6, 0, 0));
        assertEquals(cmds.info("leg").verts(), copy.verts());
        var t = doc.parts().getPartByName("leg2").orElseThrow().transform();
        assertEquals(-3f, t.position().x, 1e-5);
        assertEquals(2f, t.position().z, 1e-5);
    }

    // ===================== Face selection =====================

    @Test
    void cubeHasExactlyOneFacePerAxisDirection() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        Set<Integer> all = new HashSet<>();
        for (String dir : new String[]{"+x", "-x", "+y", "-y", "+z", "-z"}) {
            int[] ids = cmds.selectFacesByDirection("body", dir);
            assertEquals(1, ids.length, "direction " + dir + " should match exactly one cube face");
            all.add(ids[0]);
        }
        assertEquals(6, all.size(), "the six directions must select six distinct faces");
    }

    @Test
    void facingRespectsPartRotation() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        int upBefore = cmds.selectFacesByDirection("body", "+y")[0];

        // Rotate 90° about X: a different local face now points up.
        cmds.setTransform("body", null, null, new Vector3f(90, 0, 0), null);
        int[] upAfter = cmds.selectFacesByDirection("body", "+y");
        assertEquals(1, upAfter.length);
        assertNotEquals(upBefore, upAfter[0],
                "facing selection must be world-space (rotation-aware)");
    }

    @Test
    void unknownDirectionTeaches() {
        cmds.createPart("cube", "body", null, null, null, null);
        CommandException e = assertThrows(CommandException.class,
                () -> cmds.selectFacesByDirection("body", "upwards"));
        assertTrue(e.hint().contains("+y"));
    }

    // ===================== Topology ops =====================

    @Test
    void extrudeAddsSideQuadsAndSurvivesLaterTransforms() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        int[] top = cmds.selectFacesByDirection("body", "+y");

        ModelCommands.FacesResult result = cmds.extrudeFaces("body", top, 1.0f);
        assertEquals(4, result.newLocalFaceIds().length, "quad extrude creates 4 side quads");
        assertEquals(10, cmds.info("body").faces());

        // The edit must survive a later transform (part geometry is authoritative).
        cmds.translate("body", new Vector3f(5, 0, 0));
        assertEquals(10, cmds.info("body").faces());

        // Extruded top face moved up by the offset: bbox reflects it.
        float maxY = cmds.summary().bbox()[1][1];
        assertEquals(2.0f, maxY, 1e-4, "cube half-height 1 + extrude 1");
    }

    @Test
    void insetKeepsFaceCountGrowthAndInnerCap() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        int[] top = cmds.selectFacesByDirection("body", "+y");
        ModelCommands.FacesResult result = cmds.insetFaces("body", top, 0.3f);
        assertEquals(4, result.newLocalFaceIds().length, "quad inset creates 4 border quads");
        assertEquals(10, cmds.info("body").faces());
    }

    @Test
    void scaleFacesMovesVerticesWithoutTopologyChange() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        int before = cmds.info("body").faces();
        cmds.scaleFaces("body", cmds.selectFacesByDirection("body", "+y"), 0.5f, null);
        assertEquals(before, cmds.info("body").faces());
        // Top face shrank: its corners pulled toward the centroid, so the
        // model bbox is unchanged but the top-face x-extent at y=+1 shrank.
        assertEquals(2f, cmds.summary().bbox()[1][1] - cmds.summary().bbox()[0][1], 1e-4);
    }

    @Test
    void deleteFacesRemovesAndCompacts() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        int[] top = cmds.selectFacesByDirection("body", "+y");
        cmds.deleteFaces("body", top);
        assertEquals(5, cmds.info("body").faces());
        // Face ids stay contiguous 0..4 after compaction.
        assertThrows(CommandException.class, () -> cmds.scaleFaces("body", new int[]{5}, 0.5f, null));
    }

    @Test
    void deleteAllFacesFailsAtomically() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        assertThrows(CommandException.class,
                () -> cmds.deleteFaces("body", new int[]{0, 1, 2, 3, 4, 5}));
    }

    @Test
    void subdivideEdgeAddsVertex() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        PartMeshRebuilder.PartGeometry geo =
                doc.parts().getPartByName("body").map(p -> doc.parts().getPartGeometry(p.id())).orElseThrow();
        int va = geo.indices()[0];
        int vb = geo.indices()[1];
        int vertsBefore = cmds.info("body").verts();

        int newLocal = cmds.subdivideEdge("body", va, vb, 0.5f);
        assertTrue(cmds.info("body").verts() > vertsBefore, "subdivide must add corners");
        assertTrue(newLocal >= 0, "new vertex should be mappable back to a local index");

        float[] pos = cmds.vertex("body", newLocal);
        assertNotNull(pos);
    }

    @Test
    void subdivideRejectsBadT() {
        cmds.createPart("cube", "body", null, null, null, null);
        assertThrows(CommandException.class, () -> cmds.subdivideEdge("body", 0, 1, 0f));
        assertThrows(CommandException.class, () -> cmds.subdivideEdge("body", 0, 1, 1f));
    }

    // ===================== Mirror =====================

    @Test
    void mirrorPreservesOutwardWinding() {
        cmds.createPart("cube", "leg", new Vector3f(1, 3, 1), new Vector3f(3, 0, 0), null, null);
        cmds.mirrorPart("leg", 'x', "leg_R");

        float volOriginal = signedVolume("leg");
        float volMirrored = signedVolume("leg_R");
        assertTrue(volOriginal * volMirrored > 0,
                "mirrored geometry must keep the same winding orientation (was "
                        + volOriginal + " vs " + volMirrored + ")");
        // Transform mirrored on the axis.
        assertEquals(-3f, doc.parts().getPartByName("leg_R").orElseThrow()
                .transform().position().x, 1e-5);
    }

    private float signedVolume(String partName) {
        var part = doc.parts().getPartByName(partName).orElseThrow();
        PartMeshRebuilder.PartGeometry geo = doc.parts().getPartGeometry(part.id());
        float[] v = geo.vertices();
        int[] idx = geo.indices();
        double volume = 0;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
            volume += (v[a] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1])
                    - v[a + 1] * (v[b] * v[c + 2] - v[b + 2] * v[c])
                    + v[a + 2] * (v[b] * v[c + 1] - v[b + 1] * v[c])) / 6.0;
        }
        return (float) volume;
    }

    // ===================== Vertices =====================

    @Test
    void moveVerticesDeltaAndAbsolute() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        float[] before = cmds.vertex("body", 0);
        cmds.moveVertices("body", new int[]{0}, new Vector3f(0.5f, 0, 0), false);
        float[] afterDelta = cmds.vertex("body", 0);
        assertEquals(before[0] + 0.5f, afterDelta[0], 1e-5);

        cmds.moveVertices("body", new int[]{0}, new Vector3f(9, 9, 9), true);
        assertEquals(9f, cmds.vertex("body", 0)[0], 1e-5);

        assertThrows(CommandException.class,
                () -> cmds.moveVertices("body", new int[]{9999}, new Vector3f(), false));
    }

    // ===================== Materials =====================

    @Test
    void defineAndAssignMaterial() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        int id = cmds.defineMaterial("shell", new int[]{255, 120, 40, 255}, false, null);
        assertTrue(id > 0);
        cmds.setFaceMaterial("body", new int[]{0, 1}, "shell");

        var range = doc.parts().getPartByName("body").orElseThrow().meshRange();
        var mapping = doc.faceTextures().getFaceMapping(range.faceStart());
        assertNotNull(mapping);
        assertEquals(id, mapping.materialId());

        CommandException e = assertThrows(CommandException.class,
                () -> cmds.setFaceMaterial("body", new int[]{0}, "nope"));
        assertTrue(e.getMessage().contains("shell"), "error should list known materials");
    }

    @Test
    void newFacesInheritSourceMaterialOnExtrude() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        int id = cmds.defineMaterial("shell", null, false, null);
        int[] top = cmds.selectFacesByDirection("body", "+y");
        cmds.setFaceMaterial("body", top, "shell");

        ModelCommands.FacesResult result = cmds.extrudeFaces("body", top, 0.5f);
        var range = doc.parts().getPartByName("body").orElseThrow().meshRange();
        for (int local : result.newLocalFaceIds()) {
            var mapping = doc.faceTextures().getFaceMapping(range.faceStart() + local);
            assertNotNull(mapping, "new face " + local + " should have a mapping");
            assertEquals(id, mapping.materialId(), "side quads inherit the source material");
        }
    }

    // ===================== Trace =====================

    @Test
    void everyMutationIsTraced() {
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
        cmds.translate("body", new Vector3f(1, 0, 0));
        cmds.extrudeFaces("body", cmds.selectFacesByDirection("body", "+y"), 1f);
        cmds.defineMaterial("shell", null, false, null);

        List<String> ops = cmds.opsTrace().stream().map(n -> n.get("op").asText()).toList();
        assertEquals(List.of("create_part", "translate", "extrude_faces", "define_material"), ops);
        // Trace ops carry resolved face indices (replayable without re-selection).
        assertTrue(cmds.opsTrace().get(2).get("faces").isArray());
    }
}
