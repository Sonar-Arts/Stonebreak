package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.binding.BindingStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.button;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The script sandbox and its failure modes (#292): no reach beyond the {@code ui} API, memory cap
 * and watchdog deadline (instruction budget opt-in), errors that disable only the failing handler
 * with a source line, roll back its partial writes, and leave the screen and its close path usable.
 */
class ScriptSafetyTest {

    static OmuiArchive twoButtons(String script) {
        return ScriptRig.withCode(screen("t:ui/safety", root(button("bad"), button("quit"),
            label("out", "start").name("out"), label("closed", "").name("closed"))), script);
    }

    static final String QUIT = """
        ui.q("#quit"):on("click", function() ui.q("#closed"):setText("quit pressed") ui.close() end)
        """;

    @Test
    void sandboxOffersOnlyTheCuratedLibrariesAndTheUiApi() {
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/sandbox", root(label("out", "").name("out"))), """
            local absent = {}
            for _, name in ipairs({ "io", "os", "debug", "package", "dofile", "loadfile", "collectgarbage",
                                    "__cenda_ui_factory", "__cenda_ui_dispatch", "__cenda_traceback", "__h" }) do
              if rawget(_ENV, name) ~= nil or _G[name] ~= nil then absent[#absent + 1] = "PRESENT:" .. name end
            end
            local bin = select("#", load("\\27Lua")) == 2 and "binary refused" or "BINARY RAN"
            local dump = string.dump == nil and "no dump" or "DUMP"
            local java = (ui.java or ui.host or ui.gl or ui.file) == nil and "no host objects" or "HOST OBJECT"
            local req = pcall(require, "os") and "REQUIRED OS" or "require os refused"
            function on_open(ui)
              ui.q("#out"):setText(table.concat(absent, ",") .. "|" .. bin .. "|" .. dump .. "|" .. java .. "|" .. req)
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            assertEquals("|binary refused|no dump|no host objects|require os refused", rig.text("out"),
                rig.rt.diagnostics().toString());
        }
    }

    @Test
    void oneInstanceCannotTamperWithAnothersApi() {
        OmuiArchive evil = ScriptRig.withCode(com.openmason.engine.ui.runtime.UiDocs.component("t:ui/evil",
            box("frame").kids(label("x", "")), new com.openmason.engine.format.omui.UiDocument.ComponentDef(
                List.of(), List.of(), List.of(), java.util.Map.of())), "evil", """
            local results = {}
            results[#results + 1] = tostring(getmetatable(ui.root))
            results[#results + 1] = tostring(pcall(setmetatable, ui.root, {}))
            results[#results + 1] = tostring(pcall(function() getmetatable(ui.root).__index.setText = nil end))
            ui.root.setText = nil -- only this element object of this environment
            string.rep = nil      -- only this environment's string table
            ui.log(table.concat(results, " "))
            """);
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/tamper", root(
            com.openmason.engine.ui.runtime.UiDocs.inst("e", "t:ui/evil", java.util.Map.of()),
            com.openmason.engine.ui.runtime.UiDocs.inst("c", "t:ui/counter", java.util.Map.of()))), "-- screen");
        UiDocumentSource source = UiDocumentSource.of(java.util.Map.of("t:ui/evil", evil,
            "t:ui/counter", ScriptBehaviourTest.counterComponent()), java.util.Map.of());
        try (ScriptRig rig = new ScriptRig(doc, source, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            assertTrue(rig.log().contains("ui.Element false false"), rig.log().toString());
            rig.click("c/plus");
            assertEquals("1", rig.text("c/value"), "the other instance's API is untouched");
        }
    }

    @Test
    void binaryCodeBehindIsRefused() {
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/bin", root(label("out", "x"))), "\u001bLua");
        try (ScriptRig rig = new ScriptRig(doc)) {
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.BINARY_SCRIPT), rig.codes().toString());
        }
    }

    @Test
    void anErrorDisablesOnlyItsHandlerRollsBackAndReportsTheLine() {
        OmuiArchive doc = twoButtons("""
            function on_open(ui)
              ui.q("#bad"):on("click", function()
                ui.q("#out"):setText("half-written")
                ui.q("#out"):addClass("broken")
                local t = nil
                return t.field -- line 6
              end)
            """ + QUIT + """
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.click("bad");
            assertEquals("start", rig.text("out"), "the failed handler's writes were rolled back");
            assertFalse(rig.el("out").hasClass("broken"));
            UiScriptDiagnostic d = rig.rt.diagnostics().getFirst();
            assertEquals(UiScriptDiagnostic.Code.RUNTIME, d.code());
            assertEquals("main.lua:6", d.location(), d.toString());
            assertEquals("bad", d.element());
            assertTrue(d.message().contains("stack traceback"), "with a traceback for the console");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.HANDLER_DISABLED));
            assertTrue(UiDocs.has(rig.ui, UiRuntimeDiagnostic.Code.SCRIPT_ERROR), "mirrored on the instance");

            int before = rig.rt.diagnostics().size();
            rig.click("bad");
            assertEquals(before, rig.rt.diagnostics().size(), "a disabled handler never runs again");
            rig.click("quit");
            assertEquals("quit pressed", rig.text("closed"), "the rest of the screen still works");
        }
    }

    @Test
    void theDeadlineStopsARunawayHandlerAndTheScreenStaysUsable() {
        AtomicInteger closes = new AtomicInteger();
        UiScriptServices services = new UiScriptServices() {
            @Override
            public void requestClose() {
                closes.incrementAndGet();
            }
        };
        OmuiArchive doc = twoButtons("""
            function on_open(ui)
              ui.q("#bad"):on("click", function() ui.q("#out"):setText("spinning") while true do end end)
            """ + QUIT + """
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, null, UiScriptOptions.DEFAULTS.withDeadline(30),
            services)) {
            long t0 = System.nanoTime();
            rig.click("bad");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 1000, "stopped after " + ms + " ms");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.DEADLINE), rig.codes().toString());
            assertEquals("start", rig.text("out"));
            rig.click("quit");
            assertEquals(1, closes.get(), "the close path still runs after an interrupted call");
        }
    }

    @Test
    void theMemoryCapStopsAHogAndTheStateRecovers() {
        OmuiArchive doc = twoButtons("""
            function on_open(ui)
              ui.q("#bad"):on("click", function()
                local hog = {}
                for i = 1, 1e8 do hog[i] = string.rep("x", 100) .. i end
              end)
            """ + QUIT + """
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, null,
            UiScriptOptions.DEFAULTS.withMemoryLimit(2L << 20), UiScriptServices.NONE)) {
            rig.click("bad");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.MEMORY), rig.codes().toString());
            assertTrue(rig.rt.memoryPeak() <= 2L << 20, "peak " + rig.rt.memoryPeak());
            rig.click("quit");
            assertEquals("quit pressed", rig.text("closed"));
        }
    }

    @Test
    void theInstructionBudgetIsAnOptInDiagnostic() {
        OmuiArchive doc = twoButtons("""
            function on_open(ui)
              ui.q("#bad"):on("click", function() local x = 0 for i = 1, 1e9 do x = x + i end end)
            """ + QUIT + """
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, null,
            UiScriptOptions.DEFAULTS.withInstructionBudget(200_000), UiScriptServices.NONE)) {
            rig.click("bad");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.BUDGET), rig.codes().toString());
            rig.click("quit");
            assertEquals("quit pressed", rig.text("closed"));
        }
    }

    @Test
    void failingUpdateIsSwitchedOffAndOtherHooksKeepRunning() {
        OmuiArchive doc = twoButtons("""
            local frames = 0
            function update(dt)
              frames = frames + 1
              if frames == 3 then error("frame three") end
            end
            function on_open(ui)
            """ + QUIT + """
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            for (int i = 0; i < 6; i++) {
                rig.frame(0.016);
            }
            assertEquals(1, rig.rt.diagnostics().stream().filter(d -> d.code() == UiScriptDiagnostic.Code.RUNTIME)
                .count(), "update ran until it failed, then was disabled");
            rig.click("quit");
            assertEquals("quit pressed", rig.text("closed"));
        }
    }

    @Test
    void syntaxErrorsAreReportedAndTheScreenStillOpens() {
        OmuiArchive doc = twoButtons("function on_open(ui) ui.q('#out'):setText( end");
        try (ScriptRig rig = new ScriptRig(doc)) {
            UiScriptDiagnostic d = rig.rt.diagnostics().getFirst();
            assertEquals(UiScriptDiagnostic.Code.SYNTAX, d.code());
            assertEquals("main.lua:1", d.location());
            assertEquals("start", rig.text("out"));
        }
    }

    @Test
    void convertersArePureAndAFailingOneIsReportedThroughTheBinding() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        OmuiArchive doc = UiDocs.declare(ScriptRig.withCode(screen("t:ui/pure", root(
            box("a").style("width", 10).style("height", 10)
                .bind("style:display", "session.online", UiNode.BindingMode.TO_TARGET, "sneaky"))), """
            ui.converter("sneaky", { result = "string", to = function(v)
              ui.action("test:net.ping", { n = 1 }) -- refused: converters are pure
              return "flex"
            end })
            """), List.of(), "test:session@1", "test:net@1");
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertTrue(h.calls.isEmpty(), "the action never ran");
            assertEquals(BindingStatus.State.INVALID, rig.view.binder().status("a", "style:display").state());
            assertTrue(rig.rt.diagnostics().getFirst().message().contains("converters are pure"),
                rig.rt.diagnostics().toString());
        }
    }

    @Test
    void localWritesToABoundTargetAreRefusedWithAMessage() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        OmuiArchive doc = UiDocs.declare(ScriptRig.withCode(screen("t:ui/own", root(
            label("out", "x").name("out").bind("prop:text", "session.online", UiNode.BindingMode.TO_TARGET, "str"),
            label("log", "").name("log"))), """
            ui.converter("str", { result = "string", to = function(v) return tostring(v) end })
            function on_open(ui)
              local ok, err = pcall(function() ui.q("#out"):setText("mine") end)
              ui.q("#log"):setText(tostring(ok) .. ": " .. tostring(err))
            end
            """), List.of(), "test:session@1");
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertEquals("false", rig.text("out"));
            assertTrue(rig.text("log").startsWith("false: main.lua:3: prop:text on out is owned by its to-target"),
                rig.text("log"));
        }
    }

    @Test
    void closeRunsEvenWhenOnCloseFails() {
        OmuiArchive doc = twoButtons("function on_close(ui) error('cleanup failed') end");
        ScriptRig rig = new ScriptRig(doc);
        rig.close();
        assertTrue(rig.rt.isClosed());
        assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.RUNTIME));
        assertEquals(0, rig.rt.memoryUsed(), "the state was freed");
    }

    @Test
    void valuesFromScriptsAreTypeCheckedAgainstWidgets() {
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/types", root(label("out", "x").name("out"),
            label("log", "").name("log"))), """
            function on_open(ui)
              local ok, err = pcall(function() ui.q("#out"):set("text", 42) end)
              local ok2, err2 = pcall(function() ui.q("#out"):set("nope", "x") end)
              ui.q("#log"):setText(tostring(err) .. " | " .. tostring(err2))
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            assertEquals("x", rig.text("out"));
            assertTrue(rig.text("log").contains("expects string") && rig.text("log").contains("no property nope"),
                rig.text("log"));
        }
    }

    static UiValue v(Object o) {
        return UiDocs.value(o);
    }
}
