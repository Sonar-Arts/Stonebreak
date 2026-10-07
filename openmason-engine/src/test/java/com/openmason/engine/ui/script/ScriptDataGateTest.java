package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.declared;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.obj;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua {@code ui.read}/{@code ui.watch} only reach data roots whose contracts the document
 * declares in {@code hostApis} (#327), against a game-like host and the editor's fixture host.
 */
class ScriptDataGateTest {

    /** Reads a declared root, then tries an undeclared one three ways. */
    private static final String PROBE = """
        function on_open(ui)
          local v, state = ui.read("session.online")
          ui.log("declared=" .. tostring(v) .. "/" .. state)
          local ok, err = pcall(ui.read, "vitals.health")
          ui.log("read " .. tostring(ok) .. " " .. tostring(err))
          ok, err = pcall(ui.watch, "vitals.health", function() ui.log("leaked") end)
          ui.log("watch " .. tostring(ok) .. " " .. tostring(err))
          ok, err = pcall(ui.read, "vitals")
          ui.log("root " .. tostring(ok))
        end
        """;

    private static OmuiArchive probe() {
        return declared(ScriptRig.withCode(screen("t:ui/gate", root(label("out", "").name("out"))), PROBE));
    }

    private static void assertRefused(List<String> log, String contract) {
        assertTrue(log.contains("declared=false/ready"), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("read false") && l.contains("CAPABILITY_MISSING")
            && l.contains(contract) && l.contains("vitals.health")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("watch false") && l.contains("CAPABILITY_MISSING")),
            log.toString());
        assertTrue(log.contains("root false"), log.toString());
        assertTrue(log.stream().noneMatch(l -> l.contains("leaked")), log.toString());
    }

    @Test
    void aGameHostRefusesUndeclaredRoots() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        DataCell vitals = h.host.data().register("vitals",
            new DataCell(DataType.object("health", DataType.integer()), obj("health", 20)),
            HostContract.of("test:vitals", 1));
        try (ScriptRig rig = new ScriptRig(probe(), UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertRefused(rig.log(), "test:vitals");
            vitals.set(obj("health", 1));
            rig.frame(0.016);
            assertTrue(rig.log().stream().noneMatch(l -> l.contains("leaked")), rig.log().toString());
            assertEquals(0, vitals.subscriberCount());
        }
    }

    @Test
    void anUncaughtUndeclaredReadIsAScriptError() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        h.host.data().register("vitals", new DataCell(DataType.object("health", DataType.integer()), obj("health", 20)),
            HostContract.of("test:vitals", 1));
        OmuiArchive doc = declared(ScriptRig.withCode(screen("t:ui/gate", root(label("out", "").name("out"))), """
            function on_open(ui)
              ui.q("#out"):setText(tostring(ui.read("vitals.health")))
            end
            """));
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertEquals("", rig.text("out"), "the value never reached the screen");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.RUNTIME), rig.codes().toString());
            assertTrue(rig.rt.diagnostics().stream().anyMatch(d -> d.message().contains("CAPABILITY_MISSING")),
                rig.rt.diagnostics().toString());
        }
    }

    @Test
    void theFixtureHostRefusesThemToo() {
        byte[] json = """
            {"session": {"online": false}, "vitals": {"health": 20}}
            """.getBytes(StandardCharsets.UTF_8);
        OmuiArchive doc = probe();
        FixtureHost f = FixtureHost.parse(json, "fixture.json", doc.manifest());
        assertEquals("test:session", f.host().data().root("session").contract().id());
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, f.host(), UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertRefused(rig.log(), "fixture:vitals");
        }
    }

    @Test
    void declaringTheContractOpensTheRoot() {
        byte[] json = """
            {"session": {"online": false}, "vitals": {"health": 20}}
            """.getBytes(StandardCharsets.UTF_8);
        OmuiArchive doc = UiDocs.declare(ScriptRig.withCode(screen("t:ui/gate", root(label("out", "").name("out"))), """
            function on_open(ui)
              ui.q("#out"):setText(tostring(ui.read("vitals.health")))
            end
            """), List.of(), "test:session@1", "test:vitals@1");
        FixtureHost f = FixtureHost.parse(json, "fixture.json", doc.manifest());
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, f.host(), UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertEquals("20", rig.text("out"));
        }
    }
}
