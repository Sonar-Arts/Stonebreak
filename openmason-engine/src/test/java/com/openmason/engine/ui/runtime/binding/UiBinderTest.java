package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiActionException;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.l10n.MessageCatalog;
import com.openmason.engine.ui.l10n.UiLocalizer;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.UiTexts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.contract;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.param;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.binding.BindingRig.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Declarative bindings against host data: the pause and furnace pilots, inheritance, states, lifetime (#289). */
class UiBinderTest {

    private static final HostContract SESSION = HostContract.of("stonebreak:session", 1);

    /** A game-like host: live cells registered by host code (here: the test). */
    private static UiHost gameHost(DataCell session) {
        UiHost host = new UiHost();
        host.data().register("session", session, SESSION);
        host.offer(HostContract.of("stonebreak:screen.pause", 1));
        return host;
    }

    private static DataCell sessionCell(boolean online) {
        return new DataCell(DataType.object("online", DataType.bool()), obj("online", online));
    }

    private static String display(UiDocumentInstance ui, String key) {
        ui.resolveStyles();
        return ui.find(key).computedStyle().keyword("display", "flex");
    }

    // ── acceptance: pause online state, one API for preview and game ─────────

    @Test
    void pauseOnlineStateFollowsNotifications() {
        DataCell session = sessionCell(false);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(BindingRig.pause(), BindingRig.pauseContext());
        try (UiBinder binder = UiBinder.open(ui, gameHost(session), BindingRig.PAUSE_CODE_BEHIND)) {
            assertEquals("none", display(ui, "resync"), "offline: the resync button collapses");
            session.set(obj("online", true));
            assertEquals("flex", display(ui, "resync"), "a notification, not a poll, shows it");
            assertEquals(BindingStatus.State.ACTIVE, binder.status("resync", "style:display").state());
            assertEquals("Resync", ui.find("resync/label").text("text"), "static component params still bind");
            assertEquals(1, session.subscriberCount());
        }
        assertEquals(0, session.subscriberCount(), "closing the binder releases the host subscription");
    }

    @Test
    void previewFixtureAndGameHostGiveTheSameTree() {
        OmuiArchive pause = BindingRig.pause();
        FixtureHost preview = FixtureHost.forArchive(pause); // editor/fixtures.json: {"session": {"online": true}}
        DataCell live = sessionCell(true);
        UiDocumentInstance a = UiDocumentInstance.instantiate(pause, BindingRig.pauseContext());
        UiDocumentInstance b = UiDocumentInstance.instantiate(pause, BindingRig.pauseContext());
        try (UiBinder pa = UiBinder.open(a, preview.host(), BindingRig.PAUSE_CODE_BEHIND);
             UiBinder pb = UiBinder.open(b, gameHost(live), BindingRig.PAUSE_CODE_BEHIND)) {
            assertEquals(snapshot(a), snapshot(b));
            preview.cell("session").set(obj("online", false));
            live.set(obj("online", false));
            assertEquals(snapshot(a), snapshot(b));
            assertEquals("none", display(a, "resync"));
        }
    }

    private static List<String> snapshot(UiDocumentInstance ui) {
        ui.resolveStyles();
        List<String> out = new ArrayList<>();
        for (UiElement e : ui.elements()) {
            out.add(e.key() + " " + e.computedStyle().keyword("display", "flex") + " " + e.text("text"));
        }
        return out;
    }

    // ── acceptance: furnace progress with localized formatting ──────────────

    @Test
    void furnaceProgressBindsWidthAndLocalizedText() {
        UiLocalizer l10n = UiLocalizer.english();
        l10n.addCatalog(MessageCatalog.of(Locale.ENGLISH, Map.of("furnace.progress", "Smelting {progress, number, percent}")));
        UiRuntimeContext ctx = UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT).withLocalizer(l10n);
        OmuiArchive doc = BindingRig.withData(screen("t:ui/furnace", box("root").data("furnace").kids(
            box("bar").bind("style:width", ".progress", UiNode.BindingMode.TO_TARGET, "percent"),
            label("pct", "").prop("textKey", "furnace.progress").bind("prop:textArgs", "."))), "stonebreak:furnace@1");
        UiHost host = new UiHost();
        DataCell furnace = host.data().register("furnace",
            new DataCell(DataType.object("progress", DataType.number(), "lit", DataType.bool()), obj("progress", 0, "lit", false)),
            HostContract.of("stonebreak:furnace", 1));
        UiConverters percent = UiConverters.of(Map.of("percent", UiConverter.of(DataType.string(),
            v -> UiValue.of(Math.round(((UiValue.Num) v).value() * 100) + "%"))));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, ctx);
        try (UiBinder binder = UiBinder.open(ui, host, percent)) {
            furnace.post(obj("progress", 0.25, "lit", true)); // from the server thread's side
            ui.resolveStyles();
            assertEquals(UiValue.of("0%"), ui.find("bar").computedStyle().get("width"), "posted, not yet drained");
            host.drain();
            ui.resolveStyles();
            assertEquals(UiValue.of("25%"), ui.find("bar").computedStyle().get("width"));
            assertEquals("Smelting 25%", UiTexts.label(ui.find("pct")));
            furnace.set(obj("progress", 0.5, "lit", true));
            assertEquals("Smelting 50%", UiTexts.label(ui.find("pct")));
            assertTrue(ui.diagnostics().isEmpty(), ui.diagnostics().toString());
        }
    }

    // ── inheritance ─────────────────────────────────────────────────────────

    @Test
    void relativePathsResolveAgainstTheNearestSource() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").data("world").kids(
            box("player").data(".player").kids(label("name", "?").bind("prop:text", ".name")),
            label("seed", "?").bind("prop:text", ".seedText"),
            label("abs", "?").bind("prop:text", "world.player.name"))), "t:world@1");
        UiHost host = new UiHost();
        DataCell world = host.data().register("world", new DataCell(DataType.ANY,
            obj("seedText", "42", "player", obj("name", "Steve"))), HostContract.of("t:world", 1));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            assertEquals("Steve", ui.find("name").text("text"));
            assertEquals("42", ui.find("seed").text("text"));
            assertEquals("Steve", ui.find("abs").text("text"));
            world.set(obj("seedText", "7", "player", obj("name", "Alex")));
            assertEquals("Alex", ui.find("name").text("text"));
            assertEquals("Alex", ui.find("abs").text("text"));
        }
    }

    @Test
    void instanceParamBindingsFeedTheComponentAndSlotsKeepTheirAuthorsSource() {
        OmuiArchive button = component("t:ui/button", box("frame").kids(label("label", "?").bind("prop:text", ".label"),
            box("slot_host")), contract(List.of(param("label", ValueType.STRING, "Button")), List.of(UiDocs.slot("icon", "slot_host"))));
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").data("session").kids(
            inst("b", "t:ui/button", Map.of(), List.of(), Map.of("icon", List.of(label("slotted", "?").bind("prop:text", ".user"))))
                .bind("prop:label", ".user"))), "stonebreak:session@1");
        UiHost host = new UiHost();
        DataCell s = host.data().register("session", new DataCell(DataType.object("user", DataType.string()),
            obj("user", "Steve")), SESSION);
        UiRuntimeContext ctx = UiRuntimeContext.basic().withSource(
            com.openmason.engine.ui.runtime.UiDocumentSource.of(Map.of("t:ui/button", button), Map.of()));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, ctx);
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            assertEquals("Steve", ui.find("b/label").text("text"), "param bound live into the component");
            assertEquals("Steve", ui.find("slotted").text("text"), "slot content reads its author's source");
            s.set(obj("user", "Alex"));
            assertEquals("Alex", ui.find("b/label").text("text"));
            assertEquals("Alex", ui.find("slotted").text("text"));
        }
    }

    // ── null / missing / loading / error and defaults ───────────────────────

    @Test
    void nonReadyStatesFallBackToTheAuthoredValue() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").kids(label("name", "unknown").bind("prop:text", "session.name"),
            label("ghost", "none").bind("prop:text", "nope.name"))), "stonebreak:session@1");
        UiHost host = new UiHost();
        DataCell s = host.data().register("session", new DataCell(DataType.object("name", DataType.string().orNull()),
            DataState.LOADING), SESSION);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            assertEquals("unknown", ui.find("name").text("text"));
            assertEquals(BindingStatus.State.LOADING, b.status("name", "prop:text").state());
            s.set(obj("name", "Steve"));
            assertEquals("Steve", ui.find("name").text("text"));
            s.set(obj("name", UiValue.NULL));
            assertEquals("unknown", ui.find("name").text("text"));
            assertEquals(BindingStatus.State.NULL, b.status("name", "prop:text").state());
            s.fail("server unreachable");
            assertEquals("unknown", ui.find("name").text("text"));
            assertEquals(BindingStatus.State.FAILED, b.status("name", "prop:text").state());
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.BINDING_SOURCE_FAILED));
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.UNKNOWN_DATA_SOURCE), "unknown root 'nope'");
            assertEquals(BindingStatus.State.MISSING, b.status("ghost", "prop:text").state());
        }
    }

    @Test
    void typeMismatchesAndMissingConvertersAreDiagnostics() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").kids(
            label("count", "0").bind("prop:text", "stats.count"),
            box("c").bind("class:online", "stats.count"),
            box("d").bind("style:display", "stats.count", UiNode.BindingMode.TO_TARGET, "no_such"))), "t:stats@1");
        UiHost host = new UiHost();
        host.data().register("stats", new DataCell(DataType.object("count", DataType.integer()), obj("count", 3)),
            HostContract.of("t:stats", 1));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            assertEquals("0", ui.find("count").text("text"));
            assertEquals(BindingStatus.State.INVALID, b.status("count", "prop:text").state());
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.BINDING_TYPE));
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.MISSING_CONVERTER));
            assertFalse(ui.find("c").hasClass("online"));
        }
    }

    @Test
    void onceAppliesTheFirstValueOnly() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").kids(
            label("l", "?").bind("prop:text", "session.user", UiNode.BindingMode.ONCE, null))), "stonebreak:session@1");
        UiHost host = new UiHost();
        DataCell s = host.data().register("session", new DataCell(DataType.object("user", DataType.string()),
            obj("user", "Steve")), SESSION);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            s.set(obj("user", "Alex"));
            assertEquals("Steve", ui.find("l").text("text"));
            assertEquals(0, s.subscriberCount());
        }
    }

    @Test
    void toTargetBindingsStillOwnTheirTargets() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").kids(label("l", "?").bind("prop:text", "session.user"))),
            "stonebreak:session@1");
        UiHost host = new UiHost();
        host.data().register("session", new DataCell(DataType.object("user", DataType.string()), obj("user", "Steve")), SESSION);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            assertFalse(ui.find("l").setProp("text", UiValue.of("hacked")));
            assertEquals("Steve", ui.find("l").text("text"));
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.BOUND_PROPERTY_WRITE));
        }
    }

    @Test
    void bindingsToUndeclaredRootsAreRefusedWithADiagnostic() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").kids(
            label("ok", "?").bind("prop:text", "session.user"),
            label("hp", "hidden").bind("prop:text", "vitals.health"))), "stonebreak:session@1");
        UiHost host = new UiHost();
        host.data().register("session", new DataCell(DataType.object("user", DataType.string()), obj("user", "Steve")), SESSION);
        DataCell vitals = host.data().register("vitals", new DataCell(DataType.object("health", DataType.integer()),
            obj("health", 20)), HostContract.of("stonebreak:player.vitals", 1));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            assertEquals("Steve", ui.find("ok").text("text"));
            assertEquals("hidden", ui.find("hp").text("text"), "the authored value; host data never reaches the element (#327)");
            assertEquals(BindingStatus.State.FAILED, b.status("hp", "prop:text").state());
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.CAPABILITY_MISSING));
            assertEquals(0, vitals.subscriberCount());
            vitals.set(obj("health", 3));
            assertEquals("hidden", ui.find("hp").text("text"));
        }
    }

    // ── one contract for bindings, scripts and graphs ───────────────────────

    @Test
    void scriptsGraphsAndBindingsShareOneContract() {
        OmuiArchive doc = UiDocs.declare(screen("t:ui/pause", box("root").kids(
            label("who", "?").bind("prop:text", "session.user"))), List.of(), "stonebreak:session@1");
        UiHost host = new UiHost();
        DataCell s = host.data().register("session", new DataCell(DataType.object("user", DataType.string()),
            obj("user", "Steve")), SESSION);
        host.actions().register(ActionSpec.of("stonebreak:session.kick", SESSION,
            DataType.object("player", DataType.string()), DataType.bool()), (args, c) -> CompletableFuture.completedFuture(UiValue.TRUE));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            UiScope scope = b.scope();
            // what Lua code-behind (#292) and a compiled graph (#291) call: the same scope
            List<UiValue> scriptSaw = new ArrayList<>();
            scope.watch(DataPath.parse("session.user"), st -> scriptSaw.add(st.valueOrNull()));
            s.set(obj("user", "Alex"));
            assertEquals(List.of(UiValue.of("Alex")), scriptSaw);
            assertEquals(UiValue.of(ui.find("who").text("text")), scope.read(DataPath.parse("session.user")).valueOrNull());
            for (CallSite.Origin origin : List.of(CallSite.Origin.SCRIPT, CallSite.Origin.GRAPH, CallSite.Origin.BINDING)) {
                CallSite site = scope.site("who", origin);
                UiActionException e = assertThrows(UiActionException.class,
                    () -> scope.invoke("stonebreak:session.kick", obj("player", 3), site));
                assertTrue(e.getMessage().contains("document t:ui/pause, node who"), e.getMessage());
                assertEquals(ActionCall.State.SUCCEEDED, scope.invoke("stonebreak:session.kick", obj("player", "x"), site).state());
            }
        }
    }

    // ── acceptance: unbinding releases everything, stale completions rejected ─

    @Test
    void closingReleasesSubscriptionsAndRejectsLateResults() {
        OmuiArchive doc = UiDocs.declare(screen("t:ui/pause", box("root").data("session").kids(
            label("a", "?").bind("prop:text", ".user"), label("b", "?").bind("prop:text", "session.user"))),
            List.of(), "stonebreak:session@1");
        UiHost host = new UiHost();
        DataCell s = host.data().register("session", new DataCell(DataType.object("user", DataType.string()),
            obj("user", "Steve")), SESSION);
        CompletableFuture<UiValue> slow = new CompletableFuture<>();
        host.actions().register(ActionSpec.of("stonebreak:session.refresh", SESSION, null, DataType.ANY), (a, c) -> slow);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        UiBinder b = UiBinder.open(ui, host, UiConverters.NONE);
        UiScope scope = b.scope();
        List<ActionCall.State> callbacks = new ArrayList<>();
        scope.invoke("stonebreak:session.refresh", null, scope.site("a", CallSite.Origin.SCRIPT))
            .whenSettled(c -> callbacks.add(c.state()));
        assertTrue(scope.subscriptionCount() > 0);
        b.close();
        assertEquals(0, scope.subscriptionCount());
        assertEquals(0, s.subscriberCount());
        assertTrue(scope.isClosed());
        assertTrue(host.openScopes().isEmpty());
        slow.complete(UiValue.TRUE);
        host.drain();
        assertEquals(List.of(ActionCall.State.CANCELLED), callbacks, "the old screen never sees the late result");
        assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.STALE_COMPLETION));
        s.set(obj("user", "Alex"));
        assertEquals("Steve", ui.find("a").text("text"), "nothing updates a closed screen");
        UiBinder again = UiBinder.open(ui, host, UiConverters.NONE);
        assertEquals("Alex", ui.find("a").text("text"), "the instance can be bound again");
        again.close();
    }

    @Test
    void reloadRebindsAndDropsTheOldGenerationsWork() {
        OmuiArchive doc = UiDocs.declare(screen("t:ui/pause", box("root").kids(label("a", "?").bind("prop:text", "session.user"))),
            List.of(), "stonebreak:session@1");
        UiHost host = new UiHost();
        DataCell s = host.data().register("session", new DataCell(DataType.object("user", DataType.string()),
            obj("user", "Steve")), SESSION);
        CompletableFuture<UiValue> slow = new CompletableFuture<>();
        host.actions().register(ActionSpec.of("stonebreak:session.refresh", SESSION, null, DataType.ANY), (a, c) -> slow);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            ActionCall old = b.scope().invoke("stonebreak:session.refresh", null, b.scope().site("a", CallSite.Origin.SCRIPT));
            ui.reload();
            assertEquals(ActionCall.State.CANCELLED, old.state());
            assertEquals(2, b.scope().generation());
            assertEquals("Steve", ui.find("a").text("text"));
            s.set(obj("user", "Alex"));
            assertEquals("Alex", ui.find("a").text("text"), "the rebuilt element is bound");
            assertEquals(1, s.subscriberCount(), "the old elements' subscriptions are gone");
        }
    }

    @Test
    void runtimeInsertionsAndRemovalsBindAndRelease() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").data("session")), "stonebreak:session@1");
        UiHost host = new UiHost();
        DataCell s = host.data().register("session", new DataCell(DataType.object("user", DataType.string()),
            obj("user", "Steve")), SESSION);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            UiElement added = ui.root().insertChild(-1, label("dyn", "?").bind("prop:text", ".user").build());
            assertEquals("Steve", added.text("text"));
            assertEquals(1, b.scope().subscriptionCount());
            added.remove();
            assertEquals(0, b.scope().subscriptionCount());
        }
    }

    @Test
    void secondBinderIsRefused() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(screen("t:ui/x", box("root")), UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, new UiHost(), UiConverters.NONE)) {
            assertThrows(IllegalStateException.class, () -> UiBinder.open(ui, new UiHost(), UiConverters.NONE));
        }
    }
}
