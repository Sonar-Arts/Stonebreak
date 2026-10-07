package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.graph.GraphBuilder;
import com.openmason.engine.ui.runtime.UiDocs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;

/**
 * The #292 sample documents, built from the readable sources in
 * {@code src/test/resources/ui/script/samples/}. {@code ScriptSampleFixtureTest} pins the packed
 * archives next to them ({@code ui/script/*.omui}), which the game's dev overlay
 * ({@code -Dstonebreak.uidoc}) and the editor preview ({@code -Dopenmason.uidoc.preview}) open.
 * Not a test class.
 */
final class ScriptSamples {

    static final String PAUSE_ID = "stonebreak:ui/samples/scripted_pause";
    static final String MINIGAME_ID = "stonebreak:ui/samples/minigame";
    static final String GRAPH_PAUSE_ID = "stonebreak:ui/samples/graph_pause";

    /** Preview fixture: an online session and deterministic action results. */
    static final String PAUSE_FIXTURE = """
        {
          "data": {
            "session": {
              "hosting": false,
              "mode": "SINGLEPLAYER",
              "online": true
            }
          },
          "contracts": {
            "session": "stonebreak:session"
          },
          "actions": {
            "stonebreak:network.resync": {
              "result": {
                "audited": 12
              }
            },
            "stonebreak:screen.pause.resume": {
              "result": null
            },
            "stonebreak:screen.pause.quit": {
              "result": null
            }
          }
        }
        """;

    private ScriptSamples() {
    }

    static OmuiArchive scriptedPause() {
        UiDocs.N panel = box("panel").name("panel").style("width", 360).style("height", 260)
            .style("background-color", "#202020E6").style("border-radius", 6).style("align-items", "center")
            .style("justify-content", "center").style("row-gap", 12).kids(
                label("title", "Scripted Pause").name("title").style("font-size", 24).style("color", "#FFFFFF"),
                label("online", "Online").name("online").style("color", "#7CFC00")
                    .bind("style:display", "session.online", UiNode.BindingMode.TO_TARGET, "display_if"),
                label("status", "Ready").name("status").style("color", "#FFFFFF"),
                button("resync", "Resync"),
                button("resume", "Resume"));
        UiDocs.N root = box("root").style("width", "100%").style("height", "100%").style("align-items", "center")
            .style("justify-content", "center").style("picking-mode", "ignore").kids(panel);
        OmuiArchive doc = UiDocs.declare(screen(PAUSE_ID, root), List.of(), "stonebreak:network.resync@1",
            "stonebreak:screen.pause@1", "stonebreak:session@1");
        return ScriptRig.withCode(doc, "pause", source("scripted_pause.lua"))
            .withEditorEntry("editor/fixtures.json", UiBytes.utf8(PAUSE_FIXTURE));
    }

    /**
     * The #291 acceptance screen: the scripted pause's layout with no code-behind at all. One
     * behavior graph drives the open animation, conditional visibility (a data watch and a
     * graph-function converter), pause navigation (focus, resume after an awaited fade, quit)
     * and an awaited resync action. Its resume handler is the parity twin of
     * {@code scripted_pause.lua}'s.
     */
    static OmuiArchive graphPause() {
        UiDocs.N panel = box("panel").name("panel").style("width", 360).style("height", 300)
            .style("background-color", "#202020E6").style("border-radius", 6).style("align-items", "center")
            .style("justify-content", "center").style("row-gap", 12).kids(
                label("title", "Graph Pause").name("title").style("font-size", 24).style("color", "#FFFFFF"),
                label("online", "Online").name("online").style("color", "#7CFC00")
                    .bind("style:display", "session.online", UiNode.BindingMode.TO_TARGET, "display_if"),
                label("status", "Ready").name("status").style("color", "#FFFFFF"),
                button("resync", "Resync"),
                button("resume", "Resume"),
                button("quit", "Quit"));
        UiDocs.N root = box("root").style("width", "100%").style("height", "100%").style("align-items", "center")
            .style("justify-content", "center").style("picking-mode", "ignore").kids(panel);
        OmuiArchive doc = UiDocs.declare(screen(GRAPH_PAUSE_ID, root), List.of(), "stonebreak:network.resync@1",
            "stonebreak:screen.pause@1", "stonebreak:session@1");
        // Laid out the way the editor's Arrange would, so the sample opens readable in the graph window.
        com.openmason.engine.ui.graph.edit.GraphEditor layout = com.openmason.engine.ui.graph.edit.GraphEditor.open(
            doc.withGraph(pauseGraph()), "pause", com.openmason.engine.ui.runtime.UiDocumentSource.EMPTY);
        layout.arrange("");
        layout.arrange("display_if");
        return layout.document().withEditorEntry("editor/fixtures.json", UiBytes.utf8(PAUSE_FIXTURE));
    }

    static UiGraph pauseGraph() {
        return new GraphBuilder("pause")
            .var("resyncs", ValueType.INT, 0)
            // Open: fade and slide the panel in, show resync only when online, focus resume.
            .node("opened", "ui:event.open")
            .node("hide", "ui:element.set-style", "target", "panel", "property", "opacity", "=value", 0)
            .node("lower", "ui:element.set-style", "target", "panel", "property", "translate-y", "=value", 24)
            .node("fade_in", "ui:anim.tween", "target", "panel", "property", "opacity", "easing", "ease-out",
                "wait", false, "=value", 1, "=duration", 0.35)
            .node("slide_in", "ui:anim.tween", "target", "panel", "property", "translate-y", "easing", "ease-out",
                "wait", false, "=value", 0, "=duration", 0.35)
            .node("online_now", "ui:data.read", "path", "session.online")
            .node("show_resync", "ui:element.set-visible", "target", "resync")
            .node("focus_resume", "ui:element.focus", "target", "resume")
            .link("opened.then", "hide.exec").link("hide.then", "lower.exec").link("lower.then", "fade_in.exec")
            .link("fade_in.then", "slide_in.exec").link("slide_in.then", "show_resync.exec")
            .link("online_now.value", "show_resync.visible").link("show_resync.then", "focus_resume.exec")
            // Conditional visibility while open: the resync button follows session.online.
            .node("online_changed", "ui:event.watch", "path", "session.online")
            .node("track_resync", "ui:element.set-visible", "target", "resync")
            .link("online_changed.then", "track_resync.exec").link("online_changed.value", "track_resync.visible")
            // Resync: an awaited host action, then a coloured status line.
            .node("on_resync", "ui:event.click", "target", "resync")
            .node("busy", "ui:element.set-text", "target", "status", "=text", "Resyncing...")
            .node("resync", "ui:action.invoke", "action", "stonebreak:network.resync")
            .node("worked", "ui:flow.branch")
            .node("count", "ui:variable.increment", "variable", "resyncs")
            .node("audited", "ui:object.get", "field", "audited")
            .node("total", "ui:variable.get", "variable", "resyncs")
            .node("report", "ui:format", "template", "Audited {audited} chunks ({n})")
            .node("show_ok", "ui:element.set-text", "target", "status")
            .node("green", "ui:anim.tween", "target", "status", "property", "color", "easing", "ease-in-out",
                "wait", false, "=value", "#7CFC00", "=duration", 0.25)
            .node("why", "ui:format", "template", "Resync failed: {error}")
            .node("show_fail", "ui:element.set-text", "target", "status")
            .node("red", "ui:anim.tween", "target", "status", "property", "color", "wait", false,
                "=value", "#FF5050", "=duration", 0.25)
            .link("on_resync.then", "busy.exec").link("busy.then", "resync.exec").link("resync.then", "worked.exec")
            .link("resync.ok", "worked.condition")
            .link("worked.true", "count.exec").link("count.then", "show_ok.exec")
            .link("resync.result", "audited.object").link("audited.value", "report.audited")
            .link("total.value", "report.n").link("report.text", "show_ok.text").link("show_ok.then", "green.exec")
            .link("worked.false", "show_fail.exec").link("resync.error", "why.error").link("why.text", "show_fail.text")
            .link("show_fail.then", "red.exec")
            // Pause navigation: resume after the panel fades out (parity with scripted_pause.lua), quit at once.
            .node("on_resume", "ui:event.click", "target", "resume")
            .node("fade_out", "ui:anim.tween", "target", "panel", "property", "opacity", "easing", "ease-in",
                "=value", 0, "=duration", 0.2)
            .node("resume", "ui:action.request", "action", "stonebreak:screen.pause.resume")
            .link("on_resume.then", "fade_out.exec").link("fade_out.then", "resume.exec")
            .node("on_quit", "ui:event.click", "target", "quit")
            .node("quit", "ui:action.request", "action", "stonebreak:screen.pause.quit")
            .link("on_quit.then", "quit.exec")
            // A computed binding: session.online -> style:display of the "online" badge.
            .function("display_if", f -> f.in("online", "bool").out("display", "string")
                .node("entry", "ui:function.entry")
                .node("pick", "ui:select", "=a", "flex", "=b", "none")
                .node("result", "ui:function.return")
                .link("entry.online", "pick.condition").link("pick.value", "result.display"))
            .build();
    }

    static OmuiArchive minigame() {
        UiDocs.N frame = box("frame").name("frame").style("align-items", "center").style("row-gap", 8).kids(
            label("title", "Minigame: 1,000 sprites").name("title").style("color", "#FFFFFF"),
            node("game", "Canvas").name("game").style("width", 640).style("height", 360).prop("capacity", 16384),
            label("hint", "Move the pointer to bat the sprites; click to reset").name("hint")
                .style("color", "#B0B0B0"));
        UiDocs.N root = box("root").style("width", "100%").style("height", "100%").style("align-items", "center")
            .style("justify-content", "center").style("picking-mode", "ignore").kids(frame);
        OmuiArchive doc = UiDocs.declare(screen(MINIGAME_ID, root), List.of(UiFeatures.CANVAS));
        return ScriptRig.withCode(doc, "minigame", source("minigame.lua"));
    }

    private static UiDocs.N button(String id, String text) {
        return node(id, "Button").name(id).style("width", 160).style("height", 36).style("align-items", "center")
            .style("justify-content", "center").kids(label(id + "_label", text).style("color", "#FFFFFF"));
    }

    static String source(String file) {
        try (InputStream in = ScriptSamples.class.getResourceAsStream("/ui/script/samples/" + file)) {
            if (in == null) {
                throw new IllegalStateException("missing sample source " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
