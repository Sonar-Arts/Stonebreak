package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.data.UiActionException;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.binding.UiActivation;
import com.openmason.engine.ui.runtime.binding.UiBinder;
import com.openmason.engine.ui.runtime.binding.UiConverter;
import com.openmason.engine.ui.runtime.binding.UiConverters;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.config.Settings;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game's UI host (#289): pause and furnace pilots on live cells, actions, settings edits, preview equivalence. */
class GameUiHostTest {

    /** Records what the game was asked to do instead of doing it. */
    private static final class FakeServices implements GameUiHost.Services {
        final List<String> calls = new ArrayList<>();
        UiValue.Obj settings = SettingsContract.read(Settings.defaults());

        @Override public void resume() { calls.add("resume"); }
        @Override public void openStatistics() { calls.add("statistics"); }
        @Override public void openGlossary() { calls.add("glossary"); }
        @Override public void openSettings() { calls.add("settings"); }
        @Override public void quitToMenu() { calls.add("quit"); }
        @Override public int resync() { calls.add("resync"); return 42; }
        @Override public UiValue.Obj settings() { return settings; }
        @Override public void applySettings(UiValue.Obj value) {
            calls.add("apply");
            Map<String, UiValue> merged = new java.util.LinkedHashMap<>(settings.fields());
            value.fields().forEach((k, v) -> {
                if (!(v instanceof UiValue.Null)) {
                    merged.put(k, v);
                }
            });
            settings = new UiValue.Obj(merged);
        }
    }

    private static final UiConverters DISPLAY_IF = UiConverters.of(Map.of("display_if",
        UiConverter.of(DataType.string(), v -> UiValue.of(v instanceof UiValue.Bool b && b.value() ? "flex" : "none"))));

    private static UiNode node(String id, String type, String dataSource, List<UiNode.UiBinding> bindings,
                               List<UiNode> children) {
        return new UiNode(id, null, type, 1, List.of(), Map.of(), Map.of(), dataSource, bindings, null, children, Map.of());
    }

    /** Pause-shaped: root reads session; resync shows only online; a bar follows furnace progress. */
    private static OmuiArchive pauseLike() {
        UiNode resync = node("resync", "Button", null,
            List.of(new UiNode.UiBinding("style:display", ".online", UiNode.BindingMode.TO_TARGET, "display_if", Map.of())),
            List.of());
        UiNode bar = node("bar", "Box", null, List.of(new UiNode.UiBinding("style:opacity", "furnace.progress")), List.of());
        UiNode root = node("root", "Box", "session", List.of(), List.of(resync, bar));
        UiManifest m = UiManifest.create("stonebreak:ui/pause_menu", UiManifest.DocumentKind.SCREEN, "Pause");
        m = new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), m.displayName(), m.uiApi(), m.layoutSemantics(),
            m.requires(), List.of(
                new UiManifest.HostRequirement("stonebreak:furnace", 1, false, Map.of()),
                new UiManifest.HostRequirement("stonebreak:network.resync", 1, true, Map.of()),
                new UiManifest.HostRequirement("stonebreak:screen.pause", 1, false, Map.of()),
                new UiManifest.HostRequirement("stonebreak:session", 1, false, Map.of())),
            m.providers(), m.unknown());
        return OmuiArchive.of(m, new UiDocument(root, List.of(), null, null, Map.of()));
    }

    private static String display(UiDocumentInstance ui) {
        ui.resolveStyles();
        return ui.find("resync").computedStyle().keyword("display", "flex");
    }

    @Test
    void pauseOnlineStateFollowsTheSessionWithoutPolling() {
        GameUiHost game = new GameUiHost(new FakeServices(), MultiplayerSession.Mode.SINGLEPLAYER);
        OmuiArchive doc = pauseLike();
        assertTrue(UiActivation.require(doc, null, game.host()).isEmpty(), "the game offers every pause contract");
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, game.host(), DISPLAY_IF)) {
            assertEquals("none", display(ui));
            game.sessionChanged(MultiplayerSession.Mode.HOST); // the session thread posts
            assertEquals("none", display(ui));
            game.drain();
            assertEquals("flex", display(ui));
        }
    }

    @Test
    void previewFixturesAndTheGameHostAgree() {
        OmuiArchive doc = pauseLike();
        FixtureHost preview = FixtureHost.of(new UiValue.Obj(Map.of(
            "session", new UiValue.Obj(Map.of("mode", UiValue.of("join"), "online", UiValue.TRUE, "hosting", UiValue.FALSE)),
            "furnace", new UiValue.Obj(Map.of("open", UiValue.TRUE, "lit", UiValue.TRUE, "progress", UiValue.of(0.5),
                "fuel", UiValue.of(1))),
            "contracts", new UiValue.Obj(Map.of("session", UiValue.of("stonebreak:session"),
                "furnace", UiValue.of("stonebreak:furnace"))))), doc.manifest());
        GameUiHost game = new GameUiHost(new FakeServices(), MultiplayerSession.Mode.JOIN);
        FurnaceState furnace = new FurnaceState(new BlockPos(0, 0, 0));
        furnace.applyStateString("furnace:state=Lit;burn=100;burnTotal=100;cook="
            + com.stonebreak.crafting.SmeltingManager.TICKS_PER_SMELT / 2);
        game.furnaceOpened(furnace);
        assertTrue(UiActivation.check(doc, null, preview.host()).stream().noneMatch(d -> d.isError()));
        UiDocumentInstance a = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        UiDocumentInstance c = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder pa = UiBinder.open(a, preview.host(), DISPLAY_IF); UiBinder pc = UiBinder.open(c, game.host(), DISPLAY_IF)) {
            assertEquals(snapshot(a), snapshot(c));
        }
    }

    private static List<String> snapshot(UiDocumentInstance ui) {
        ui.resolveStyles();
        List<String> out = new ArrayList<>();
        for (UiElement e : ui.elements()) {
            out.add(e.key() + " " + e.computedStyle().values());
        }
        return out;
    }

    @Test
    void furnaceProgressArrivesByNotificationFromAnyThread() throws Exception {
        GameUiHost game = new GameUiHost(new FakeServices(), MultiplayerSession.Mode.SINGLEPLAYER);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(pauseLike(), UiRuntimeContext.basic());
        FurnaceState furnace = new FurnaceState(new BlockPos(1, 2, 3));
        try (UiBinder b = UiBinder.open(ui, game.host(), DISPLAY_IF)) {
            game.furnaceOpened(furnace);
            Thread echo = new Thread(() -> furnace.applyStateString("furnace:state=Lit;burn=50;burnTotal=100;cook=10"));
            echo.start();
            echo.join();
            game.drain();
            ui.resolveStyles();
            double expected = furnace.getCookProgressRatio();
            assertTrue(expected > 0);
            assertEquals(expected, ui.find("bar").computedStyle().number("opacity", -1), 1e-6);
            game.furnaceClosed();
            furnace.applyStateString("furnace:state=Lit;burn=50;burnTotal=100;cook=20");
            game.drain();
            ui.resolveStyles();
            assertEquals(0, ui.find("bar").computedStyle().number("opacity", -1), 1e-6, "closed: no longer mirrored");
        }
    }

    @Test
    void leavingTheWorldCancelsPendingWork() {
        FakeServices services = new FakeServices();
        GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.HOST);
        UiScope scope = game.host().openScope("stonebreak:ui/pause_menu", null, p -> { });
        long epoch = game.host().epoch();
        game.sessionChanged(MultiplayerSession.Mode.MENU);
        game.drain();
        assertEquals(epoch + 1, game.host().epoch());
        assertEquals(DataPath.parse("session.online").evaluate(game.sessionCell().value()), UiValue.FALSE);
        scope.close();
    }

    @Test
    void pauseActionsRunTheGameRulesAndCheckTheirContract() {
        FakeServices services = new FakeServices();
        GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.JOIN);
        UiScope scope = game.host().openScope("stonebreak:ui/pause_menu",
            Set.of("stonebreak:screen.pause", "stonebreak:network.resync"), p -> { });
        CallSite site = scope.site("resync", CallSite.Origin.SCRIPT);
        ActionCall resync = scope.invoke("stonebreak:network.resync", null, site);
        assertEquals(new UiValue.Obj(Map.of("audited", UiValue.of(42))), resync.result());
        scope.invoke("stonebreak:screen.pause.resume", null, site);
        assertEquals(List.of("resync", "resume"), services.calls);
        UiActionException e = assertThrows(UiActionException.class, () -> scope.invoke("stonebreak:screen.pause.quit",
            new UiValue.Obj(Map.of("now", UiValue.TRUE)), site));
        assertTrue(e.getMessage().contains("document stonebreak:ui/pause_menu, node resync"), e.getMessage());
        assertEquals(UiActionException.Code.CAPABILITY_MISSING, assertThrows(UiActionException.class,
            () -> scope.invoke("stonebreak:settings.apply", null, site)).code());
        scope.close();
    }

    @Test
    void settingsEditsValidateBeforeTheyApply() {
        FakeServices services = new FakeServices();
        GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.SINGLEPLAYER);
        UiScope scope = game.host().openScope("stonebreak:ui/settings", Set.of("stonebreak:settings"), p -> { });
        CallSite site = scope.site("scale", CallSite.Origin.BINDING);
        assertTrue(!scope.edits().stage(DataPath.parse("settings.uiScale"), UiValue.of(5), site).accepted());
        assertTrue(scope.edits().stage(DataPath.parse("settings.uiScale"), UiValue.of(1.5), site).accepted());
        assertTrue(services.calls.isEmpty(), "staging never saves");
        ActionCall apply = scope.edits().apply("settings", site);
        assertEquals(ActionCall.State.SUCCEEDED, apply.state());
        assertEquals(List.of("apply"), services.calls);
        assertEquals(UiValue.of(1.5), services.settings.get("uiScale"));
        assertTrue(!scope.edits().isDirty());
        scope.close();
    }
}
