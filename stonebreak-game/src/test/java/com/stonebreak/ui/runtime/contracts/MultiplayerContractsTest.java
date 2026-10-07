package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.UiScope;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.multiplayerMenu.HostWorldScreen;
import com.stonebreak.ui.multiplayerMenu.JoinWorldScreen;
import com.stonebreak.ui.runtime.GameUiHost;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The multiplayer contracts (#299) run the legacy screens' own form rules: the host publishes their
 * state, and the actions' refusals are the legacy status lines. Only inputs those rules refuse are
 * used here, so no session is ever started.
 */
class MultiplayerContractsTest {

    private static final class Services implements GameUiHost.Services {
        HostWorldScreen host = new HostWorldScreen(null);
        JoinWorldScreen join = new JoinWorldScreen(null);

        @Override public void resume() { }
        @Override public void openStatistics() { }
        @Override public void openGlossary() { }
        @Override public void openSettings() { }
        @Override public void quitToMenu() { }
        @Override public int resync() { return 0; }
        @Override public UiValue.Obj settings() { return SettingsContract.read(com.stonebreak.config.Settings.defaults()); }
        @Override public void applySettings(UiValue.Obj value) { }
        @Override public HostWorldScreen hostWorld() { return host; }
        @Override public String hostSelect(int index) { return host.selectWorld(index) ? null : "no row"; }
        @Override public String hostStart(String port) { return host.startHosting(port); }
        @Override public JoinWorldScreen joinWorld() { return join; }
        @Override public String joinConnect(String h, String p, String u) { return join.connect(h, p, u); }
    }

    private final Services services = new Services();
    private final GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.MENU);
    private final UiScope scope = game.host().openScope("stonebreak:ui/screens/test", null, p -> { });
    private final CallSite site = scope.site("t", CallSite.Origin.SCRIPT);

    private UiValue.Obj root(String name) {
        return (UiValue.Obj) game.host().data().root(name).source().state().valueOrNull();
    }

    @Test
    void theHostFormPublishesItsWorldsAndRefusesBadPortsWithTheLegacyStatus() {
        services.host.setWorldSource(() -> List.of("A", "B", "C", "D", "E", "F", "G", "H", "I"));
        services.host.onShow();
        game.drain();
        assertEquals(8, ((UiValue.Arr) root("hostWorld").get("worlds")).items().size(), "the first eight, as legacy");

        assertEquals(ActionCall.State.SUCCEEDED, scope.invoke("stonebreak:screen.host-world.select",
            new UiValue.Obj(Map.of("index", UiValue.of(3))), site).state());
        assertEquals(UiValue.of(3), root("hostWorld").get("selected"));
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:screen.host-world.select",
            new UiValue.Obj(Map.of("index", UiValue.of(8))), site).state(), "the ninth world has no row");

        ActionCall start = scope.invoke("stonebreak:screen.host-world.start",
            new UiValue.Obj(Map.of("port", UiValue.of("70000"))), site);
        assertEquals(ActionCall.State.SUCCEEDED, start.state());
        assertEquals(UiValue.of("Invalid port (1-65535)."), ((UiValue.Obj) start.result()).get("status"));
        assertEquals(UiValue.of("Invalid port (1-65535)."), root("hostWorld").get("status"), "published at once");
    }

    @Test
    void theJoinFormValidatesAndCutsWhatWasTyped() {
        ActionCall c = scope.invoke("stonebreak:screen.join-world.connect", new UiValue.Obj(Map.of(
            "host", UiValue.of(" "), "port", UiValue.of("12a34"), "username", UiValue.of("x".repeat(40)))), site);
        assertEquals(UiValue.of("Host required."), ((UiValue.Obj) c.result()).get("status"));
        assertEquals("1234", services.join.portText(), "digits only, as the legacy field accepts them");
        assertEquals(24, services.join.userText().length(), "the legacy 24-character username");
        game.drain();
        assertEquals(UiValue.of("Host required."), root("joinWorld").get("status"));
    }
}
