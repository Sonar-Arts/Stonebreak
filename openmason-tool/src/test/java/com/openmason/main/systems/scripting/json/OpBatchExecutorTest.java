package com.openmason.main.systems.scripting.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import com.openmason.main.systems.scripting.doc.FakeFacePixelStore;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpBatchExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private OpBatchExecutor batch;
    private HeadlessModelDocument doc;
    private ModelCommands cmds;

    @BeforeEach
    void setUp() {
        batch = new OpBatchExecutor(mapper);
        doc = new HeadlessModelDocument();
        cmds = new ModelCommands(doc, mapper);
    }

    private JsonNode parseAndValidate(String json) {
        JsonNode root = batch.parse(json);
        batch.validate(root);
        return root;
    }

    // ===================== Validation =====================

    @Test
    void rejectsMalformedJsonAndMissingOps() {
        assertEquals(-1, assertThrows(OpBatchException.class,
                () -> batch.parse("not json")).opIndex());
        assertEquals(-1, assertThrows(OpBatchException.class,
                () -> batch.validate(batch.parse("{\"version\":1}"))).opIndex());
        assertEquals(-1, assertThrows(OpBatchException.class,
                () -> batch.validate(batch.parse("{\"version\":99,\"ops\":[{\"op\":\"remove_part\",\"part\":\"x\"}]}"))).opIndex());
    }

    @Test
    void rejectsUnknownOpWithIndexAndValidList() {
        OpBatchException e = assertThrows(OpBatchException.class, () -> parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"a"},
                  {"op":"explode_part","part":"a"}
                ]}"""));
        assertEquals(1, e.opIndex());
        assertTrue(e.hint().contains("create_part"));
    }

    @Test
    void rejectsMissingAndMistypedFields() {
        OpBatchException missing = assertThrows(OpBatchException.class, () -> parseAndValidate("""
                {"ops":[{"op":"create_part","shape":"cube"}]}"""));
        assertEquals(0, missing.opIndex());
        assertTrue(missing.getMessage().contains("name"));

        OpBatchException badVec = assertThrows(OpBatchException.class, () -> parseAndValidate("""
                {"ops":[{"op":"create_part","shape":"cube","name":"a","size":[1,2]}]}"""));
        assertTrue(badVec.getMessage().contains("size"));
    }

    @Test
    void rejectsUndefinedRefStatically() {
        OpBatchException e = assertThrows(OpBatchException.class, () -> parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"a"},
                  {"op":"inset_faces","part":"a","faces":{"ref":"cap"},"amount":0.2}
                ]}"""));
        assertEquals(1, e.opIndex());
        assertTrue(e.getMessage().contains("cap"));
    }

    @Test
    void validationNeverMutatesTheDocument() {
        assertThrows(OpBatchException.class, () -> parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"a"},
                  {"op":"bogus_op"}
                ]}"""));
        assertEquals(0, doc.parts().getPartCount(), "validation must not create parts");
    }

    // ===================== Execution =====================

    @Test
    void buildsAModelWithAsRefChaining() {
        JsonNode root = parseAndValidate("""
                {"version":1,"ops":[
                  {"op":"create_part","shape":"cube","name":"body","size":[8,4,6],"position":[0,4,0]},
                  {"op":"extrude_faces","part":"body","faces":{"facing":"+y"},"offset":1.5,"as":"cap"},
                  {"op":"inset_faces","part":"body","faces":{"ref":"cap"},"amount":0.2},
                  {"op":"define_material","name":"shell","tint":[255,120,40,255]},
                  {"op":"set_face_material","part":"body","faces":[0,1],"material":"shell"},
                  {"op":"mirror_part","part":"body","axis":"x","name":"body_R"}
                ]}""");
        batch.execute(root, cmds);

        assertEquals(2, doc.parts().getPartCount());
        // cube 6 faces + extrude 4 sides + inset on the 4 side quads (4×4 border quads)
        assertEquals(6 + 4 + 16, cmds.info("body").faces());
        assertNotNull(doc.parts().getPartByName("body_R").orElse(null));
    }

    @Test
    void runtimeFailureReportsOpIndex() {
        JsonNode root = parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"a"},
                  {"op":"translate","part":"MISSING","delta":[1,0,0]}
                ]}""");
        OpBatchException e = assertThrows(OpBatchException.class, () -> batch.execute(root, cmds));
        assertEquals(1, e.opIndex());
        assertTrue(e.getMessage().contains("MISSING"));
    }

    @Test
    void bareArrayAndIndicesObjectBothWorkAsFaces() {
        JsonNode root = parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"a","size":[2,2,2]},
                  {"op":"scale_faces","part":"a","faces":[0],"factor":0.5},
                  {"op":"scale_faces","part":"a","faces":{"indices":[1]},"factor":0.5}
                ]}""");
        batch.execute(root, cmds);
        assertEquals(6, cmds.info("a").faces());
    }

    @Test
    void animOpsBuildAndQueueAClip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        ModelCommands local = new ModelCommands(doc, mapper, tmp);
        JsonNode root = parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"body","size":[2,2,2],"position":[0,3,0]},
                  {"op":"anim_clip","name":"idle","duration":2.0,"fps":30,"loop":true},
                  {"op":"anim_key","part":"body","time":0.0},
                  {"op":"anim_key","part":"body","time":1.0,"position":[0,3.5,0],"easing":"ease_in_out"},
                  {"op":"anim_layer","type":"overlay","mask":["body"],"priority":2},
                  {"op":"anim_save","path":"idle.omanim"}
                ]}""");
        batch.execute(root, local);

        // Deferred write: file appears only on flush.
        assertTrue(local.anim().flushSaves().get(0).endsWith("idle.omanim"));
        var parsed = new com.openmason.engine.format.oma.OMAReader().read(
                java.nio.file.Files.readAllBytes(tmp.resolve("idle.omanim")));
        assertEquals(2, parsed.tracks().get(0).keyframes().size());
        // Omitted pose at t=0 captured the part's transform.
        assertEquals(3.0f, parsed.tracks().get(0).keyframes().get(0).position().y, 1e-5);
    }

    // ===================== Texture / canvas ops =====================

    /** Validate a batch whose second op is broken; return the failure. */
    private OpBatchException expectInvalidSecondOp(String opJson) {
        OpBatchException e = assertThrows(OpBatchException.class, () -> parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"a"},
                  %s
                ]}""".formatted(opJson)));
        assertEquals(1, e.opIndex());
        return e;
    }

    @Test
    void textureOpsValidateRequiredFields() {
        OpBatchException noSize = expectInvalidSecondOp(
                """
                {"op":"texture_create","part":"a","faces":[0]}""");
        assertTrue(noSize.getMessage().contains("size"));

        OpBatchException badColor = expectInvalidSecondOp(
                """
                {"op":"texture_fill","part":"a","face":0,"color":[1,2,3]}""");
        assertTrue(badColor.getMessage().contains("color"));

        OpBatchException noFrom = expectInvalidSecondOp(
                """
                {"op":"texture_line","part":"a","face":0,"to":[7,7],"color":[255,0,0,255]}""");
        assertTrue(noFrom.getMessage().contains("from"));
        OpBatchException noTo = expectInvalidSecondOp(
                """
                {"op":"texture_line","part":"a","face":0,"from":[0,0],"color":[255,0,0,255]}""");
        assertTrue(noTo.getMessage().contains("to"));

        // Pixel arrays must be 6 ints per pixel.
        OpBatchException badPixels = expectInvalidSecondOp(
                """
                {"op":"texture_set_pixels","part":"a","face":0,"pixels":[1,2,3,4,5]}""");
        assertTrue(badPixels.getMessage().contains("pixels"));

        assertEquals(0, doc.parts().getPartCount(), "validation must not create parts");
    }

    @Test
    void canvasOpsValidateRequiredFields() {
        OpBatchException noIndex = expectInvalidSecondOp(
                """
                {"op":"canvas_set_layer","name":"Top"}""");
        assertTrue(noIndex.getMessage().contains("index"));

        OpBatchException noPath = expectInvalidSecondOp(
                """
                {"op":"canvas_export_png"}""");
        assertTrue(noPath.getMessage().contains("path"));

        OpBatchException badPixels = expectInvalidSecondOp(
                """
                {"op":"canvas_set_pixels","pixels":[1,2,3,4,5,6,7]}""");
        assertTrue(badPixels.getMessage().contains("pixels"));
    }

    @Test
    void textureBatchPaintsThroughTheFakeStore() {
        HeadlessModelDocument inner = new HeadlessModelDocument();
        FakeFacePixelStore store = new FakeFacePixelStore(inner.faceTextures());
        ModelCommands texCmds = new ModelCommands(
                FakeFacePixelStore.document(inner, store), mapper);

        batch.execute(parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"panel","size":[2,2,2]},
                  {"op":"texture_create","part":"panel","faces":{"facing":"+y"},
                   "size":[8,8],"color":[10,20,30,255]}
                ]}"""), texCmds);
        assertEquals(1, store.textureCount());

        // The +y face id is only known at runtime — paint it in a second batch.
        int face = texCmds.selectFacesByDirection("panel", "+y")[0];
        batch.execute(parseAndValidate("""
                {"ops":[
                  {"op":"texture_fill","part":"panel","face":%d,"color":[255,0,0,255]},
                  {"op":"texture_line","part":"panel","face":%d,
                   "from":[0,0],"to":[7,7],"color":[0,0,255,255]}
                ]}""".formatted(face, face)), texCmds);

        // The fake store's bytes changed (first texture is id 1).
        assertArrayEquals(new int[]{0, 0, 255, 255}, store.pixel(1, 0, 0),
                "line overdraws the fill on the diagonal");
        assertArrayEquals(new int[]{0, 0, 255, 255}, store.pixel(1, 7, 7));
        assertArrayEquals(new int[]{255, 0, 0, 255}, store.pixel(1, 7, 0),
                "fill shows off the diagonal");
    }

    @Test
    void textureRuntimeFailureReportsOpIndex() {
        HeadlessModelDocument inner = new HeadlessModelDocument();
        FakeFacePixelStore store = new FakeFacePixelStore(inner.faceTextures());
        ModelCommands texCmds = new ModelCommands(
                FakeFacePixelStore.document(inner, store), mapper);

        JsonNode root = parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"panel","size":[2,2,2]},
                  {"op":"create_part","shape":"cube","name":"bare","size":[2,2,2]},
                  {"op":"texture_create","part":"panel","faces":[0],"size":[8,8]},
                  {"op":"texture_fill","part":"bare","face":0,"color":[255,0,0,255]}
                ]}""");
        OpBatchException e = assertThrows(OpBatchException.class,
                () -> batch.execute(root, texCmds));
        assertEquals(3, e.opIndex(), "the unmapped-face fill is op 3");
        assertTrue(e.getMessage().contains("no texture"));
        assertTrue(e.hint().contains("texture_create"));
    }

    @Test
    void setVertexMoveVerticesAndGeometryOps() {
        JsonNode root = parseAndValidate("""
                {"ops":[
                  {"op":"create_part","shape":"cube","name":"a","size":[2,2,2]},
                  {"op":"move_vertices","part":"a","vertices":[0],"xyz":[0.5,0,0]},
                  {"op":"set_vertex","part":"a","index":0,"position":[3,3,3]},
                  {"op":"set_geometry","part":"a",
                   "vertices":[0,0,0, 1,0,0, 1,1,0, 0,1,0],
                   "indices":[0,1,2, 0,2,3],
                   "triangle_to_face_id":[0,0]}
                ]}""");
        batch.execute(root, cmds);
        assertEquals(1, cmds.info("a").faces());
        assertEquals(4, cmds.info("a").verts());
    }
}
