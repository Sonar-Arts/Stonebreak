package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.runtime.UiDocumentSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;

/**
 * Representative behavior graphs (#291) with the environments they compile in, shared by the
 * golden and source-map tests. Not a test class (no {@code Test} affix).
 */
final class GraphSamples {

    /** A graph and what it can see. */
    record Sample(UiGraph graph, GraphEnvironment env) {
        CompiledGraph compile(boolean debug) {
            return GraphCompiler.compile(graph, env, debug);
        }
    }

    private GraphSamples() {
    }

    /** Every sample by golden name, in a fixed order. */
    static Map<String, Sample> all() {
        Map<String, Sample> m = new LinkedHashMap<>();
        m.put("counter", counter());
        m.put("functions", functions());
        m.put("flow", flow());
        m.put("bridge", bridge());
        m.put("component", knobComponent());
        return m;
    }

    static OmuiArchive screenDoc() {
        return screen("t:ui/golden", box("root").kids(node("resume", "Button").name("resume"),
            node("quit", "Button").name("quit"), label("status", "-").name("status"), box("panel").name("panel")));
    }

    static Sample sample(UiGraph g, OmuiArchive doc, UiDocumentSource source) {
        return new Sample(g, DocumentEnvironment.of(doc, source));
    }

    /** Events, state, a branch, an awaited tween and actions (requested and awaited). */
    static Sample counter() {
        UiGraph g = new GraphBuilder("counter")
            .var("clicks", ValueType.INT, 0)
            .var("elapsed", ValueType.NUMBER, 0)
            .var("online", ValueType.BOOL, false)
            .node("opened", "ui:event.open")
            .node("hide", "ui:element.set-visible", "target", "panel", "=visible", false)
            .link("opened.then", "hide.exec")
            .node("on_resume", "ui:event.click", "target", "resume")
            .node("count", "ui:variable.increment", "variable", "clicks")
            .node("get", "ui:variable.get", "variable", "clicks")
            .node("fmt", "ui:format", "template", "Clicked {n} times")
            .node("show", "ui:element.set-text", "target", "status")
            .node("enough", "ui:compare", "op", "ge", "=b", 3)
            .node("br", "ui:flow.branch")
            .node("fade", "ui:anim.tween", "target", "panel", "property", "opacity", "easing", "ease-out",
                "=value", 0, "=duration", 0.2)
            .node("req", "ui:action.request", "action", "stonebreak:screen.pause.resume")
            .node("pulse", "ui:element.set-class", "target", "panel", "class", "pulse")
            .link("on_resume.then", "count.exec").link("count.then", "show.exec")
            .link("get.value", "fmt.n").link("fmt.text", "show.text")
            .link("show.then", "br.exec").link("get.value", "enough.a").link("enough.result", "br.condition")
            .link("br.true", "fade.exec").link("fade.then", "req.exec").link("br.false", "pulse.exec")
            .node("on_quit", "ui:event.click", "target", "quit")
            .node("ping", "ui:action.invoke", "action", "test:net.ping", "args", GraphBuilder.names("reason"),
                "=reason", "quit")
            .node("ok", "ui:flow.branch")
            .node("close", "ui:screen.close")
            .node("fail", "ui:element.set-text", "target", "status")
            .link("on_quit.then", "ping.exec").link("ping.then", "ok.exec").link("ping.ok", "ok.condition")
            .link("ok.true", "close.exec").link("ok.false", "fail.exec").link("ping.error", "fail.text")
            .node("frame", "ui:event.update")
            .node("acc", "ui:variable.increment", "variable", "elapsed")
            .link("frame.then", "acc.exec").link("frame.dt", "acc.by")
            .node("watch", "ui:event.watch", "path", "session.online")
            .node("store", "ui:variable.set", "variable", "online")
            .link("watch.then", "store.exec").link("watch.value", "store.value")
            .node("bye", "ui:event.close")
            .node("log", "ui:log", "=message", "bye")
            .link("bye.then", "log.exec")
            .build();
        return sample(g, screenDoc(), UiDocumentSource.EMPTY);
    }

    /** A multi-exit exec function, a single-exit exec function, a pure converter and a pure two-in function. */
    static Sample functions() {
        UiGraph g = new GraphBuilder("functions")
            .var("score", ValueType.INT, 12)
            .node("go", "ui:event.click", "target", "resume")
            .node("get", "ui:variable.get", "variable", "score")
            .node("rank", "ui:function.call", "function", "classify")
            .node("show", "ui:element.set-text", "target", "status")
            .node("say", "ui:function.call", "function", "announce")
            .node("desc", "ui:function.call", "function", "describe", "=b", "score")
            .node("twice", "ui:function.call", "function", "double")
            .node("after", "ui:log")
            .link("go.then", "rank.exec").link("get.value", "rank.score")
            .link("rank.high", "show.exec").link("rank.label", "show.text")
            .link("rank.low", "say.exec").link("get.value", "desc.a").link("desc.text", "say.message")
            .link("say.done", "after.exec").link("get.value", "twice.x").link("twice.y", "after.message")
            .function("classify", f -> f.in("exec", "exec").in("score", "int")
                .out("high", "exec").out("low", "exec").out("label", "string")
                .node("entry", "ui:function.entry")
                .node("cmp", "ui:compare", "op", "gt", "=b", 10)
                .node("br", "ui:flow.branch")
                .node("hi", "ui:function.return", "output", "high", "=label", "high")
                .node("lo", "ui:function.return", "output", "low", "=label", "low")
                .link("entry.then", "br.exec").link("entry.score", "cmp.a").link("cmp.result", "br.condition")
                .link("br.true", "hi.exec").link("br.false", "lo.exec"))
            .function("announce", f -> f.in("exec", "exec").in("message", "string").out("done", "exec")
                .node("entry", "ui:function.entry")
                .node("log", "ui:log")
                .node("ret", "ui:function.return")
                .link("entry.then", "log.exec").link("entry.message", "log.message").link("log.then", "ret.exec"))
            .function("describe", f -> f.in("a", "int").in("b", "string").out("text", "string")
                .node("entry", "ui:function.entry")
                .node("fmt", "ui:format", "template", "{b}: {a}")
                .node("ret", "ui:function.return")
                .link("entry.a", "fmt.a").link("entry.b", "fmt.b").link("fmt.text", "ret.text"))
            .function("double", f -> f.in("x", "number").out("y", "number")
                .node("entry", "ui:function.entry")
                .node("mul", "ui:math", "op", "mul", "=b", 2)
                .node("ret", "ui:function.return")
                .link("entry.x", "mul.a").link("mul.result", "ret.y"))
            .build();
        return sample(g, screenDoc(), UiDocumentSource.EMPTY);
    }

    /** A sequence, a for-each, a join reached by two links and a loop that waits. */
    static Sample flow() {
        UiValue list = new UiValue.Arr(List.of(UiValue.of("a"), UiValue.of("b"), UiValue.of("c")));
        UiGraph g = new GraphBuilder("flow")
            .node("opened", "ui:event.open")
            .node("seq", "ui:flow.sequence", "count", 3)
            .node("each", "ui:flow.for-each", "=list", list)
            .node("line", "ui:format", "template", "#{i}: {v}")
            .node("item", "ui:log")
            .node("done", "ui:log", "=message", "done")
            .node("read", "ui:data.read", "path", "session.online")
            .node("br", "ui:flow.branch")
            .node("tick", "ui:log", "=message", "tick")
            .node("wait", "ui:flow.wait", "=seconds", 1)
            .link("opened.then", "seq.exec")
            .link("seq.then0", "each.exec").link("each.body", "item.exec").link("each.completed", "done.exec")
            .link("each.index", "line.i").link("each.item", "line.v").link("line.text", "item.message")
            .link("seq.then1", "br.exec").link("read.value", "br.condition").link("br.true", "done.exec")
            .link("seq.then2", "tick.exec").link("tick.then", "wait.exec").link("wait.then", "tick.exec")
            .build();
        return sample(g, screenDoc(), UiDocumentSource.EMPTY);
    }

    static final String BRIDGE_CODE = """
        local M = {}

        --- Formats a score for the HUD.
        ---@param points integer
        ---@param prefix string?
        ---@return string text
        function M.format_score(points, prefix)
          return (prefix or "") .. points
        end

        ---@async
        ---@param seconds number
        function M.cooldown(seconds)
          ui.await(ui.sleep(seconds))
        end

        ---@return integer
        function best()
          return 42
        end

        return M
        """;

    static final UiDocument.ComponentDef KNOB = new UiDocument.ComponentDef(List.of(),
        List.of(new UiDocument.EventDef("changed",
            List.of(new UiDocument.Param("value", ValueType.INT, null, Map.of())), Map.of())),
        List.of(), Map.of());

    /** The component the bridge screen instantiates; its own graph emits {@code changed}. */
    static OmuiArchive knob() {
        return component("t:ui/knob", box("kroot").kids(node("plus", "Button").name("plus"),
            label("value", "0").name("value")), KNOB);
    }

    /** Annotated code-behind calls, custom events both ways and a component signal handler. */
    static Sample bridge() {
        OmuiArchive doc = screen("t:ui/bridge", box("root").kids(label("status", "-").name("status"),
            inst("knob", "t:ui/knob", Map.of())));
        UiDocument withCode = doc.document();
        doc = doc.withDocument(new UiDocument(withCode.root(), withCode.styleSheets(), "main", withCode.component(),
            withCode.unknown())).withScript("main", BRIDGE_CODE);
        UiGraph g = new GraphBuilder("bridge")
            .node("on_score", "ui:event.custom", "name", "scored", "params", GraphBuilder.params("score", "int"))
            .node("fmt", "lua:call", "function", "format_score", "=prefix", "Score: ")
            .node("show", "ui:element.set-text", "target", "status")
            .node("cool", "lua:call", "function", "cooldown", "=seconds", 0.5)
            .node("raise", "ui:event.raise", "name", "cooled", "params", GraphBuilder.params("score", "int"))
            .link("on_score.then", "fmt.exec").link("on_score.score", "fmt.points")
            .link("fmt.then", "show.exec").link("fmt.text", "show.text")
            .link("show.then", "cool.exec").link("cool.then", "raise.exec").link("on_score.score", "raise.score")
            .node("on_changed", "ui:event.signal", "target", "knob", "signal", "changed")
            .node("top", "lua:call", "function", "best", "pure", true)
            .node("cmp", "ui:compare", "op", "ge")
            .node("record", "ui:element.set-visible", "target", "knob/value")
            .link("on_changed.then", "record.exec").link("on_changed.value", "cmp.a").link("top.result", "cmp.b")
            .link("cmp.result", "record.visible")
            .build();
        return sample(g, doc, UiDocumentSource.of(Map.of("t:ui/knob", knob()), Map.of()));
    }

    /** A component's own graph: it emits its declared signal. */
    static Sample knobComponent() {
        UiGraph g = new GraphBuilder("knob")
            .var("value", ValueType.INT, 0)
            .node("press", "ui:event.click", "target", "plus")
            .node("inc", "ui:variable.increment", "variable", "value")
            .node("get", "ui:variable.get", "variable", "value")
            .node("show", "ui:element.set-text", "target", "value")
            .node("emit", "ui:signal.emit", "signal", "changed")
            .link("press.then", "inc.exec").link("inc.then", "show.exec").link("get.value", "show.text")
            .link("show.then", "emit.exec").link("get.value", "emit.value")
            .build();
        return sample(g, knob(), UiDocumentSource.EMPTY);
    }
}
