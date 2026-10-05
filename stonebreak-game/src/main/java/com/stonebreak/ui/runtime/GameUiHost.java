package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionHandler;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.EditPolicy;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.PauseMenuActions;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Stonebreak's implementation of the UI host contract (#289): the live data sources and game
 * actions every migrated screen (and the {@code -Dstonebreak.uidoc} overlay) binds to. The
 * editor preview offers the same contracts from fixtures ({@code FixtureHost}), so a document
 * behaves the same in both.
 *
 * <table>
 *   <caption>Contracts</caption>
 *   <tr><th>Contract</th><th>Offers</th></tr>
 *   <tr><td>{@code stonebreak:session} 1</td><td>root {@code session}: {@code mode}, {@code online},
 *       {@code hosting}; follows {@link MultiplayerSession} mode changes</td></tr>
 *   <tr><td>{@code stonebreak:furnace} 1</td><td>root {@code furnace}: the open furnace's {@code open},
 *       {@code lit}, {@code progress} and {@code fuel} (0–1)</td></tr>
 *   <tr><td>{@code stonebreak:settings} 1</td><td>editable root {@code settings}, applied by
 *       {@code stonebreak:settings.apply}</td></tr>
 *   <tr><td>{@code stonebreak:screen.pause} 1</td><td>{@code stonebreak:screen.pause.resume},
 *       {@code .statistics}, {@code .glossary}, {@code .settings}, {@code .quit}</td></tr>
 *   <tr><td>{@code stonebreak:network.resync} 1</td><td>{@code stonebreak:network.resync} →
 *       {@code {audited}}</td></tr>
 * </table>
 *
 * <p>Producers on other threads (the integrated server ticking furnaces, network handlers) post
 * into the cells; the frame loop {@link #drain}s once per frame. Leaving a world or disconnecting
 * advances the host epoch, which cancels every pending action of every open screen.
 */
public final class GameUiHost {

    public static final HostContract SESSION = HostContract.of("stonebreak:session", 1);
    public static final HostContract FURNACE = HostContract.of("stonebreak:furnace", 1);
    public static final HostContract SETTINGS = HostContract.of("stonebreak:settings", 1);
    public static final HostContract PAUSE = HostContract.of("stonebreak:screen.pause", 1);
    public static final HostContract RESYNC = HostContract.of("stonebreak:network.resync", 1);

    static final DataType.Obj SESSION_TYPE = DataType.object("mode", DataType.string(), "online", DataType.bool(),
        "hosting", DataType.bool());
    static final DataType.Obj FURNACE_TYPE = DataType.object("open", DataType.bool(), "lit", DataType.bool(),
        "progress", DataType.number(), "fuel", DataType.number());
    static final DataType.Obj SETTINGS_TYPE = DataType.object("uiScale", DataType.number(),
        "uiTextScale", DataType.number(), "reducedMotion", DataType.bool(), "renderDistance", DataType.integer(),
        "maxFps", DataType.integer());

    /** The game behind the host. Tests substitute it; nothing here reaches game singletons directly. */
    public interface Services {
        void resume();

        void openStatistics();

        void openGlossary();

        void openSettings();

        void quitToMenu();

        /** @return chunks audited, or -1 when not connected */
        int resync();

        UiValue.Obj settings();

        /** Applies and saves an already validated settings snapshot. */
        void applySettings(UiValue.Obj value);
    }

    private static volatile GameUiHost instance;

    private final UiHost host = new UiHost();
    private final Services services;
    private final DataCell session;
    private final DataCell furnace;
    private final DataCell settings;
    private FurnaceState openFurnace;

    public GameUiHost(Services services, MultiplayerSession.Mode mode) {
        this.services = services;
        session = host.data().register("session", new DataCell(SESSION_TYPE, sessionValue(mode)), SESSION);
        furnace = host.data().register("furnace", new DataCell(FURNACE_TYPE, closedFurnace()), FURNACE);
        settings = host.data().registerEditable("settings", new DataCell(SETTINGS_TYPE, services.settings()), SETTINGS,
            new EditPolicy("stonebreak:settings.apply", GameUiHost::settingsProblem));
        registerActions();
    }

    /** The game's host, created on first use (the first frame). */
    public static GameUiHost get() {
        GameUiHost h = instance;
        if (h == null) {
            synchronized (GameUiHost.class) {
                h = instance;
                if (h == null) {
                    h = new GameUiHost(new GameServices(), MultiplayerSession.getMode());
                    GameUiHost created = h;
                    MultiplayerSession.addModeListener(created::sessionChanged);
                    Settings.getInstance().addSaveListener(created::settingsSaved);
                    instance = h;
                }
            }
        }
        return h;
    }

    /** Runs {@code action} with the host if it exists; game code paths that run headless in tests use this. */
    public static void ifPresent(Consumer<GameUiHost> action) {
        GameUiHost h = instance;
        if (h != null) {
            action.accept(h);
        }
    }

    public UiHost host() {
        return host;
    }

    public int drain() {
        return host.drain();
    }

    // ── sources ─────────────────────────────────────────────────────────────

    /** Any thread. Leaving to the menu also ends the world epoch: pending actions are cancelled. */
    public void sessionChanged(MultiplayerSession.Mode mode) {
        session.post(sessionValue(mode));
        if (mode == MultiplayerSession.Mode.MENU) {
            host.queue().post(() -> host.advanceEpoch("left world"));
        }
    }

    /** UI thread: the furnace UI opened on {@code state}; its changes are mirrored until {@link #furnaceClosed}. */
    public void furnaceOpened(FurnaceState state) {
        if (openFurnace != null) {
            openFurnace.setChangeListener(null);
        }
        openFurnace = state;
        state.setChangeListener(() -> furnace.post(furnaceValue(state)));
        furnace.set(furnaceValue(state));
    }

    public void furnaceClosed() {
        if (openFurnace != null) {
            openFurnace.setChangeListener(null);
            openFurnace = null;
        }
        furnace.post(closedFurnace());
    }

    public void settingsSaved() {
        settings.post(services.settings());
    }

    DataCell furnaceCell() {
        return furnace;
    }

    DataCell sessionCell() {
        return session;
    }

    static UiValue.Obj sessionValue(MultiplayerSession.Mode mode) {
        return new UiValue.Obj(Map.of("mode", UiValue.of(mode.name().toLowerCase(java.util.Locale.ROOT)),
            "online", UiValue.of(mode == MultiplayerSession.Mode.HOST || mode == MultiplayerSession.Mode.JOIN),
            "hosting", UiValue.of(mode == MultiplayerSession.Mode.HOST)));
    }

    static UiValue.Obj furnaceValue(FurnaceState s) {
        return new UiValue.Obj(Map.of("open", UiValue.TRUE, "lit", UiValue.of(s.isLit()),
            "progress", UiValue.of(Math.clamp(s.getCookProgressRatio(), 0f, 1f)),
            "fuel", UiValue.of(s.getFuelRatio())));
    }

    private static UiValue.Obj closedFurnace() {
        return new UiValue.Obj(Map.of("open", UiValue.FALSE, "lit", UiValue.FALSE, "progress", UiValue.of(0),
            "fuel", UiValue.of(0)));
    }

    /** The same ranges the settings setters clamp to, refused up front instead of silently clamped. */
    static String settingsProblem(DataPath path, UiValue value, UiValue draft) {
        double v = value instanceof UiValue.Num n ? n.value() : 0;
        return switch (path.toString()) {
            case "settings.uiScale" -> v < 0.5 || v > 2.0 ? "UI scale must be between 0.5 and 2" : null;
            case "settings.uiTextScale" -> v < 0.5 || v > 3.0 ? "text scale must be between 0.5 and 3" : null;
            case "settings.renderDistance" -> v < WorldConfiguration.MIN_RENDER_DISTANCE
                || v > WorldConfiguration.MAX_RENDER_DISTANCE
                ? "render distance must be " + WorldConfiguration.MIN_RENDER_DISTANCE + "-"
                    + WorldConfiguration.MAX_RENDER_DISTANCE : null;
            case "settings.maxFps" -> v < Settings.MIN_MAX_FPS || v > Settings.MAX_MAX_FPS
                ? "max FPS must be " + Settings.MIN_MAX_FPS + "-" + Settings.MAX_MAX_FPS : null;
            default -> null;
        };
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private void registerActions() {
        pause("resume", services::resume);
        pause("statistics", services::openStatistics);
        pause("glossary", services::openGlossary);
        pause("settings", services::openSettings);
        pause("quit", services::quitToMenu);
        host.actions().register(ActionSpec.of("stonebreak:network.resync", RESYNC, null,
            DataType.object("audited", DataType.integer())), (args, ctx) -> done(new UiValue.Obj(
                Map.of("audited", UiValue.of(services.resync())))));
        host.actions().register(ActionSpec.of("stonebreak:settings.apply", SETTINGS,
            DataType.object("value", SETTINGS_TYPE), DataType.ANY), (args, ctx) -> {
                UiValue.Obj value = (UiValue.Obj) args.get("value");
                for (Map.Entry<String, UiValue> f : value.fields().entrySet()) {
                    String problem = settingsProblem(DataPath.parse("settings." + f.getKey()), f.getValue(), value);
                    if (problem != null) {
                        return CompletableFuture.failedFuture(new IllegalArgumentException(problem));
                    }
                }
                services.applySettings(value);
                settings.set(services.settings());
                return done(UiValue.NULL);
            });
    }

    private void pause(String name, Runnable run) {
        ActionHandler h = (args, ctx) -> {
            run.run();
            return done(UiValue.NULL);
        };
        host.actions().register(ActionSpec.of("stonebreak:screen.pause." + name, PAUSE, null, DataType.ANY), h);
    }

    private static CompletableFuture<UiValue> done(UiValue v) {
        return CompletableFuture.completedFuture(v);
    }

    /** The live game. */
    private static final class GameServices implements Services {
        @Override
        public void resume() {
            PauseMenuActions.resume(Game.getInstance());
        }

        @Override
        public void openStatistics() {
            PauseMenuActions.openStatistics(Game.getInstance());
        }

        @Override
        public void openGlossary() {
            PauseMenuActions.openGlossary(Game.getInstance());
        }

        @Override
        public void openSettings() {
            PauseMenuActions.openSettings(Game.getInstance());
        }

        @Override
        public void quitToMenu() {
            PauseMenuActions.quitToMenu(Game.getInstance());
        }

        @Override
        public int resync() {
            return PauseMenuActions.resync(Game.getInstance());
        }

        @Override
        public UiValue.Obj settings() {
            Settings s = Settings.getInstance();
            return new UiValue.Obj(Map.of("uiScale", UiValue.of(s.getUiScale()),
                "uiTextScale", UiValue.of(s.getUiTextScale()), "reducedMotion", UiValue.of(s.isReducedMotion()),
                "renderDistance", UiValue.of(s.getRenderDistance()), "maxFps", UiValue.of(s.getMaxFps())));
        }

        @Override
        public void applySettings(UiValue.Obj value) {
            Settings s = Settings.getInstance();
            s.setUiScale((float) num(value, "uiScale"));
            s.setUiTextScale((float) num(value, "uiTextScale"));
            s.setReducedMotion(value.get("reducedMotion") instanceof UiValue.Bool b && b.value());
            s.setRenderDistance((int) num(value, "renderDistance"));
            s.setMaxFps((int) num(value, "maxFps"));
            s.saveSettings();
        }

        private static double num(UiValue.Obj o, String k) {
            return o.get(k) instanceof UiValue.Num n ? n.value() : 0;
        }
    }
}
