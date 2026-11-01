package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.graph.GraphBuilder;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior graphs (#291) compiled to Lua and run on the #292 runtime: events, state, branches,
 * waits, animation and actions; Lua functions and custom events across the two; isolation,
 * cancellation, converters, error attribution and the debugger.
 */
class GraphRuntimeTest {

    static final HostContract NET = HostContract.of("test:net", 1);

    /** A host with one held action the test completes. */
    static final class Host {
        final UiHost host = new UiHost();
        final List<CompletableFuture<UiValue>> held = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        Host() {
            host.data().register("session", new DataCell(DataType.object("online", DataType.bool()),
                obj("online", false)), HostContract.of("test:session", 1));
            host.actions().register(ActionSpec.of("test:net.ping", NET, null, DataType.ANY), (args, ctx) -> {
                calls.add("ping" + args.fields());
                CompletableFuture<UiValue> f = new CompletableFuture<>();
                held.add(f);
                return f;
            });
            host.actions().register(ActionSpec.of("test:net.resume", NET, null, DataType.ANY), (args, ctx) -> {
                calls.add("resume");
                return CompletableFuture.completedFuture(UiValue.NULL);
            });
        }
    }

    static UiValue.Obj obj(Object... kv) {
        Map<String, UiValue> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], UiDocs.value(kv[i + 1]));
        }
        return new UiValue.Obj(m);
    }

    static UiDocs.N root(UiDocs.N... kids) {
        return box("root").style("width", 400).style("height", 300).kids(kids);
    }

    static UiDocs.N button(String id) {
        return node(id, "Button").name(id).style("width", 100).style("height", 30);
    }

    static OmuiArchive declared(OmuiArchive doc) {
        return UiDocs.declare(doc, List.of(), "test:session@1", "test:net@1");
    }

    static ScriptRig rig(OmuiArchive doc, Host h) {
        return new ScriptRig(doc, UiDocumentSource.EMPTY, h == null ? null : h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE);
    }

    // ── a graph drives a screen ─────────────────────────────────────────────

    /** Click counts, a formatted label, a branch, an awaited fade and an action: all from the graph. */
    static OmuiArchive counterScreen() {
        OmuiArchive doc = declared(screen("t:ui/graph", root(button("resume"), label("status", "idle").name("status"),
            box("panel").name("panel").style("width", 50).style("height", 20))));
        return doc.withGraph(new GraphBuilder("behaviors")
            .var("clicks", ValueType.INT, 0)
            .node("on_resume", "ui:event.click", "target", "resume")
            .node("count", "ui:variable.increment", "variable", "clicks")
            .node("get", "ui:variable.get", "variable", "clicks")
            .node("fmt", "ui:format", "template", "clicked {n}")
            .node("show", "ui:element.set-text", "target", "status")
            .node("enough", "ui:compare", "op", "ge", "=b", 2)
            .node("branch", "ui:flow.branch")
            .node("fade", "ui:anim.tween", "target", "panel", "property", "opacity", "=value", 0, "=duration", 0.5)
            .node("go", "ui:action.request", "action", "test:net.resume")
            .link("on_resume.then", "count.exec").link("count.then", "show.exec")
            .link("get.value", "fmt.n").link("fmt.text", "show.text")
            .link("show.then", "branch.exec").link("get.value", "enough.a").link("enough.result", "branch.condition")
            .link("branch.true", "fade.exec").link("fade.then", "go.exec")
            .build());
    }

    @Test
    void aGraphDrivesEventsStateBranchesAnimationAndActions() {
        Host h = new Host();
        try (ScriptRig rig = rig(counterScreen(), h)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            assertEquals(List.of("behaviors.graph.lua"), rig.rt.modules());
            rig.click("resume");
            assertEquals("clicked 1", rig.text("status"));
            assertTrue(h.calls.isEmpty());
            rig.click("resume");
            assertEquals("clicked 2", rig.text("status"));
            rig.frame(0.25);
            double half = rig.el("panel").computedStyle().number("opacity", 1);
            assertTrue(half > 0.3 && half < 0.7, "half-way through the fade: " + half);
            assertTrue(h.calls.isEmpty(), "the request waits for the tween");
            rig.frame(0.3);
            rig.frame(0.016);
            assertEquals(List.of("resume"), h.calls, "the awaited tween completed, then the action ran");
        }
    }

    // ── Lua and graphs together ─────────────────────────────────────────────

    static final String CODE = """
        local M = {}

        --- Formats a score for the label.
        ---@param points integer
        ---@return string text
        function M.describe(points)
          return "score " .. points
        end

        function M.on_open(ui)
          ui.on("from_graph", function(args) ui.q("#lua"):setText("lua got " .. args.n) end)
          ui.q("#poke"):on("click", function() ui.raise("from_lua", { score = 7 }) end)
        end
        return M
        """;

    static OmuiArchive mixedScreen() {
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/mixed", root(button("poke"),
            label("graph", "-").name("graph"), label("lua", "-").name("lua"))), CODE);
        return doc.withGraph(new GraphBuilder("bridge")
            .node("on_score", "ui:event.custom", "name", "from_lua", "params", GraphBuilder.params("score", "int"))
            .node("describe", "lua:call", "function", "describe")
            .node("show", "ui:element.set-text", "target", "graph")
            .node("answer", "ui:event.raise", "name", "from_graph", "params", GraphBuilder.params("n", "int"))
            .link("on_score.then", "describe.exec").link("on_score.score", "describe.points")
            .link("describe.then", "show.exec").link("describe.text", "show.text")
            .link("show.then", "answer.exec").link("on_score.score", "answer.n")
            .build());
    }

    @Test
    void aGraphCallsALuaFunctionAndHandlesAnEventRaisedFromLua() {
        try (ScriptRig rig = rig(mixedScreen(), null)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            assertEquals(List.of("main.lua", "bridge.graph.lua"), rig.rt.modules());
            rig.click("poke");
            assertEquals("score 7", rig.text("graph"), "Lua raised, the graph handled it and called M.describe");
            assertEquals("lua got 7", rig.text("lua"), "the graph raised back and Lua handled it");
        }
    }

    // ── isolation, lifetime ─────────────────────────────────────────────────

    static OmuiArchive counterComponent() {
        UiDocument.ComponentDef def = new UiDocument.ComponentDef(List.of(), List.of(), List.of(), Map.of());
        OmuiArchive comp = component("t:ui/counter", box("croot").style("width", 100).style("height", 40).kids(
            button("plus"), label("value", "0").name("value")), def);
        return comp.withGraph(new GraphBuilder("count")
            .var("n", ValueType.INT, 0)
            .node("click", "ui:event.click", "target", "plus")
            .node("inc", "ui:variable.increment", "variable", "n")
            .node("get", "ui:variable.get", "variable", "n")
            .node("show", "ui:element.set-text", "target", "value")
            .link("click.then", "inc.exec").link("inc.then", "show.exec").link("get.value", "show.text")
            .build());
    }

    @Test
    void graphInstancesShareNoState() {
        OmuiArchive comp = counterComponent();
        UiDocumentSource src = UiDocumentSource.of(Map.of("t:ui/counter", comp), Map.of());
        OmuiArchive doc = screen("t:ui/two", root(inst("a", "t:ui/counter", Map.of()),
            inst("b", "t:ui/counter", Map.of())));
        try (ScriptRig rig = new ScriptRig(doc, src, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            assertEquals(List.of("count.graph.lua@a", "count.graph.lua@b"), rig.rt.modules());
            rig.click("a/plus");
            rig.click("a/plus");
            rig.click("b/plus");
            assertEquals("2", rig.text("a/value"));
            assertEquals("1", rig.text("b/value"), "each instance runs the graph in its own environment");
        }
    }

    static OmuiArchive delayedScreen() {
        return screen("t:ui/delay", root(button("go"), label("out", "-").name("out"))).withGraph(
            new GraphBuilder("delay")
                .node("click", "ui:event.click", "target", "go")
                .node("wait", "ui:flow.wait", "=seconds", 1)
                .node("write", "ui:element.set-text", "target", "out", "=text", "late")
                .link("click.then", "wait.exec").link("wait.then", "write.exec")
                .build());
    }

    @Test
    void aDelayedHandlerNeverTouchesAClosedOrReloadedScreen() {
        try (ScriptRig rig = rig(delayedScreen(), null)) {
            rig.click("go");
            rig.reload(delayedScreen());
            rig.frame(1.5);
            rig.frame(0.016);
            assertEquals("-", rig.text("out"), "the reload cancelled the waiting task");
            rig.click("go");
            rig.frame(1.5);
            rig.frame(0.016);
            assertEquals("late", rig.text("out"), "a fresh click after the reload still works");
            rig.click("go");
            rig.rt.close();
            rig.frame(1.5);
            assertEquals(0, rig.rt.pendingCount());
        }
    }

    // ── converters, errors, debugging ───────────────────────────────────────

    @Test
    void aPureGraphFunctionIsABindingConverter() {
        Host h = new Host();
        OmuiArchive doc = declared(screen("t:ui/conv", root(box("badge").name("badge").style("width", 10)
            .style("height", 10).bind("style:display", "session.online", UiNode.BindingMode.TO_TARGET, "display_if"))))
            .withGraph(new GraphBuilder("convert")
                .function("display_if", f -> f.in("online", "bool").out("display", "string")
                    .node("entry", "ui:function.entry")
                    .node("pick", "ui:select", "=a", "flex", "=b", "none")
                    .node("ret", "ui:function.return")
                    .link("entry.online", "pick.condition").link("pick.value", "ret.display"))
                .build());
        try (ScriptRig rig = rig(doc, h)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            assertEquals("none", rig.el("badge").computedStyle().keyword("display", "?"));
        }
    }

    @Test
    void runtimeErrorsPointAtTheOriginatingNode() {
        OmuiArchive doc = screen("t:ui/err", root(button("go"), label("out", "-").name("out"))).withGraph(
            new GraphBuilder("faulty")
                .node("click", "ui:event.click", "target", "go")
                .node("ok", "ui:element.set-text", "target", "out", "=text", "before")
                .node("bad", "ui:element.set-prop", "target", "out", "property", "nonsense", "=value", 1)
                .link("click.then", "ok.exec").link("ok.then", "bad.exec")
                .build());
        try (ScriptRig rig = rig(doc, null)) {
            rig.click("go");
            UiScriptDiagnostic d = rig.rt.diagnostics().stream()
                .filter(x -> x.code() == UiScriptDiagnostic.Code.RUNTIME).findFirst().orElse(null);
            assertNotNull(d, rig.rt.diagnostics().toString());
            assertEquals("faulty#bad", d.node(), d.toString());
            assertEquals("faulty.graph.lua", d.chunk());
            assertEquals("-", rig.text("out"), "the failed dispatch rolled back the earlier write");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.HANDLER_DISABLED));
        }
    }

    @Test
    void anInvalidGraphIsReportedPerNodeAndNotRun() {
        OmuiArchive doc = screen("t:ui/bad", root(button("go"))).withGraph(new GraphBuilder("broken")
            .node("click", "ui:event.click", "target", "missing")
            .node("loop", "ui:log", "=message", "x")
            .link("click.then", "loop.exec").link("loop.then", "loop.exec")
            .build());
        try (ScriptRig rig = rig(doc, null)) {
            List<UiScriptDiagnostic> graph = rig.rt.diagnostics().stream()
                .filter(d -> d.code() == UiScriptDiagnostic.Code.GRAPH_INVALID).toList();
            assertTrue(graph.stream().anyMatch(d -> d.node().equals("broken#click")
                && d.message().startsWith("MISSING_ELEMENT")), graph.toString());
            assertTrue(graph.stream().anyMatch(d -> d.node().equals("broken#loop")
                && d.message().startsWith("SYNC_CYCLE")), graph.toString());
            assertTrue(rig.rt.modules().isEmpty(), "an invalid graph never runs");
        }
    }

    @Test
    void theDebuggerTracesNodesWatchesValuesAndPausesAtBreakpoints() {
        Host h = new Host();
        try (ScriptRig rig = new ScriptRig(counterScreen(), UiDocumentSource.EMPTY, h.host,
            UiScriptOptions.DEFAULTS.withGraphDebug(true), UiScriptServices.NONE)) {
            GraphDebugger dbg = rig.rt.graphDebugger();
            assertNotNull(dbg);
            dbg.setBreakpoint("behaviors", "show", true);
            rig.click("resume");
            assertEquals(1, dbg.paused().size(), "paused before 'show'");
            assertEquals("idle", rig.text("status"));
            assertEquals(1, dbg.hit("behaviors", "count").count());
            assertNull(dbg.value("behaviors", "get", "value"), "a statement's inputs evaluate after its breakpoint");
            dbg.resume(dbg.paused().getFirst().token());
            rig.frame(0.016);
            assertEquals("clicked 1", rig.text("status"), "resumed at the next frame");
            assertEquals(UiValue.of(1), dbg.value("behaviors", "get", "value"));
            assertEquals(UiValue.of("clicked 1"), dbg.value("behaviors", "fmt", "text"));
            assertNull(dbg.hit("behaviors", "fade"), "the false branch never reached the fade");
            assertFalse(dbg.trace().isEmpty());
        }
    }

    // ── budget stops ────────────────────────────────────────────────────────

    static final String BUSY = """
        local M = {}
        --- Spins for a while.
        ---@param n integer
        ---@return integer total
        function M.spin(n)
          local t = 0
          for i = 1, n do t = t + i % 7 end
          return t
        end
        return M
        """;

    static OmuiArchive busyScreen() {
        return ScriptRig.withCode(screen("t:ui/busy", root(button("go"), label("out", "-").name("out"))), BUSY)
            .withGraph(new GraphBuilder("busy")
                .node("click", "ui:event.click", "target", "go")
                .node("spin", "lua:call", "function", "spin", "=n", 2_000_000_000)
                .node("show", "ui:element.set-text", "target", "out")
                .link("click.then", "spin.exec").link("spin.then", "show.exec").link("spin.total", "show.text")
                .build());
    }

    @Test
    void deadlineStopsPointAtTheCallingNode() {
        try (ScriptRig rig = new ScriptRig(busyScreen(), UiDocumentSource.EMPTY, null,
            UiScriptOptions.DEFAULTS.withDeadline(30), UiScriptServices.NONE)) {
            rig.click("go");
            UiScriptDiagnostic d = rig.rt.diagnostics().stream()
                .filter(x -> x.code() == UiScriptDiagnostic.Code.DEADLINE).findFirst().orElse(null);
            assertNotNull(d, rig.rt.diagnostics().toString());
            assertEquals("busy#spin", d.node(), "the Lua frame is main.lua; the graph frame below names the node");
            assertEquals("-", rig.text("out"));
        }
    }

    @Test
    void instructionBudgetStopsPointAtTheCallingNode() {
        try (ScriptRig rig = new ScriptRig(busyScreen(), UiDocumentSource.EMPTY, null,
            UiScriptOptions.DEFAULTS.withInstructionBudget(100_000), UiScriptServices.NONE)) {
            rig.click("go");
            UiScriptDiagnostic d = rig.rt.diagnostics().stream()
                .filter(x -> x.code() == UiScriptDiagnostic.Code.BUDGET).findFirst().orElse(null);
            assertNotNull(d, rig.rt.diagnostics().toString());
            assertEquals("busy#spin", d.node());
        }
    }
}
