package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua code-behind (#292): events, bindings, async host actions and animation on one screen;
 * isolated component instances; signals; data watches; raw input; modules.
 */
class ScriptBehaviourTest {

    static final HostContract SESSION = HostContract.of("test:session", 1);
    static final HostContract NET = HostContract.of("test:net", 1);

    /** A small host: session data and a held "ping" action the test completes. */
    static final class Host {
        final UiHost host = new UiHost();
        final DataCell session = new DataCell(DataType.object("online", DataType.bool()),
            obj("online", false));
        final List<CompletableFuture<UiValue>> held = new ArrayList<>();
        final List<UiValue.Obj> calls = new ArrayList<>();

        Host() {
            host.data().register("session", session, SESSION);
            host.actions().register(ActionSpec.of("test:net.ping", NET, DataType.object("n", DataType.integer()),
                DataType.object("pong", DataType.integer())), (args, ctx) -> {
                    calls.add(args);
                    CompletableFuture<UiValue> f = new CompletableFuture<>();
                    held.add(f);
                    return f;
                });
        }

        void complete(int i, int pong) {
            held.get(i).complete(obj("pong", pong));
            host.drain();
        }
    }

    static UiValue.Obj obj(Object... kv) {
        java.util.Map<String, UiValue> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], UiDocs.value(kv[i + 1]));
        }
        return new UiValue.Obj(m);
    }

    static UiDocs.N button(String id) {
        return node(id, "Button").name(id).style("width", 100).style("height", 30);
    }

    static UiDocs.N root(UiDocs.N... kids) {
        return box("root").style("width", 400).style("height", 300).kids(kids);
    }

    static OmuiArchive declared(OmuiArchive doc) {
        return UiDocs.declare(doc, List.of(), "test:session@1", "test:net@1");
    }

    // ── acceptance: one Lua-authored screen ─────────────────────────────────

    static final String SCREEN = """
        local M = {}
        local pongs = 0

        ui.converter("display_if", { result = "string", to = function(v) return v and "flex" or "none" end })

        function M.on_open(ui)
          ui.q("#ping"):on("click", function()
            ui.q("#status"):setText("pinging")
            local r, err = ui.await(ui.action("test:net.ping", { n = 1 }))
            if r then
              pongs = pongs + r.pong
              ui.q("#status"):setText("pong " .. pongs)
              ui.await(ui.tween(ui.q("#status"), { opacity = 0.25 }, 0.5, "linear"))
              ui.q("#status"):addClass("faded")
            else
              ui.q("#status"):setText("failed: " .. tostring(err))
            end
          end)
        end
        return M
        """;

    static OmuiArchive screenDoc() {
        return declared(ScriptRig.withCode(screen("t:ui/scripted", root(
            button("ping"),
            label("status", "idle").name("status"),
            box("online").name("online").style("width", 50).style("height", 10)
                .bind("style:display", "session.online", UiNode.BindingMode.TO_TARGET, "display_if"))), SCREEN));
    }

    @Test
    void oneScreenShowsEventsBindingsAnimationAndAnAsyncAction() {
        Host h = new Host();
        try (ScriptRig rig = new ScriptRig(screenDoc(), UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            // Binding through a Lua converter.
            assertEquals("none", rig.el("online").computedStyle().keyword("display", "flex"));
            h.session.set(obj("online", true));
            rig.layout();
            assertEquals("flex", rig.el("online").computedStyle().keyword("display", "flex"));

            // Code-behind event → async host action.
            rig.click("ping");
            assertEquals("pinging", rig.text("status"));
            assertEquals(List.of(obj("n", 1)), h.calls);
            rig.frame(0.016);
            assertEquals("pinging", rig.text("status"), "nothing resumes before the result arrives");
            h.complete(0, 41);
            rig.frame(0.016);
            assertEquals("pong 41", rig.text("status"), "the task resumed with the action's result");

            // Animation through the host sampler; the task resumes when the tween completes.
            rig.frame(0.25);
            double opacity = rig.el("status").computedStyle().number("opacity", 1);
            assertTrue(opacity < 0.75 && opacity > 0.4, "half-way: " + opacity);
            assertFalse(rig.el("status").hasClass("faded"));
            rig.frame(0.3);
            rig.frame(0.016);
            assertEquals(0.25, rig.el("status").computedStyle().number("opacity", 1), 1e-6);
            assertTrue(rig.el("status").hasClass("faded"), "the awaiting task continued after the tween");
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
        }
    }

    @Test
    void failedActionsReturnNilAndAMessage() {
        Host h = new Host();
        try (ScriptRig rig = new ScriptRig(screenDoc(), UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            rig.click("ping");
            h.held.getFirst().completeExceptionally(new IllegalStateException("server down"));
            h.host.drain();
            rig.frame(0.016);
            assertTrue(rig.text("status").startsWith("failed: ") && rig.text("status").contains("server down"),
                rig.text("status"));
        }
    }

    @Test
    void clipsPlayAndFireEvents() {
        UiAnimationClip clip = new UiAnimationClip("pulse", 1.0, UiAnimationClip.LoopMode.ONCE, List.of(
            new UiAnimationClip.AnimTrack("box", "style:opacity", List.of(
                new UiAnimationClip.AnimKey(0, UiValue.of(1), UiEasing.LINEAR, Map.of()),
                new UiAnimationClip.AnimKey(1, UiValue.of(0), UiEasing.LINEAR, Map.of())), Map.of())),
            List.of(new UiAnimationClip.AnimEvent(0.5, "half", Map.of())), Map.of());
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/clip", root(box("box").name("box").style("width", 10)
                .style("height", 10), label("out", "").name("out"))), """
            function on_open(ui)
              local h = ui.play("pulse", { on_event = function(name) ui.q("#out"):setText(name) end })
              ui.await(h)
              ui.q("#out"):setText("done")
            end
            """).withAnimation(clip);
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.frame(0.25);
            assertEquals(0.75, rig.el("box").computedStyle().number("opacity", 1), 1e-6);
            rig.frame(0.3);
            rig.frame(0.0);
            assertEquals("half", rig.text("out"));
            rig.frame(0.5);
            rig.frame(0.0);
            assertEquals("done", rig.text("out"));
            assertEquals(0.0, rig.el("box").computedStyle().number("opacity", 1), 1e-6, "a finished clip holds");
        }
    }

    // ── acceptance: isolated component instances ────────────────────────────

    static final String COUNTER = """
        local M = {}
        count = count or 0 -- an env global: per instance, kept across hot reload
        function M.on_open(ui)
          ui.log("open " .. ui.key)
          ui.root:q("#plus"):on("click", function()
            count = count + 1
            ui.get("value"):setText(tostring(count))
            ui.emit("changed", { value = count })
          end)
        end
        function M.on_close(ui) ui.log("close " .. ui.key) end
        return M
        """;

    static OmuiArchive counterComponent() {
        UiDocument.ComponentDef def = new UiDocument.ComponentDef(List.of(), List.of(
            new UiDocument.EventDef("changed", List.of(new UiDocument.Param("value", ValueType.INT)), Map.of())),
            List.of(), Map.of());
        return ScriptRig.withCode(component("t:ui/counter", box("frame").style("flex-direction", "row").kids(
            button("plus"), label("value", "0").name("value")), def), "counter", COUNTER);
    }

    @Test
    void twoComponentInstancesHaveIsolatedStateAndLifecycles() {
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/counters", root(
            inst("a", "t:ui/counter", Map.of()), inst("b", "t:ui/counter", Map.of()),
            label("total", "").name("total"))), """
            local total = 0
            function on_open(ui)
              for _, key in ipairs({ "a", "b" }) do
                ui.get(key):on("changed", function(args)
                  total = total + 1
                  ui.q("#total"):setText(key .. "=" .. args.value .. " total=" .. total)
                end)
              end
            end
            """);
        UiDocumentSource source = UiDocumentSource.of(Map.of("t:ui/counter", counterComponent()), Map.of());
        ScriptRig rig = new ScriptRig(doc, source, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE);
        try {
            assertEquals(3, rig.rt.modules().size(), rig.rt.modules().toString());
            rig.click("a/plus");
            rig.click("a/plus");
            rig.click("b/plus");
            assertEquals("2", rig.text("a/value"));
            assertEquals("1", rig.text("b/value"), "each instance counts in its own environment");
            assertEquals("b=1 total=3", rig.text("total"), "signals reach the screen's handlers");
            assertTrue(rig.log().containsAll(List.of("open a", "open b")), rig.log().toString());
        } finally {
            rig.close();
        }
        assertTrue(rig.log().containsAll(List.of("close a", "close b")), rig.log().toString());
        assertTrue(rig.rt.isClosed());
    }

    @Test
    void componentScriptsCannotReachOutsideTheirInstance() {
        OmuiArchive comp = ScriptRig.withCode(component("t:ui/nosy", box("frame").kids(label("inner", "x")),
            new UiDocument.ComponentDef(List.of(), List.of(), List.of(), Map.of())), "nosy", """
            function on_open(ui)
              ui.log("q=" .. tostring(ui.q("#secret")))
              local ok, err = pcall(function() return ui.get("../secret") end)
              ui.log("get=" .. tostring(ok and err))
              local ok2, err2 = pcall(function() local e = ui.root:q("#inner") return e:parent():parent():name() end)
              ui.log("parent=" .. tostring(ok2 and err2))
            end
            """);
        OmuiArchive doc = screen("t:ui/host", root(label("secret", "pin").name("secret"), inst("n", "t:ui/nosy", Map.of())));
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.of(Map.of("t:ui/nosy", comp), Map.of()), null,
            UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            assertTrue(rig.log().containsAll(List.of("q=nil", "get=nil")), rig.log().toString());
            assertTrue(rig.log().stream().anyMatch(l -> l.startsWith("parent=")), rig.log().toString());
            assertFalse(rig.log().contains("parent=root"), "the parent chain stops at the instance");
        }
    }

    // ── data watches, input hook, modules ───────────────────────────────────

    @Test
    void watchesDeliverAtTheNextFrame() {
        Host h = new Host();
        OmuiArchive doc = declared(ScriptRig.withCode(screen("t:ui/watch", root(label("out", "").name("out"))), """
            function on_open(ui)
              local v, state = ui.read("session.online")
              ui.q("#out"):setText(tostring(v) .. "/" .. state)
              ui.watch("session.online", function(value) ui.q("#out"):setText("now " .. tostring(value)) end)
            end
            """));
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertEquals("false/ready", rig.text("out"));
            h.session.set(obj("online", true));
            h.session.set(obj("online", false));
            h.session.set(obj("online", true));
            rig.frame(0.016);
            assertEquals("now true", rig.text("out"), "coalesced to the latest value");
        }
    }

    @Test
    void onInputSeesRawInputFirstAndMayConsumeIt() {
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/keys", root(label("out", "").name("out"))), """
            local seen = {}
            function on_input(ev)
              if ev.type == "key-down" then
                seen[#seen + 1] = ev.key
                ui.q("#out"):setText(table.concat(seen, ","))
                return ev.key == 32
              end
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            assertFalse(rig.view.input().keyDown(65, 0, false), "not consumed");
            assertTrue(rig.view.input().keyDown(32, 0, false), "the hook consumed space");
            assertEquals("65,32", rig.text("out"));
        }
    }

    @Test
    void requireResolvesDeclaredModulesOnly() {
        byte[] shared = "local M = {} function M.twice(x) return x * 2 end return M".getBytes(StandardCharsets.UTF_8);
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/mods", root(label("out", "").name("out"))), """
            local util = require("util")
            local math2 = require("t:ui/scripts/math2")
            assert(require("util") == util, "modules are cached")
            local ok, err = pcall(require, "t:ui/scripts/undeclared")
            local ok2, err2 = pcall(require, "missing")
            function on_open(ui)
              ui.q("#out"):setText(util.hello() .. " " .. math2.twice(21) .. " " .. tostring(ok) .. " "
                .. tostring(ok2))
              ui.log(err)
              ui.log(err2)
            end
            """).withScript("util", "return { hello = function() return 'hi' end }")
            .withDependencies(new OmuiArchive.UiDependencies(List.of(UiDependency.shared("t:ui/scripts/math2",
                UiDependency.Kind.SCRIPT, UiBytes.copyOf(shared).sha256(), shared.length, "UI/t/ui/scripts/math2.lua")),
                Map.of()));
        UiDocumentSource source = UiDocumentSource.of(Map.of(), Map.of(),
            Map.of("t:ui/scripts/math2", new String(shared, StandardCharsets.UTF_8),
                "t:ui/scripts/undeclared", "return {}"));
        try (ScriptRig rig = new ScriptRig(doc, source, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            assertEquals("hi 42 false false", rig.text("out"), rig.rt.diagnostics().toString());
            assertTrue(rig.log().stream().anyMatch(l -> l.contains("not a declared script dependency")), rig.log().toString());
            assertTrue(rig.log().stream().anyMatch(l -> l.contains("no script scripts/missing.lua")), rig.log().toString());
        }
    }

    @Test
    void documentsWithoutCodeBehindNeedNoLuaState() {
        OmuiArchive doc = screen("t:ui/plain", root(label("out", "x")));
        try (ScriptRig rig = new ScriptRig(doc)) {
            assertFalse(rig.rt.isScripted());
            assertNotNull(rig.rt.converters());
            rig.frame(0.016);
        }
    }

    @Test
    void canvasCommandsLandInTheNativeBuffer() {
        OmuiArchive doc = UiDocs.declare(ScriptRig.withCode(screen("t:ui/canvas", root(
            node("game", "Canvas").name("game").style("width", 200).style("height", 100).prop("capacity", 256))), """
            local c
            function on_open(ui) c = ui.q("#game"):canvas() end
            function update(dt)
              c:clear()
              c:rect(1, 2, 3, 4, 0xff0000, 0.5)
              c:text("score", 10, 20)
              c:text("score", 10, 40)
            end
            """), List.of(UiFeatures.CANVAS));
        try (ScriptRig rig = new ScriptRig(doc)) {
            rig.frame(0.016);
            var cmds = rig.ui.canvas("game");
            assertNotNull(cmds);
            assertEquals(7 + 7 + 7, cmds.size());
            assertEquals(2f, cmds.get(0));
            assertEquals(0xff0000, (int) cmds.get(5));
            assertEquals("score", cmds.string((int) cmds.get(8)));
            assertEquals(cmds.get(8), cmds.get(15), "a string is registered once");
            rig.frame(0.016);
            assertEquals(21, cmds.size(), "clear() resets each frame");
        }
    }

    @Test
    void keyEventsCarryTheirKeyToHandlers() {
        OmuiArchive doc = UiDocs.declare(ScriptRig.withCode(screen("t:ui/kh", root(
            button("b").prop("focusable", true), label("out", "").name("out"))), """
            function on_open(ui)
              local b = ui.q("#b")
              b:on("key-down", function(ev) ui.q("#out"):setText("key " .. ev.key .. " on " .. ev.target.key) ev:stop() end)
              b:focus()
            end
            """), List.of(UiFeatures.INPUT));
        try (ScriptRig rig = new ScriptRig(doc)) {
            assertTrue(rig.view.input().keyDown(66, 0, false));
            assertEquals("key 66 on b", rig.text("out"));
        }
    }
}
