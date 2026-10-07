package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.UiScope;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The screen actions the #299 migrations added to {@link GameUiHost}: each one runs the legacy
 * screen's own rule through its service and stays authoritative (a refused call fails, it is
 * never reported as done).
 */
class ScreenActionsTest {

    private static final class Services implements GameUiHost.Services {
        boolean dead;
        int respawns;
        boolean statisticsOpen;
        int statisticsClosed;
        com.stonebreak.ui.runtime.contracts.StatsRecord stats = com.stonebreak.ui.runtime.contracts.StatsRecord.NONE;
        com.stonebreak.ui.glossaryScreen.GlossaryScreen glossary;

        @Override
        public com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen() {
            return glossary;
        }

        @Override public void resume() { }
        @Override public void openStatistics() { }
        @Override public void openGlossary() { }
        @Override public void openSettings() { }
        @Override public void quitToMenu() { }
        @Override public int resync() { return 0; }
        @Override public UiValue.Obj settings() { return SettingsContract.read(com.stonebreak.config.Settings.defaults()); }
        @Override public void applySettings(UiValue.Obj value) { }

        @Override
        public com.stonebreak.ui.runtime.contracts.StatsRecord stats() {
            return stats;
        }

        @Override
        public String closeStatistics() {
            if (!statisticsOpen) {
                return "no statistics screen is showing";
            }
            statisticsOpen = false;
            statisticsClosed++;
            return null;
        }

        @Override
        public String respawn() {
            if (!dead) {
                return "no death menu is showing";
            }
            respawns++;
            dead = false;
            return null;
        }
    }

    private final Services services = new Services();
    private final GameUiHost game = new GameUiHost(services, MultiplayerSession.Mode.SINGLEPLAYER);
    private final UiScope scope = game.host().openScope("stonebreak:ui/screens/death", null, p -> { });
    private final CallSite site = scope.site("respawn", CallSite.Origin.SCRIPT);

    @Test
    void respawnRunsOnlyWhileTheDeathMenuIsUp() {
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:screen.death.respawn", null, site).state(),
            "a living player is never respawned by a script");
        services.dead = true;
        assertEquals(ActionCall.State.SUCCEEDED, scope.invoke("stonebreak:screen.death.respawn", null, site).state());
        assertEquals(1, services.respawns);
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:screen.death.respawn", null, site).state(),
            "a second click after the respawn does nothing");
        assertEquals(1, services.respawns);
    }

    @Test
    void theDeclarationHostRefusesRespawn() {
        GameUiHost declared = GameUiHost.declaration();
        UiScope s = declared.host().openScope("stonebreak:ui/screens/death", null, p -> { });
        assertEquals(ActionCall.State.FAILED,
            s.invoke("stonebreak:screen.death.respawn", null, s.site("respawn", CallSite.Origin.SCRIPT)).state());
    }

    @Test
    void statisticsBackClosesOnlyAnOpenScreen() {
        CallSite back = scope.site("back", CallSite.Origin.SCRIPT);
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:screen.statistics.back", null, back).state());
        services.statisticsOpen = true;
        assertEquals(ActionCall.State.SUCCEEDED, scope.invoke("stonebreak:screen.statistics.back", null, back).state());
        assertEquals(1, services.statisticsClosed);
    }

    @Test
    void statsArePublishedWithTheirLegacyText() {
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.US);
        try {
            var s = new com.stonebreak.player.PlayerStats();
            s.restore(1234, 4567.25, 15432.5, 9876.4, 4321.0, 512.75, 3725.0);
            services.stats = com.stonebreak.ui.runtime.contracts.StatsRecord.of(s);
            game.drain();
            UiValue.Obj root = (UiValue.Obj) game.host().data().root("stats").source().state().valueOrNull();
            UiValue.Obj text = (UiValue.Obj) root.get("text");
            assertEquals(UiValue.of("1,234"), text.get("entitiesKilled"));
            assertEquals(UiValue.of("15.43 km"), text.get("totalDistance"));
            assertEquals(UiValue.of("512.8 m"), text.get("distanceInAir"));
            assertEquals(UiValue.of("1h 2m 5s"), text.get("timeInAir"));
            assertEquals(UiValue.of(1234), root.get("entitiesKilled"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    private com.stonebreak.ui.glossaryScreen.GlossaryScreen openGlossary() {
        var g = new com.stonebreak.ui.glossaryScreen.GlossaryScreen(null);
        var d = new com.stonebreak.player.EntityDiscoveries();
        d.recordVariantSeen(com.stonebreak.mobs.entities.EntityType.COW, "Default");
        d.recordVariantSeen(com.stonebreak.mobs.entities.EntityType.COW, "Highland");
        var stats = new com.stonebreak.player.PlayerStats();
        stats.restoreKillsByType(java.util.Map.of(com.stonebreak.mobs.entities.EntityType.COW, 2L));
        g.setDataSource(() -> d, () -> stats);
        g.setVisible(true);
        services.glossary = g;
        return g;
    }

    private UiValue.Obj glossaryRoot() {
        return (UiValue.Obj) game.host().data().root("glossary").source().state().valueOrNull();
    }

    @Test
    void theGlossaryIsPublishedOnlyWhileOpenAndItsActionsChangeTheGamesSelection() {
        CallSite site = scope.site("glossary", CallSite.Origin.SCRIPT);
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:screen.glossary.select",
            new UiValue.Obj(java.util.Map.of("index", UiValue.of(1))), site).state(), "closed");
        var g = openGlossary();
        game.drain();
        UiValue.Obj root = glossaryRoot();
        assertEquals(UiValue.of("Cow"), root.get("name"));
        assertEquals(UiValue.of("2 defeated"), root.get("badge"));
        assertEquals(UiValue.of("Default  1/2"), root.get("chip"));
        assertEquals(UiValue.of("1 / 4 observed"), root.get("observedText"));

        assertEquals(ActionCall.State.SUCCEEDED, scope.invoke("stonebreak:screen.glossary.cycle",
            new UiValue.Obj(java.util.Map.of("delta", UiValue.of(1))), site).state());
        assertEquals(UiValue.of("Highland  2/2"), glossaryRoot().get("chip"), "published before the next frame");

        assertEquals(ActionCall.State.SUCCEEDED, scope.invoke("stonebreak:screen.glossary.select",
            new UiValue.Obj(java.util.Map.of("index", UiValue.of(1))), site).state());
        assertEquals(1, g.getSelectedEntityIndex());
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:screen.glossary.cycle",
            new UiValue.Obj(java.util.Map.of("delta", UiValue.of(1))), site).state(), "the sheep has no second variant");
        assertEquals(ActionCall.State.FAILED, scope.invoke("stonebreak:screen.glossary.select",
            new UiValue.Obj(java.util.Map.of("index", UiValue.of(9))), site).state(), "no such row");
        assertEquals(1, g.getSelectedEntityIndex());
    }
}
