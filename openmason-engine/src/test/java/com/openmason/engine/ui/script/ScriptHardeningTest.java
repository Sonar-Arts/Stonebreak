package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.button;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.declared;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The #282 hardening pass over the script runtime: closing or reloading from inside a call never
 * frees the state under the running VM, a world change cancels every kind of waiting task, a
 * failed handler releases what it started, per-context caps, context isolation and call sites.
 */
class ScriptHardeningTest {

    static OmuiArchive doc(String script) {
        return declared(ScriptRig.withCode(screen("t:ui/hard", root(button("go"), button("other"),
            box("panel").name("panel").style("width", 50).style("height", 50),
            label("out", "idle").name("out"))), script));
    }

    /** Services whose close/navigate act at once, the way an impatient host would. */
    static UiScriptServices immediate(AtomicReference<ScriptRig> rig, boolean reloadInstead) {
        return new UiScriptServices() {
            @Override
            public void requestClose() {
                if (reloadInstead) {
                    rig.get().rt.reload();
                } else {
                    rig.get().rt.close();
                }
            }
        };
    }

    // ── C4: close / reload under a running call ─────────────────────────────

    @Test
    void closingFromInsideAHandlerWaitsForTheCallToReturn() {
        AtomicReference<ScriptRig> holder = new AtomicReference<>();
        ScriptRig rig = new ScriptRig(doc("""
            function on_open(ui)
              ui.q("#go"):on("click", function()
                ui.close()                       -- the host closes the runtime right here
                ui.q("#out"):setText("after close")  -- the call is still running: the state must live
              end)
            end
            function on_close(ui) ui.log("on_close ran") end
            """), UiDocumentSource.EMPTY, null, UiScriptOptions.DEFAULTS, immediate(holder, false));
        holder.set(rig);
        rig.click("go");
        assertTrue(rig.rt.isClosed());
        assertEquals("after close", rig.text("out"), "the handler finished on a live state");
        assertTrue(rig.log().contains("on_close ran"), rig.log().toString());
        assertEquals(0, rig.rt.memoryUsed(), "the state was freed once the call returned");
        assertEquals(0, rig.rt.pendingCount());
        rig.frame(0.016); // nothing runs any more
        rig.close();
    }

    @Test
    void reloadingFromInsideAHandlerIsDeferredToo() {
        AtomicReference<ScriptRig> holder = new AtomicReference<>();
        ScriptRig rig = new ScriptRig(doc("""
            opens = (opens or 0) + 1
            function on_open(ui)
              ui.log("open " .. opens)
              ui.q("#go"):on("click", function() ui.close() ui.q("#out"):setText("still here") end)
            end
            """), UiDocumentSource.EMPTY, null, UiScriptOptions.DEFAULTS, immediate(holder, true));
        holder.set(rig);
        try {
            rig.click("go");
            assertEquals("still here", rig.text("out"));
            assertFalse(rig.rt.isClosed());
            assertTrue(rig.log().contains("open 2"), "the reload ran after the call: " + rig.log());
        } finally {
            rig.close();
        }
    }

    @Test
    void onCloseAskingToCloseAgainIsANoOp() {
        AtomicReference<ScriptRig> holder = new AtomicReference<>();
        ScriptRig rig = new ScriptRig(doc("""
            function on_close(ui) ui.close() ui.log("closing once") end
            """), UiDocumentSource.EMPTY, null, UiScriptOptions.DEFAULTS, immediate(holder, false));
        holder.set(rig);
        rig.close();
        assertTrue(rig.rt.isClosed());
        assertEquals(1, rig.log().stream().filter("closing once"::equals).count(), rig.log().toString());
    }

    // ── C5: world change ────────────────────────────────────────────────────

    @Test
    void leavingTheWorldCancelsSleepsTweensAndWatchCallbacks() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        OmuiArchive d = doc("""
            local function guard(name)
              return setmetatable({}, { __close = function() ui.log(name .. " closed") end })
            end
            function on_open(ui)
              ui.async(function() local g <close> = guard("sleep") ui.await(ui.sleep(5)) ui.log("slept") end)
              ui.async(function()
                local g <close> = guard("tween")
                ui.await(ui.tween(ui.q("#panel"), { opacity = 0.2 }, 5))
                ui.log("tweened")
              end)
              ui.watch("session.online", function()
                ui.log("watch fired")
                local g <close> = guard("watch")
                ui.await(ui.sleep(5))
                ui.log("watched")
              end)
            end
            """);
        try (ScriptRig rig = new ScriptRig(d, UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            h.session.set(ScriptBehaviourTest.obj("online", true));
            rig.frame(0.016); // the watch callback starts and waits
            h.host.advanceEpoch("left the world");
            h.host.drain();
            rig.frame(0.016);
            assertTrue(rig.log().containsAll(List.of("sleep closed", "tween closed", "watch closed")),
                rig.log().toString());
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.TASK_CANCELLED), rig.codes().toString());
            for (int i = 0; i < 10; i++) {
                rig.frame(1);
            }
            assertFalse(rig.log().stream().anyMatch(l -> l.equals("slept") || l.equals("tweened") || l.equals("watched")),
                "no cancelled task resumed in the new world: " + rig.log());
            // Handlers and watches survive the world change.
            h.session.set(ScriptBehaviourTest.obj("online", false));
            rig.frame(0.016);
            assertEquals(2, rig.log().stream().filter("watch fired"::equals).count(),
                "the watch itself survives the world change: " + rig.log());
        }
    }

    // ── journal: a failed handler releases what it started ──────────────────

    @Test
    void aFailedHandlerReleasesTweensTimersAndHandlersItStarted() {
        try (ScriptRig rig = new ScriptRig(doc("""
            function on_open(ui)
              ui.q("#go"):on("click", function()
                ui.tween(ui.q("#panel"), { opacity = 0.2 }, 10)
                ui.q("#other"):on("click", function() ui.log("other clicked") end)
                ui.sleep(1)
                error("boom")
              end)
            end
            """))) {
            rig.click("go");
            assertTrue(rig.codes().contains(UiScriptDiagnostic.Code.RUNTIME), rig.codes().toString());
            assertEquals(0, rig.ui.animator().active(), "the failed handler's tween was released");
            rig.frame(0.016); // the release's own "stopped" notice drains
            assertEquals(0, rig.rt.pendingCount(), "its timer was removed");
            rig.click("other");
            assertFalse(rig.log().contains("other clicked"), "its handler registration was undone");
        }
    }

    // ── caps ────────────────────────────────────────────────────────────────

    @Test
    void handleFloodsAreRefusedAtTheCap() {
        try (ScriptRig rig = new ScriptRig(doc("""
            function on_open(ui)
              local ok, err = pcall(function() for i = 1, 5000 do ui.sleep(100) end end)
              ui.log(tostring(ok) .. " " .. tostring(err))
            end
            """))) {
            assertTrue(rig.log().stream().anyMatch(l -> l.startsWith("false") && l.contains("unsettled handles")),
                rig.log().toString());
            assertEquals(UiScriptRuntime.MAX_HANDLES, rig.rt.pendingCount());
        }
    }

    @Test
    void watchFloodsAreRefusedAtTheCap() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        try (ScriptRig rig = new ScriptRig(doc("""
            function on_open(ui)
              local ok, err = pcall(function()
                for i = 1, 2000 do ui.watch("session.online", function() end) end
              end)
              ui.log(tostring(ok) .. " " .. tostring(err))
            end
            """), UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            assertTrue(rig.log().stream().anyMatch(l -> l.startsWith("false") && l.contains("watches")),
                rig.log().toString());
        }
    }

    // ── isolation ───────────────────────────────────────────────────────────

    @Test
    void scriptsCannotReachTheirContextTable() {
        try (ScriptRig rig = new ScriptRig(doc("""
            local hd = ui.sleep(1)
            ui.log("el=" .. tostring(rawget(ui.root, "__ctx")) .. " " .. tostring(ui.root.__ctx)
              .. " handle=" .. tostring(rawget(hd, "ctx")))
            local ok = pcall(ui.seek, { token = hd.token, kind = "anim" }, 1)
            ui.log("forged handle accepted=" .. tostring(ok))
            """))) {
            assertTrue(rig.log().contains("el=nil nil handle=nil"), rig.log().toString());
            assertTrue(rig.log().contains("forged handle accepted=false"), rig.log().toString());
        }
    }

    @Test
    void severalTasksMayAwaitOneHandle() {
        try (ScriptRig rig = new ScriptRig(doc("""
            function on_open(ui)
              local hd = ui.sleep(0.1)
              ui.async(function() ui.await(hd) ui.log("first") end)
              ui.async(function() ui.await(hd) ui.log("second") end)
            end
            """))) {
            rig.frame(0.2);
            assertTrue(rig.log().containsAll(List.of("first", "second")), rig.log().toString());
        }
    }

    // ── call sites (#289 AC2) ───────────────────────────────────────────────

    @Test
    void actionCallSitesNameTheHandlerElement() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        List<CallSite> sites = new ArrayList<>();
        h.host.actions().register(ActionSpec.of("test:net.where", ScriptBehaviourTest.NET, DataType.object(),
            DataType.ANY), (args, ctx) -> {
                sites.add(ctx.site());
                return CompletableFuture.completedFuture(UiValue.NULL);
            });
        try (ScriptRig rig = new ScriptRig(doc("""
            function on_open(ui)
              ui.request("test:net.where")
              ui.q("#go"):on("click", function() ui.request("test:net.where") end)
            end
            """), UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            rig.click("go");
            assertEquals(2, sites.size(), sites.toString());
            assertEquals(CallSite.Origin.SCRIPT, sites.get(1).origin());
            assertEquals("go", sites.get(1).elementKey(), "the element whose handler made the call");
            assertTrue(sites.get(1).describe().contains("t:ui/hard"), sites.get(1).describe());
        }
    }

    @Test
    void graphActionCallSitesNameTheGraphNode() {
        ScriptBehaviourTest.Host h = new ScriptBehaviourTest.Host();
        List<CallSite> sites = new ArrayList<>();
        h.host.actions().register(ActionSpec.of("test:net.where", ScriptBehaviourTest.NET, DataType.object(),
            DataType.ANY), (args, ctx) -> {
                sites.add(ctx.site());
                return CompletableFuture.completedFuture(UiValue.NULL);
            });
        OmuiArchive d = doc("-- graphs only").withGraph(new com.openmason.engine.ui.graph.GraphBuilder("g")
            .node("click", "ui:event.click", "target", "go")
            .node("req", "ui:action.request", "action", "test:net.where")
            .link("click.then", "req.exec").build());
        try (ScriptRig rig = new ScriptRig(d, UiDocumentSource.EMPTY, h.host, UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            rig.click("go");
            assertEquals(1, sites.size(), sites.toString());
            assertEquals(CallSite.Origin.GRAPH, sites.getFirst().origin());
            assertEquals("g#req", sites.getFirst().elementKey(), "the graph node that made the call");
        }
    }

    // ── options ─────────────────────────────────────────────────────────────

    @Test
    void productionOptionsSeedHashingRandomlyAndDoNotTrustDerivedGraphs() {
        UiScriptOptions host = new UiScriptOptions(4L << 20, 50, 0, false);
        assertTrue(host.randomHashSeed());
        assertFalse(host.trustDerivedGraphs());
        assertFalse(UiScriptOptions.DEFAULTS.randomHashSeed(), "fixtures keep a fixed seed");
        try (ScriptRig rig = new ScriptRig(doc("""
            local t = { a = 1, b = 2, c = 3 }
            local n = 0 for _ in pairs(t) do n = n + 1 end
            ui.log("n=" .. n)
            """), UiDocumentSource.EMPTY, null, host, UiScriptServices.NONE)) {
            assertTrue(rig.log().contains("n=3"), rig.log().toString());
        }
    }
}
