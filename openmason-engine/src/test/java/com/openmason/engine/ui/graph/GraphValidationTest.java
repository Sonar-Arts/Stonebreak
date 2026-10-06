package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.graph.GraphDiagnostic.Code;
import com.openmason.engine.ui.graph.GraphDiagnostic.Severity;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One focused check per graph validation rule (#291): code, location and severity of each diagnostic. */
class GraphValidationTest {

    static final OmuiArchive DOC = screen("t:ui/v", box("root").kids(node("go", "Button").name("go"),
        node("stop", "Button"), label("status", "-"), box("panel")))
        .withAnimation(new UiAnimationClip("intro", 1.0, UiAnimationClip.LoopMode.ONCE, List.of(), List.of(), Map.of()));

    static final String CODE = """
        local M = {}

        ---@param n integer
        ---@return string text
        function M.describe(n)
          return "n=" .. n
        end

        ---@async
        ---@param n number
        ---@return number
        function M.slow(n)
          return n
        end
        return M
        """;

    static GraphEnvironment env(OmuiArchive doc) {
        return DocumentEnvironment.of(doc, UiDocumentSource.EMPTY);
    }

    static List<GraphDiagnostic> check(UiGraph g) {
        return check(g, DOC);
    }

    static List<GraphDiagnostic> check(UiGraph g, OmuiArchive doc) {
        return GraphCompiler.validate(g, env(doc));
    }

    static OmuiArchive withCode(OmuiArchive doc, String source) {
        UiDocument d = doc.document();
        return doc.withDocument(new UiDocument(d.root(), d.styleSheets(), "main", d.component(), d.unknown()))
            .withScript("main", source);
    }

    /** The one diagnostic with {@code code}; fails when there are none or several. */
    static GraphDiagnostic one(List<GraphDiagnostic> all, Code code) {
        List<GraphDiagnostic> d = all.stream().filter(x -> x.code() == code).toList();
        assertEquals(1, d.size(), "exactly one " + code + " in " + all);
        return d.getFirst();
    }

    /** An error at {@code function}/{@code node}.{@code port} with {@code code}, and nothing else is an error. */
    static void onlyError(List<GraphDiagnostic> all, Code code, String function, String node, String port) {
        GraphDiagnostic d = one(all, code);
        at(d, function, node, port);
        assertEquals(Severity.ERROR, d.severity(), d.toString());
        assertEquals(List.of(d), all.stream().filter(GraphDiagnostic::isError).toList(), "no other errors");
    }

    static void at(GraphDiagnostic d, String function, String node, String port) {
        assertEquals(function, d.function(), d.toString());
        assertEquals(node, d.node(), d.toString());
        assertEquals(port, d.port(), d.toString());
    }

    static boolean has(List<GraphDiagnostic> all, Code code, String function, String node, String port) {
        return all.stream().anyMatch(d -> d.code() == code && d.function().equals(function) && d.node().equals(node)
            && d.port().equals(port));
    }

    static List<GraphDiagnostic> errors(List<GraphDiagnostic> all) {
        return all.stream().filter(GraphDiagnostic::isError).toList();
    }

    /** A click on "go" that runs {@code log}. */
    static GraphBuilder clickLog() {
        return new GraphBuilder("g")
            .node("click", "ui:event.click", "target", "go")
            .node("log", "ui:log", "=message", "hi")
            .link("click.then", "log.exec");
    }

    @Test
    void aValidGraphHasNoDiagnostics() {
        assertEquals(List.of(), check(clickLog().build()));
        assertTrue(GraphCompiler.compile(clickLog().build(), env(DOC), false).ok());
    }

    // ── kinds and versions ──────────────────────────────────────────────────

    @Test
    void unknownKind() {
        List<GraphDiagnostic> d = check(clickLog().node("x", "ui:does.not-exist").build());
        onlyError(d, Code.UNKNOWN_KIND, "", "x", "");
    }

    @Test
    void newerKindVersion() {
        UiGraph g = clickLog().build();
        UiGraph.GraphNode future = new UiGraph.GraphNode("future", "ui:log", 2, 0, 0, Map.of(), Map.of(), Map.of());
        List<UiGraph.GraphNode> nodes = new java.util.ArrayList<>(g.nodes());
        nodes.add(future);
        List<UiGraph.GraphEdge> edges = new java.util.ArrayList<>(g.edges());
        edges.add(new UiGraph.GraphEdge("log", "then", "future", "exec", Map.of()));
        UiGraph newer = new UiGraph(g.id(), g.variables(), nodes, edges, g.functions(), g.unknown());
        List<GraphDiagnostic> d = check(newer);
        onlyError(d, Code.NEWER_KIND_VERSION, "", "future", "");
        assertTrue(one(d, Code.NEWER_KIND_VERSION).message().contains("version 2"), d.toString());
    }

    // ── properties ──────────────────────────────────────────────────────────

    @Test
    void invalidPropBadEnumValue() {
        List<GraphDiagnostic> d = check(clickLog().node("cmp", "ui:compare", "op", "approx").build());
        onlyError(d, Code.INVALID_PROP, "", "cmp", "");
    }

    @Test
    void invalidPropBadIdentifier() {
        List<GraphDiagnostic> d = check(clickLog().node("evt", "ui:event.custom", "name", "two words").build());
        onlyError(d, Code.INVALID_PROP, "", "evt", "");
    }

    @Test
    void invalidPropSequenceCountOutOfRange() {
        for (int count : new int[]{1, FlowKinds.MAX_SEQUENCE + 1}) {
            List<GraphDiagnostic> d = check(clickLog().node("seq", "ui:flow.sequence", "count", count)
                .link("log.then", "seq.exec").build());
            onlyError(d, Code.INVALID_PROP, "", "seq", "");
        }
        assertEquals(List.of(), check(clickLog().node("seq", "ui:flow.sequence", "count", FlowKinds.MAX_SEQUENCE)
            .link("log.then", "seq.exec").build()));
    }

    @Test
    void duplicateVariableName() {
        List<GraphDiagnostic> d = check(clickLog().var("n", ValueType.INT, 0).var("n", ValueType.NUMBER, 0).build());
        onlyError(d, Code.DUPLICATE_NAME, "", "", "");
    }

    // ── inputs ──────────────────────────────────────────────────────────────

    @Test
    void requiredInput() {
        List<GraphDiagnostic> d = check(clickLog().node("each", "ui:flow.for-each").link("log.then", "each.exec").build());
        onlyError(d, Code.REQUIRED_INPUT, "", "each", "list");
    }

    @Test
    void invalidLiteral() {
        List<GraphDiagnostic> d = check(clickLog().node("wait", "ui:flow.wait", "=seconds", "soon")
            .link("log.then", "wait.exec").build());
        onlyError(d, Code.INVALID_LITERAL, "", "wait", "seconds");

        List<GraphDiagnostic> fraction = check(clickLog().node("sum", "ui:math", "type", "int", "=a", 1.5)
            .node("show", "ui:element.set-text", "target", "status")
            .link("log.then", "show.exec").link("sum.result", "show.text").build());
        onlyError(fraction, Code.INVALID_LITERAL, "", "sum", "a");
    }

    // ── links ───────────────────────────────────────────────────────────────

    @Test
    void brokenLinkToAPortTheNodeLacks() {
        List<GraphDiagnostic> d = check(clickLog().node("other", "ui:log").link("log.then", "other.nope").build());
        assertTrue(has(d, Code.BROKEN_LINK, "", "other", "nope"), d.toString());
        assertEquals(Severity.ERROR, one(d, Code.BROKEN_LINK).severity());

        List<GraphDiagnostic> from = check(clickLog().node("other", "ui:log").link("log.gone", "other.exec").build());
        assertTrue(has(from, Code.BROKEN_LINK, "", "log", "gone"), from.toString());
    }

    @Test
    void aLiteralForAPortTheNodeLacksIsOnlyAWarning() {
        List<GraphDiagnostic> d = check(clickLog().node("other", "ui:screen.close", "=volume", 3)
            .link("log.then", "other.exec").build());
        GraphDiagnostic w = one(d, Code.BROKEN_LINK);
        at(w, "", "other", "volume");
        assertEquals(Severity.WARNING, w.severity());
    }

    @Test
    void wrongDirection() {
        List<GraphDiagnostic> fromInput = check(clickLog()
            .node("show", "ui:element.set-text", "target", "status", "=text", "x")
            .node("other", "ui:log").link("log.then", "show.exec").link("show.then", "other.exec")
            .link("show.text", "other.message").build());
        onlyError(fromInput, Code.WRONG_DIRECTION, "", "show", "text");

        List<GraphDiagnostic> intoOutput = check(clickLog()
            .node("get", "ui:compare").node("fmt", "ui:format", "template", "x")
            .link("get.result", "fmt.text").build());
        onlyError(intoOutput, Code.WRONG_DIRECTION, "", "fmt", "text");
    }

    @Test
    void typeMismatchStringIntoBool() {
        List<GraphDiagnostic> d = check(clickLog()
            .node("fmt", "ui:format", "template", "hello").node("br", "ui:flow.branch")
            .link("log.then", "br.exec").link("fmt.text", "br.condition").build());
        onlyError(d, Code.TYPE_MISMATCH, "", "br", "condition");
    }

    @Test
    void typeMismatchExecIntoData() {
        List<GraphDiagnostic> d = check(new GraphBuilder("g")
            .node("click", "ui:event.click", "target", "go")
            .node("show", "ui:element.set-text", "target", "status")
            .node("log", "ui:log")
            .link("click.then", "log.exec").link("log.then", "show.text").build());
        assertTrue(has(d, Code.TYPE_MISMATCH, "", "show", "text"), d.toString());
    }

    @Test
    void typeMismatchNumberIntoInt() {
        List<GraphDiagnostic> d = check(clickLog().var("count", ValueType.INT, 0)
            .node("half", "ui:math", "op", "div", "=a", 1, "=b", 2)
            .node("set", "ui:variable.set", "variable", "count")
            .link("log.then", "set.exec").link("half.result", "set.value").build());
        onlyError(d, Code.TYPE_MISMATCH, "", "set", "value");
    }

    @Test
    void duplicateLinkIntoOneDataInput() {
        List<GraphDiagnostic> d = check(clickLog()
            .node("a", "ui:format", "template", "a").node("b", "ui:format", "template", "b")
            .node("show", "ui:element.set-text", "target", "status")
            .link("log.then", "show.exec").link("a.text", "show.text").link("b.text", "show.text").build());
        onlyError(d, Code.DUPLICATE_LINK, "", "show", "text");
    }

    @Test
    void duplicateLinkFromOneExecOutput() {
        List<GraphDiagnostic> d = check(clickLog().node("other", "ui:log")
            .link("click.then", "other.exec").build());
        onlyError(d, Code.DUPLICATE_LINK, "", "click", "then");
    }

    @Test
    void twoLinksIntoOneExecInputAreAJoin() {
        assertEquals(List.of(), check(new GraphBuilder("g")
            .node("a", "ui:event.click", "target", "go").node("b", "ui:event.click", "target", "stop")
            .node("seq", "ui:flow.sequence").node("done", "ui:log")
            .link("a.then", "seq.exec").link("seq.then0", "done.exec").link("seq.then1", "done.exec")
            .link("b.then", "done.exec").build()));
    }

    // ── references ──────────────────────────────────────────────────────────

    @Test
    void missingElement() {
        List<GraphDiagnostic> d = check(clickLog().node("show", "ui:element.set-text", "target", "nowhere")
            .link("log.then", "show.exec").build());
        onlyError(d, Code.MISSING_ELEMENT, "", "show", "");

        List<GraphDiagnostic> none = check(clickLog().node("show", "ui:element.set-text")
            .link("log.then", "show.exec").build());
        onlyError(none, Code.MISSING_ELEMENT, "", "show", "");
    }

    @Test
    void elementPathsJoinThroughComponentInstances() {
        OmuiArchive button = component("t:ui/btn", box("croot").kids(label("label", "x")),
            new UiDocument.ComponentDef(List.of(), List.of(), List.of(), Map.of()));
        OmuiArchive doc = screen("t:ui/inst", box("root").kids(inst("resume", "t:ui/btn", Map.of())));
        UiGraph g = new GraphBuilder("g")
            .node("click", "ui:event.click", "target", "resume")
            .node("show", "ui:element.set-text", "target", "resume/label", "=text", "go")
            .link("click.then", "show.exec").build();

        List<GraphDiagnostic> withoutSource = GraphCompiler.validate(g, DocumentEnvironment.of(doc, UiDocumentSource.EMPTY));
        onlyError(withoutSource, Code.MISSING_ELEMENT, "", "show", "");

        GraphEnvironment env = DocumentEnvironment.of(doc, UiDocumentSource.of(Map.of("t:ui/btn", button), Map.of()));
        assertEquals(List.of(), GraphCompiler.validate(g, env));
        assertTrue(GraphCompiler.compile(g, env, false).lua().contains("ui.get(\"resume/label\"):setText(\"go\")"));
    }

    @Test
    void unknownSignal() {
        UiDocument.ComponentDef def = new UiDocument.ComponentDef(List.of(), List.of(new UiDocument.EventDef("changed",
            List.of(new UiDocument.Param("value", ValueType.INT, null, Map.of())), Map.of())), List.of(), Map.of());
        OmuiArchive comp = component("t:ui/knob", box("kroot"), def);
        OmuiArchive doc = screen("t:ui/sig", box("root").kids(inst("knob", "t:ui/knob", Map.of()), box("plain")));
        GraphEnvironment env = DocumentEnvironment.of(doc, UiDocumentSource.of(Map.of("t:ui/knob", comp), Map.of()));

        UiGraph undeclared = new GraphBuilder("g").node("on", "ui:event.signal", "target", "knob", "signal", "pressed").build();
        onlyError(GraphCompiler.validate(undeclared, env), Code.UNKNOWN_SIGNAL, "", "on", "");

        UiGraph notAnInstance = new GraphBuilder("g").node("on", "ui:event.signal", "target", "plain", "signal", "changed").build();
        onlyError(GraphCompiler.validate(notAnInstance, env), Code.UNKNOWN_SIGNAL, "", "on", "");

        UiGraph declared = new GraphBuilder("g").node("on", "ui:event.signal", "target", "knob", "signal", "changed")
            .node("log", "ui:log").link("on.then", "log.exec").link("on.value", "log.message").build();
        assertEquals(List.of(), GraphCompiler.validate(declared, env));

        UiGraph emitFromScreen = clickLog().node("emit", "ui:signal.emit", "signal", "changed")
            .link("log.then", "emit.exec").build();
        onlyError(check(emitFromScreen), Code.UNKNOWN_SIGNAL, "", "emit", "");
    }

    @Test
    void unknownVariable() {
        List<GraphDiagnostic> d = check(clickLog().node("get", "ui:variable.get", "variable", "missing").build());
        onlyError(d, Code.UNKNOWN_VARIABLE, "", "get", "");
    }

    @Test
    void unknownFunction() {
        List<GraphDiagnostic> d = check(clickLog().node("call", "ui:function.call", "function", "missing")
            .link("log.then", "call.exec").build());
        assertTrue(has(d, Code.UNKNOWN_FUNCTION, "", "call", ""), d.toString());
        assertEquals(Severity.ERROR, one(d, Code.UNKNOWN_FUNCTION).severity());
    }

    @Test
    void unknownLuaFunction() {
        List<GraphDiagnostic> d = check(clickLog().node("call", "lua:call", "function", "missing").build());
        onlyError(d, Code.UNKNOWN_LUA_FUNCTION, "", "call", "");

        List<GraphDiagnostic> known = check(clickLog().node("call", "lua:call", "function", "describe", "=n", 3)
            .link("log.then", "call.exec").build(), withCode(DOC, CODE));
        assertEquals(List.of(), known);
    }

    @Test
    void unknownClip() {
        List<GraphDiagnostic> d = check(clickLog().node("play", "ui:anim.play", "clip", "outro")
            .link("log.then", "play.exec").build());
        onlyError(d, Code.UNKNOWN_CLIP, "", "play", "");
        assertEquals(List.of(), check(clickLog().node("play", "ui:anim.play", "clip", "intro")
            .link("log.then", "play.exec").build()));
    }

    // ── cycles ──────────────────────────────────────────────────────────────

    @Test
    void syncCyclePureDataCycle() {
        List<GraphDiagnostic> d = check(clickLog()
            .node("a", "ui:math").node("b", "ui:math")
            .node("show", "ui:element.set-text", "target", "status")
            .link("log.then", "show.exec").link("a.result", "b.a").link("b.result", "a.a").link("a.result", "show.text")
            .build());
        GraphDiagnostic c = one(d, Code.SYNC_CYCLE);
        assertEquals("", c.function());
        assertTrue(List.of("a", "b").contains(c.node()), c.toString());
    }

    @Test
    void syncCycleExecLoopWithoutAWait() {
        List<GraphDiagnostic> d = check(clickLog().node("again", "ui:log")
            .link("log.then", "again.exec").link("again.then", "log.exec").build());
        onlyError(d, Code.SYNC_CYCLE, "", "log", "");
    }

    @Test
    void anExecLoopWithAWaitIsAccepted() {
        UiGraph g = new GraphBuilder("g")
            .node("opened", "ui:event.open")
            .node("tick", "ui:log", "=message", "tick")
            .node("wait", "ui:flow.wait", "=seconds", 1)
            .link("opened.then", "tick.exec").link("tick.then", "wait.exec").link("wait.then", "tick.exec")
            .build();
        assertEquals(List.of(), check(g));
        assertTrue(GraphCompiler.compile(g, env(DOC), false).ok());
    }

    @Test
    void syncCycleRecursiveFunction() {
        List<GraphDiagnostic> d = check(clickLog()
            .function("again", f -> f.in("exec", "exec").out("then", "exec")
                .node("entry", "ui:function.entry").node("self", "ui:function.call", "function", "again")
                .link("entry.then", "self.exec"))
            .build());
        onlyError(d, Code.SYNC_CYCLE, "again", "", "");
    }

    // ── waits ───────────────────────────────────────────────────────────────

    @Test
    void latentInSyncWaitAfterUpdate() {
        List<GraphDiagnostic> d = check(new GraphBuilder("g")
            .node("frame", "ui:event.update").node("wait", "ui:flow.wait")
            .link("frame.then", "wait.exec").build());
        onlyError(d, Code.LATENT_IN_SYNC, "", "wait", "");

        List<GraphDiagnostic> close = check(new GraphBuilder("g")
            .node("bye", "ui:event.close")
            .node("fade", "ui:anim.tween", "target", "panel", "property", "opacity", "=value", 0)
            .link("bye.then", "fade.exec").build());
        onlyError(close, Code.LATENT_IN_SYNC, "", "fade", "");

        List<GraphDiagnostic> noWait = check(new GraphBuilder("g")
            .node("bye", "ui:event.close")
            .node("fade", "ui:anim.tween", "target", "panel", "property", "opacity", "=value", 0, "wait", false)
            .link("bye.then", "fade.exec").build());
        assertEquals(List.of(), noWait, "a tween that does not wait may run on close");
    }

    @Test
    void latentInSyncALatentFunctionCalledFromUpdate() {
        GraphBuilder g = new GraphBuilder("g")
            .node("frame", "ui:event.update").node("call", "ui:function.call", "function", "pause")
            .link("frame.then", "call.exec")
            .function("pause", f -> f.in("exec", "exec").out("then", "exec")
                .node("entry", "ui:function.entry").node("wait", "ui:flow.wait")
                .node("ret", "ui:function.return")
                .link("entry.then", "wait.exec").link("wait.then", "ret.exec"));
        onlyError(check(g.build()), Code.LATENT_IN_SYNC, "", "call", "");
    }

    @Test
    void latentInSyncAPureFunctionThatWaits() {
        UiGraph g = clickLog()
            .function("slowly", f -> f.in("x", "number").out("y", "number")
                .node("entry", "ui:function.entry")
                .node("slow", "lua:call", "function", "slow", "pure", true)
                .node("ret", "ui:function.return")
                .link("entry.x", "slow.n").link("slow.result", "ret.y"))
            .build();
        List<GraphDiagnostic> d = check(g, withCode(DOC, CODE));
        assertTrue(has(d, Code.LATENT_IN_SYNC, "slowly", "slow", ""), "the async call cannot be pure: " + d);
        assertTrue(has(d, Code.LATENT_IN_SYNC, "slowly", "", ""), "the pure function may not wait: " + d);
        assertTrue(errors(d).stream().allMatch(x -> x.code() == Code.LATENT_IN_SYNC), d.toString());
    }

    @Test
    void latentInSyncAWaitInAPureFunctionBody() {
        UiGraph g = clickLog()
            .function("f", f -> f.in("x", "number").out("y", "number")
                .node("entry", "ui:function.entry").node("wait", "ui:flow.wait").node("ret", "ui:function.return")
                .link("entry.x", "ret.y"))
            .build();
        List<GraphDiagnostic> d = check(g);
        assertTrue(has(d, Code.LATENT_IN_SYNC, "f", "", ""), d.toString());
        assertTrue(has(d, Code.FUNCTION_SHAPE, "f", "wait", ""), "a pure function runs no statements: " + d);
    }

    // ── scope and reachability ──────────────────────────────────────────────

    @Test
    void outOfScopeReadingAnActionResultFromAnotherEvent() {
        List<GraphDiagnostic> d = check(new GraphBuilder("g")
            .node("ping", "ui:event.click", "target", "go")
            .node("call", "ui:action.invoke", "action", "test:net.ping")
            .node("later", "ui:event.click", "target", "stop")
            .node("show", "ui:element.set-text", "target", "status")
            .link("ping.then", "call.exec").link("later.then", "show.exec").link("call.error", "show.text")
            .build());
        onlyError(d, Code.OUT_OF_SCOPE, "", "show", "text");
    }

    static UiGraph readsAnotherEventThroughAPureNode() {
        return new GraphBuilder("g")
            .node("ping", "ui:event.click", "target", "go")
            .node("other", "ui:event.click", "target", "stop")
            .node("fmt", "ui:format", "template", "at {x}")
            .node("show", "ui:element.set-text", "target", "status")
            .link("other.then", "show.exec").link("ping.x", "fmt.x").link("fmt.text", "show.text")
            .build();
    }

    @Test
    void outOfScopeThroughAPureNode() {
        List<GraphDiagnostic> d = check(readsAnotherEventThroughAPureNode());
        GraphDiagnostic e = one(d, Code.OUT_OF_SCOPE);
        assertEquals("show", e.node(), "reported at the statement that reads: " + e);
        assertEquals(List.of(e), errors(d));
    }

    @Test
    void outOfScopeThroughAPureNodeNamesAPortOfTheReportedNode() {
        GraphDiagnostic e = one(check(readsAnotherEventThroughAPureNode()), Code.OUT_OF_SCOPE);
        assertEquals("text", e.port(), e.toString());
    }

    @Test
    void outOfScopeReadingAValueBeforeItsStatementRuns() {
        List<GraphDiagnostic> d = check(new GraphBuilder("g")
            .node("ping", "ui:event.click", "target", "go")
            .node("show", "ui:element.set-text", "target", "status")
            .node("call", "ui:action.invoke", "action", "test:net.ping")
            .link("ping.then", "show.exec").link("show.then", "call.exec").link("call.error", "show.text")
            .build());
        onlyError(d, Code.OUT_OF_SCOPE, "", "show", "text");
    }

    @Test
    void outOfScopeReadingAValueFromTheOtherBranchArm() {
        List<GraphDiagnostic> d = check(new GraphBuilder("g")
            .node("ping", "ui:event.click", "target", "go")
            .node("br", "ui:flow.branch")
            .node("call", "ui:action.invoke", "action", "test:net.ping")
            .node("show", "ui:element.set-text", "target", "status")
            .link("ping.then", "br.exec").link("br.true", "call.exec").link("br.false", "show.exec")
            .link("call.error", "show.text")
            .build());
        onlyError(d, Code.OUT_OF_SCOPE, "", "show", "text");
    }

    @Test
    void readingAValueAfterItsStatementOnEveryPathIsFine() {
        List<GraphDiagnostic> d = check(new GraphBuilder("g")
            .node("ping", "ui:event.click", "target", "go")
            .node("call", "ui:action.invoke", "action", "test:net.ping")
            .node("br", "ui:flow.branch")
            .node("a", "ui:element.set-text", "target", "status")
            .node("b", "ui:log")
            .node("show", "ui:element.set-text", "target", "status")
            .link("ping.then", "call.exec").link("call.then", "br.exec").link("br.true", "a.exec")
            .link("br.false", "b.exec").link("a.then", "show.exec").link("b.then", "show.exec")
            .link("call.error", "show.text")
            .build());
        assertEquals(List.of(), errors(d), "call runs before the join on both arms");
    }

    @Test
    void aMultiExitReturnMustNameItsOutput() {
        List<GraphDiagnostic> d = check(new GraphBuilder("g")
            .function("f", f -> f.in("exec", "exec").out("yes", "exec").out("no", "exec")
                .node("entry", "ui:function.entry").node("ret", "ui:function.return")
                .link("entry.then", "ret.exec"))
            .build());
        onlyError(d, Code.INVALID_PROP, "f", "ret", "");
    }

    @Test
    void unreachableIsAWarningAndTheGraphStillCompiles() {
        UiGraph g = clickLog().node("orphan", "ui:log", "=message", "orphaned words").build();
        List<GraphDiagnostic> d = check(g);
        GraphDiagnostic w = one(d, Code.UNREACHABLE);
        at(w, "", "orphan", "");
        assertEquals(Severity.WARNING, w.severity());
        CompiledGraph c = GraphCompiler.compile(g, env(DOC), false);
        assertTrue(c.ok(), c.diagnostics().toString());
        assertFalse(c.lua().contains("orphaned words"), "an unreachable node is not compiled");
    }

    // ── function shapes ─────────────────────────────────────────────────────

    @Test
    void functionShapeNoEntry() {
        List<GraphDiagnostic> d = check(clickLog()
            .function("f", f -> f.in("x", "number").out("y", "number").node("ret", "ui:function.return", "=y", 1))
            .build());
        onlyError(d, Code.FUNCTION_SHAPE, "f", "", "");
    }

    @Test
    void functionShapeTwoReturnsInAPureFunction() {
        List<GraphDiagnostic> d = check(clickLog()
            .function("f", f -> f.in("x", "number").out("y", "number")
                .node("entry", "ui:function.entry")
                .node("r1", "ui:function.return").node("r2", "ui:function.return")
                .link("entry.x", "r1.y").link("entry.x", "r2.y"))
            .build());
        onlyError(d, Code.FUNCTION_SHAPE, "f", "", "");
    }

    @Test
    void functionShapeEntryInTheEventGraph() {
        List<GraphDiagnostic> d = check(clickLog().node("entry", "ui:function.entry").build());
        onlyError(d, Code.FUNCTION_SHAPE, "", "entry", "");
    }

    @Test
    void anExecFunctionMayHaveSeveralReturns() {
        UiGraph g = clickLog()
            .node("call", "ui:function.call", "function", "pick", "=n", 3).link("log.then", "call.exec")
            .function("pick", f -> f.in("exec", "exec").in("n", "int").out("high", "exec").out("low", "exec")
                .node("entry", "ui:function.entry").node("cmp", "ui:compare", "op", "gt", "=b", 2)
                .node("br", "ui:flow.branch")
                .node("hi", "ui:function.return", "output", "high").node("lo", "ui:function.return", "output", "low")
                .link("entry.then", "br.exec").link("entry.n", "cmp.a").link("cmp.result", "br.condition")
                .link("br.true", "hi.exec").link("br.false", "lo.exec"))
            .build();
        assertEquals(List.of(), check(g));

        UiGraph badExit = clickLog()
            .function("pick", f -> f.in("exec", "exec").out("high", "exec").out("low", "exec")
                .node("entry", "ui:function.entry").node("ret", "ui:function.return", "output", "middle")
                .link("entry.then", "ret.exec"))
            .build();
        onlyError(check(badExit), Code.INVALID_PROP, "pick", "ret", "");
    }

    // ── implicit conversions ────────────────────────────────────────────────

    @Test
    void anIntFeedsANumberInputDirectly() {
        UiGraph g = clickLog().var("count", ValueType.INT, 2).var("ratio", ValueType.NUMBER, 0)
            .node("get", "ui:variable.get", "variable", "count")
            .node("half", "ui:math", "op", "div", "=b", 2)
            .node("set", "ui:variable.set", "variable", "ratio")
            .link("log.then", "set.exec").link("get.value", "half.a").link("half.result", "set.value")
            .build();
        assertEquals(List.of(), check(g));
        String lua = GraphCompiler.compile(g, env(DOC), false).lua();
        assertTrue(lua.contains("local t_half_result = t_get_value / 2"), lua);
        assertFalse(lua.contains("text("), "no conversion between int and number: " + lua);
    }

    @Test
    void anIntReachesATextInputThroughTheTextHelper() {
        UiGraph g = clickLog().var("count", ValueType.INT, 2)
            .node("get", "ui:variable.get", "variable", "count")
            .node("show", "ui:element.set-text", "target", "status")
            .link("log.then", "show.exec").link("get.value", "show.text")
            .build();
        assertEquals(List.of(), check(g));
        String lua = GraphCompiler.compile(g, env(DOC), false).lua();
        assertTrue(lua.contains("local function text(v)"), "the helper is emitted once used: " + lua);
        assertTrue(lua.contains("ui.get(\"status\"):setText(text(t_get_value))"), lua);
    }

    @Test
    void stringsReachTextInputsWithoutTheHelper() {
        UiGraph g = clickLog()
            .node("show", "ui:element.set-text", "target", "status", "=text", "plain")
            .link("log.then", "show.exec").build();
        String lua = GraphCompiler.compile(g, env(DOC), false).lua();
        assertFalse(lua.contains("text("), lua);
    }

    @Test
    void diagnosticsSortErrorsFirst() {
        UiGraph g = clickLog().node("orphan", "ui:log").node("x", "ui:nope").build();
        CompiledGraph c = GraphCompiler.compile(g, env(DOC), false);
        assertFalse(c.ok());
        assertEquals(Severity.ERROR, c.diagnostics().getFirst().severity(), c.diagnostics().toString());
        assertEquals(Severity.WARNING, c.diagnostics().getLast().severity(), c.diagnostics().toString());
        assertEquals("g#x", c.errors().getFirst().location());
    }

    @Test
    void locationsNameFunctionsAndPorts() {
        assertEquals("g#fn:f/n.p", new GraphDiagnostic(Severity.ERROR, Code.INVALID_PROP, "g", "f", "n", "p", "m").location());
        assertEquals("g#fn:f", new GraphDiagnostic(Severity.ERROR, Code.INVALID_PROP, "g", "f", "", "", "m").location());
        assertEquals("g", new GraphDiagnostic(Severity.ERROR, Code.INVALID_PROP, "g", "", "", "", "m").location());
    }
}
