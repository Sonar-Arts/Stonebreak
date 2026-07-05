package com.openmason.main.systems.scripting.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.scripting.ScriptExecutor;
import com.openmason.main.systems.scripting.ScriptExecutor.Language;
import com.openmason.main.systems.scripting.ScriptExecutor.RunOptions;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptResult;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptSource;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GraalPy engine tests. The first context in a JVM takes a few seconds to
 * warm up; the engine is shared so later tests are fast.
 */
class PythonScriptEngineTest {

    private static PythonScriptEngine engine;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void boot() {
        engine = PythonScriptEngine.ifAvailable();
        assertNotNull(engine, "GraalPy must be on the test classpath");
    }

    private static ScriptResult run(String python) {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        return new ScriptExecutor(MAPPER, engine).run(doc,
                new ScriptSource(Language.PYTHON, "script.py", python),
                new RunOptions(false, true, 30_000));
    }

    // ===================== Golden builds =====================

    @Test
    void buildsAModelWithLoopsAndChaining() {
        ScriptResult result = run("""
                import om, math
                body = om.box("body", size=(8, 3, 6), at=(0, 3, 0))
                for i in range(4):
                    a = i * math.pi / 2
                    om.cylinder("leg%d" % i, size=(1, 3, 1),
                                at=(math.cos(a) * 3, 1.5, math.sin(a) * 2), parent=body)
                cap = body.faces(facing="+y").extrude(1.0)
                cap.inset(0.2)
                om.material("shell", tint=(220, 80, 40))
                body.faces(facing="+y").set_material("shell")
                print("parts:", len(om.parts()))
                """);
        assertTrue(result.ok(), () -> "script failed: " + result.error());
        assertEquals(5, result.summary().totals().parts());
        assertTrue(result.stdout().contains("parts: 5"));
        // Python run produced a replayable JSON trace.
        assertNotNull(result.opsTrace());
        assertEquals("create_part", result.opsTrace().get(0).get("op").asText());
    }

    @Test
    void fluentStylesAndScalarBroadcastWork() {
        ScriptResult result = run("""
                import om
                p = om.box("a", size=2)          # scalar size broadcasts
                p.move(0, 1, 0).rotate(y=45).scale(1.5)
                p.move((1, 0, 0))                # tuple style
                q = p.duplicate("b", offset=(-4, 0, 0))
                om.mirror(q, axis="x", name="c")
                """);
        assertTrue(result.ok(), () -> "script failed: " + result.error());
        assertEquals(3, result.summary().totals().parts());
    }

    // ===================== Errors teach =====================

    @Test
    void commandErrorsCarryHintAndLine() {
        ScriptResult result = run("""
                import om
                om.box("body", size=(2, 2, 2))
                om.part("bodyy").move(1, 0, 0)
                """);
        assertFalse(result.ok());
        assertTrue(result.error().error().contains("body"), "should list known parts");
        assertEquals(3, result.error().line(), "line must point into the user script");
    }

    @Test
    void pythonErrorsAreShortWithLineNumbers() {
        ScriptResult result = run("""
                import om
                x = 1 / 0
                """);
        assertFalse(result.ok());
        assertTrue(result.error().error().contains("ZeroDivision"));
        assertEquals(2, result.error().line());
        assertFalse(result.error().error().contains("Traceback"), "no traceback dumps");
    }

    // ===================== Sandbox =====================

    @Test
    void sandboxBlocksFilesystemNetworkAndHostClasses() {
        assertFalse(run("open('/etc/passwd').read()").ok(), "file IO must be blocked");
        assertFalse(run("import socket\nsocket.socket()").ok(), "network must be blocked");
        assertFalse(run("import java\njava.type('java.lang.System')").ok(),
                "host class lookup must be blocked");
        assertFalse(run("import subprocess\nsubprocess.run(['ls'])").ok(),
                "subprocesses must be blocked");
    }

    @Test
    @Timeout(30)
    void infiniteLoopIsInterrupted() {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ScriptResult result = new ScriptExecutor(MAPPER, engine).run(doc,
                new ScriptSource(Language.PYTHON, "script.py", "while True:\n    pass\n"),
                new RunOptions(false, false, 2_000));
        assertFalse(result.ok());
        assertTrue(result.error().error().toLowerCase().contains("timed out"));
    }

    // ===================== Animation =====================

    @Test
    void buildsAndSavesAnimationClip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ScriptResult result = new ScriptExecutor(MAPPER, engine).run(doc,
                new ScriptSource(Language.PYTHON, "script.py", """
                        import om, math
                        body = om.box("body", size=(2, 2, 2), at=(0, 3, 0))
                        c = om.anim.clip("idle", duration=2.0, fps=30, loop=True)
                        for i in range(5):
                            t = i * 0.5
                            c.key(body, t, position=(0, 3 + 0.2 * math.sin(t * math.pi), 0))
                        c.layer(type="overlay", mask=[body], priority=1)
                        c.save("idle.omanim")
                        print(c.info()["keyframes"])
                        """),
                new RunOptions(false, false, 30_000, tmp));
        assertTrue(result.ok(), () -> "script failed: " + result.error());
        assertTrue(result.stdout().contains("5"));
        assertNotNull(result.files());
        java.nio.file.Path written = java.nio.file.Path.of(result.files().get(0));
        assertTrue(java.nio.file.Files.exists(written));
        var parsed = new com.openmason.engine.format.oma.OMAReader()
                .read(java.nio.file.Files.readAllBytes(written));
        assertEquals("idle", parsed.name());
        assertEquals(5, parsed.tracks().get(0).keyframes().size());
        assertEquals("body", parsed.tracks().get(0).partName());
    }

    @Test
    void failedScriptWritesNoAnimationFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        ScriptResult result = new ScriptExecutor(MAPPER, engine).run(doc,
                new ScriptSource(Language.PYTHON, "script.py", """
                        import om
                        body = om.box("body", size=(2, 2, 2))
                        c = om.anim.clip("idle")
                        c.key(body, 0)
                        c.save("idle.omanim")
                        raise RuntimeError("boom")
                        """),
                new RunOptions(false, false, 30_000, tmp));
        assertFalse(result.ok());
        assertFalse(java.nio.file.Files.exists(tmp.resolve("idle.omanim")),
                "deferred saves must not flush when the script fails");
    }

    // ===================== Python ↔ JSON parity =====================

    @Test
    void pythonAndJsonFrontendsProduceIdenticalGeometry() {
        ScriptResult py = run("""
                import om
                body = om.box("body", size=(8, 3, 6), at=(0, 3, 0))
                body.faces(facing="+y").extrude(1.5)
                om.mirror(om.box("leg", size=(1, 3, 1), at=(3, 1.5, 2)), axis="x", name="leg_R")
                """);
        assertTrue(py.ok(), () -> "python failed: " + py.error());

        HeadlessModelDocument jsonDoc = new HeadlessModelDocument();
        ScriptResult js = new ScriptExecutor(MAPPER, null).run(jsonDoc,
                new ScriptSource(Language.JSON_OPS, "same.json", """
                        {"ops":[
                          {"op":"create_part","shape":"cube","name":"body","size":[8,3,6],"position":[0,3,0]},
                          {"op":"extrude_faces","part":"body","faces":{"facing":"+y"},"offset":1.5},
                          {"op":"create_part","shape":"cube","name":"leg","size":[1,3,1],"position":[3,1.5,2]},
                          {"op":"mirror_part","part":"leg","axis":"x","name":"leg_R"}
                        ]}"""),
                RunOptions.defaults());
        assertTrue(js.ok(), () -> "json failed: " + js.error());

        // One command layer: identical totals and bounding boxes.
        assertEquals(js.summary().totals(), py.summary().totals());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                js.summary().bbox()[0], py.summary().bbox()[0], 1e-5f);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                js.summary().bbox()[1], py.summary().bbox()[1], 1e-5f);
    }
}
