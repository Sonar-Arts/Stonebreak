package com.openmason.main.systems.scripting.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.io.omo.OMODeserializer;
import com.openmason.main.systems.scripting.ScriptExecutor;
import com.openmason.main.systems.scripting.ScriptExecutor.Language;
import com.openmason.main.systems.scripting.ScriptExecutor.RunOptions;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptResult;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptSource;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end headless pipeline: JSON op batch → HeadlessModelDocument →
 * .omo on disk → OMODeserializer read-back. No GL context anywhere.
 */
class HeadlessOmoRoundTripTest {

    private static final String CRAB_SCRIPT = """
            {"version":1,"ops":[
              {"op":"create_part","shape":"cube","name":"body","size":[8,3,6],"position":[0,3,0]},
              {"op":"create_part","shape":"cube","name":"leg_L1","size":[1,3,1],"position":[4.5,1.5,2],"parent":"body"},
              {"op":"mirror_part","part":"leg_L1","axis":"x","name":"leg_R1"},
              {"op":"extrude_faces","part":"body","faces":{"facing":"+y"},"offset":1,"as":"hump"},
              {"op":"define_material","name":"shell","tint":[220,80,40,255]},
              {"op":"set_face_material","part":"body","faces":{"facing":"+y"},"material":"shell"}
            ]}""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScriptExecutor executor = new ScriptExecutor(mapper, null);

    private HeadlessModelDocument runCrab() {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ScriptResult result = executor.run(doc,
                new ScriptSource(Language.JSON_OPS, "crab.json", CRAB_SCRIPT),
                RunOptions.defaults());
        assertTrue(result.ok(), () -> "script failed: " + result.error());
        assertEquals(3, result.summary().totals().parts());
        return doc;
    }

    @Test
    void scriptToOmoAndBack(@TempDir Path tmp) throws Exception {
        HeadlessModelDocument doc = runCrab();
        Path out = tmp.resolve("crab.omo");
        HeadlessOmoWriter.write(doc, out.toString(), "crab");
        assertTrue(Files.size(out) > 0);

        OMODeserializer deserializer = new OMODeserializer();
        assertNotNull(deserializer.load(out.toString()), "the written .omo must load back");

        OMOFormat.MeshData mesh = deserializer.getLastLoadedMeshData();
        assertNotNull(mesh);
        assertTrue(mesh.hasCustomGeometry());
        OMOFormat.MeshData original = doc.extractMeshData();
        assertArrayEquals(original.vertices(), mesh.vertices(), "vertices must round-trip exactly");
        assertArrayEquals(original.indices(), mesh.indices());
        assertArrayEquals(original.triangleToFaceId(), mesh.triangleToFaceId());

        List<OMOFormat.PartEntry> parts = deserializer.getLastLoadedPartEntries();
        assertNotNull(parts);
        assertEquals(3, parts.size());
        assertTrue(parts.stream().anyMatch(p -> p.name().equals("leg_R1")));

        OMOFormat.FaceTextureData faceData = deserializer.getLastLoadedFaceTextureData();
        assertNotNull(faceData, "material assignment must serialize");
        assertTrue(faceData.materials().stream().anyMatch(m -> m.name().equals("shell")));
    }

    @Test
    void partEntrySpansMatchTheSavedMeshExactly(@TempDir Path tmp) {
        // Regression: part ranges must be computed FROM the saved arrays.
        // Topology-heavy edits can make the exported mesh layout differ from
        // the part manager's soup ranges — stale ranges made re-opened models
        // crash in MeshImporter with out-of-bounds indices.
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ScriptResult result = executor.run(doc,
                new ScriptSource(Language.JSON_OPS, "heavy.json", """
                        {"ops":[
                          {"op":"create_part","shape":"cube","name":"body","size":[8,3,6],"position":[0,3,0]},
                          {"op":"create_part","shape":"cube","name":"arm","size":[1,4,1],"position":[5,3,0],"parent":"body"},
                          {"op":"extrude_faces","part":"body","faces":{"facing":"+y"},"offset":1,"as":"cap"},
                          {"op":"inset_faces","part":"body","faces":{"ref":"cap"},"amount":0.2},
                          {"op":"extrude_faces","part":"arm","faces":{"facing":"-y"},"offset":0.5},
                          {"op":"scale_faces","part":"arm","faces":{"facing":"+y"},"factor":0.5},
                          {"op":"mirror_part","part":"arm","axis":"x","name":"arm_R"}
                        ]}"""),
                RunOptions.defaults());
        assertTrue(result.ok(), () -> "script failed: " + result.error());

        Path out = tmp.resolve("heavy.omo");
        HeadlessOmoWriter.write(doc, out.toString(), "heavy");

        OMODeserializer deserializer = new OMODeserializer();
        assertNotNull(deserializer.load(out.toString()));
        OMOFormat.MeshData mesh = deserializer.getLastLoadedMeshData();
        List<OMOFormat.PartEntry> parts = deserializer.getLastLoadedPartEntries();
        assertNotNull(parts);
        assertEquals(3, parts.size());

        int vertexTotal = mesh.vertices().length / 3;
        for (OMOFormat.PartEntry entry : parts) {
            assertTrue(entry.vertexStart() + entry.vertexCount() <= vertexTotal,
                    entry.name() + " vertex span exceeds the saved mesh");
            assertTrue(entry.indexStart() + entry.indexCount() <= mesh.indices().length,
                    entry.name() + " index span exceeds the saved mesh");
            for (int i = entry.indexStart(); i < entry.indexStart() + entry.indexCount(); i++) {
                int vertex = mesh.indices()[i];
                assertTrue(vertex >= entry.vertexStart()
                                && vertex < entry.vertexStart() + entry.vertexCount(),
                        entry.name() + " index " + vertex + " escapes its vertex span");
            }
            int triStart = entry.indexStart() / 3;
            int triEnd = (entry.indexStart() + entry.indexCount()) / 3;
            for (int t = triStart; t < triEnd; t++) {
                int faceId = mesh.triangleToFaceId()[t];
                assertTrue(faceId >= entry.faceStart()
                                && faceId < entry.faceStart() + entry.faceCount(),
                        entry.name() + " face id " + faceId + " escapes its face range");
            }
        }
    }

    @Test
    void twoRunsAreDeterministic() {
        OMOFormat.MeshData a = runCrab().extractMeshData();
        OMOFormat.MeshData b = runCrab().extractMeshData();
        assertArrayEquals(a.vertices(), b.vertices());
        assertArrayEquals(a.texCoords(), b.texCoords());
        assertArrayEquals(a.indices(), b.indices());
        assertArrayEquals(a.triangleToFaceId(), b.triangleToFaceId());
    }

    @Test
    void failedScriptWritesNothing(@TempDir Path tmp) {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ScriptResult result = executor.run(doc,
                new ScriptSource(Language.JSON_OPS, "bad.json",
                        """
                        {"ops":[
                          {"op":"create_part","shape":"cube","name":"a"},
                          {"op":"translate","part":"NOPE","delta":[1,0,0]}
                        ]}"""),
                RunOptions.defaults());
        assertFalse(result.ok());
        assertEquals(1, result.error().opIndex());
        assertNull(result.summary());
        assertEquals(0, tmp.toFile().listFiles().length, "nothing may be written on failure");
    }

    @Test
    void validateOnlyDoesNotTouchTheDocument() {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ScriptResult result = executor.run(doc,
                new ScriptSource(Language.JSON_OPS, "crab.json", CRAB_SCRIPT),
                new RunOptions(true, false, 30_000));
        assertTrue(result.ok());
        assertEquals(0, doc.parts().getPartCount());
    }
}
