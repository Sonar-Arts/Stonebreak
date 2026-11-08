package com.stonebreak.ui.mainMenu.showcase;

import com.stonebreak.battle.camera.CameraFrame;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.weather.WeatherMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main menu's live backdrop (Just Cause 3 style): a quiet {@code SHOWCASE} session — an
 * integrated server on an ephemeral world of the pinned seed ({@link ShowcaseConfig}) — flown by a
 * {@link ShowcaseDirector} drone camera that dips to black between areas while the world simulates.
 *
 * <p>Lifecycle: started on entering MAIN_MENU ({@link #onMenuEntered}); kept alive under the other
 * shell screens (world select, multiplayer, settings) so coming back is instant; ended by any real
 * session (Play, Host, Join all shut the running session down first) or by turning the setting off.
 * It only advances and draws in MAIN_MENU. A failed boot is not retried until the setting changes
 * or the game restarts; the menu shows its static backdrop instead.
 *
 * <p>Main thread only. Dev: {@code -Dstonebreak.showcase=off}, {@code -Dstonebreak.showcase.shot=<i>}
 * (loop one shot), {@code -Dstonebreak.showcase.seed=<n>} (scout another seed with the same shots).
 */
public final class MenuShowcase {

    private static final Logger logger = LoggerFactory.getLogger(MenuShowcase.class);
    private static final MenuShowcase INSTANCE = new MenuShowcase();

    /** Chunk radius around the eye that must be drawn before a shot fades in (capped by render distance). */
    private static final int READY_RADIUS = 6;
    private static final float READY_FRACTION = 0.9f;
    /** Pending mesh builds tolerated when fading in (the far edge may still be meshing). */
    private static final int READY_PENDING_MESHES = 24;
    /** Blocks the camera keeps above the ground / the water along every shot. */
    private static final float GROUND_CLEARANCE = 5f;
    private static final float WATER_CLEARANCE = 3f;

    private ShowcaseConfig config;
    private boolean configTried;
    private boolean failed;
    private boolean started;
    private ShowcaseDirector director;
    private boolean listening;
    /** When the menu came up waiting on the showcase (System.nanoTime), or 0 when not waiting. */
    private long waitStartNanos;
    /** The menu has been revealed for this showcase run (sticky: it never hides again). */
    private boolean revealed;

    /**
     * The longest the menu waits, black, for the showcase world before showing anyway (a slow boot
     * must never hold the buttons hostage).
     */
    static final float MENU_WAIT_SECONDS = 10f;

    private MenuShowcase() {
    }

    public static MenuShowcase get() {
        return INSTANCE;
    }

    /** Entering MAIN_MENU: start the showcase if it should run and nothing is running. */
    public void onMenuEntered() {
        ensureListening();
        if (!enabled() || failed || MultiplayerSession.hasSession()) {
            return;
        }
        ShowcaseConfig cfg = config();
        if (cfg == null) {
            return;
        }
        long seed = Long.getLong("stonebreak.showcase.seed", cfg.seed());
        FlyoverShot first = cfg.shots().get(Math.max(0, Math.min(cfg.shots().size() - 1, pinnedShot())));
        Long time = first.timeTicks();
        try {
            revealed = false;
            waitStartNanos = System.nanoTime();
            MultiplayerSession.startShowcase(seed, first.eyeFrom(), time != null ? time : 6000L,
                    WorldStorage.menuShowcaseDir().resolve("seed-" + seed));
            started = true;
            director = null;
        } catch (RuntimeException e) {
            fail("could not start", e);
        }
    }

    /**
     * One MAIN_MENU frame: ends the showcase if it was switched off, notices a failed boot, and flies
     * the camera. True when the showcase world should advance this frame (meshing, uploads, clocks).
     */
    public boolean tick(float dt) {
        if (!enabled()) {
            stop();
            failed = false; // switching it back on retries a failed showcase
            return false;
        }
        if (MultiplayerSession.consumeShowcaseFailure()) {
            fail("the showcase session failed to boot", null);
            return false;
        }
        if (!Game.isShowcaseWorldReady()) {
            director = null;
            if (!started && !failed && !MultiplayerSession.hasSession()) {
                onMenuEntered(); // the setting was switched back on, or the menu came up some other way
            }
            return false;
        }
        if (director == null) {
            ShowcaseConfig cfg = config();
            if (cfg == null) {
                return false;
            }
            director = new ShowcaseDirector(cfg.shots(), new WorldStage(), cfg.seed() ^ System.nanoTime(), pinnedShot());
        }
        director.tick(dt * TIME_SCALE);
        return true;
    }

    /** Dev: {@code -Dstonebreak.showcase.timescale} speeds the camera up (reviewing a long orbit). */
    private static final float TIME_SCALE = Float.parseFloat(System.getProperty("stonebreak.showcase.timescale", "1"));

    /**
     * How visible the menu itself is, 0..1. It waits (black) while the showcase world loads, then
     * fades in with the world's first reveal — or after {@link #MENU_WAIT_SECONDS} regardless. With
     * no showcase (off, failed) it is shown at once. Once shown it stays shown.
     */
    public float menuReveal() {
        if (revealed || staticBackdrop() || waitStartNanos == 0L) {
            return 1f;
        }
        float waited = (System.nanoTime() - waitStartNanos) / 1e9f;
        float r = isRenderable() ? 1f - director.fade() : 0f;
        if (waited >= MENU_WAIT_SECONDS) {
            r = Math.max(r, Math.min(1f, (waited - MENU_WAIT_SECONDS) / ShowcaseDirector.FADE_IN_SECONDS));
        }
        if (r >= 1f) {
            revealed = true;
            waitStartNanos = 0L;
        }
        return Math.max(0f, Math.min(1f, r));
    }

    private final java.util.Set<Integer> captured = new java.util.HashSet<>();
    private static final int LOOP_CAPTURES = 8;

    /**
     * Dev: {@code -Dstonebreak.showcase.capture=<dir>} writes {@code shot-<i>.png} once per shot,
     * 55 % of the way through it, then exits once every shot has one (a contact sheet for picking
     * and tuning shots). Main thread, after the frame is drawn and before the buffer swap.
     */
    public void afterFrame(int width, int height) {
        String dir = System.getProperty("stonebreak.showcase.capture");
        if (dir == null || dir.isBlank() || !isRenderable() || director.phase() != ShowcaseDirector.Phase.PLAY) {
            return;
        }
        int shot = director.currentShot();
        ShowcaseConfig cfg = config();
        FlyoverShot flyover = cfg.shots().get(shot);
        int key;
        String name;
        if (flyover.loop()) {
            // A looping take: eight frames around its first lap.
            int k = (int) (director.shotTime() / (flyover.duration() / LOOP_CAPTURES));
            if (k >= LOOP_CAPTURES) {
                logger.info("[showcase] loop captured; exiting");
                System.exit(0);
            }
            key = shot * 100 + k;
            name = "shot-" + shot + "-" + k + ".png";
        } else {
            if (director.shotTime() < flyover.duration() * 0.55f) {
                return;
            }
            key = shot * 100;
            name = "shot-" + shot + ".png";
        }
        if (captured.contains(key)) {
            return;
        }
        String file = java.nio.file.Path.of(dir, name).toString();
        if (com.stonebreak.core.dev.DevScreenshot.capture(file, width, height)) {
            captured.add(key);
            logger.info("[showcase] captured {} ({}) -> {}", name, flyover.caption(), file);
        }
        if (!flyover.loop() && (captured.size() >= cfg.shots().size()
                || (pinnedShot() >= 0 && captured.contains(pinnedShot() * 100)))) {
            logger.info("[showcase] every shot captured; exiting");
            System.exit(0);
        }
    }

    /** Ends a running showcase (the setting was switched off). */
    public void stop() {
        if (MultiplayerSession.isShowcase()) {
            MultiplayerSession.shutdown();
        }
        started = false;
        director = null;
    }

    /** The world is ready and the camera placed: MAIN_MENU draws the world behind its document. */
    public boolean isRenderable() {
        return Game.isShowcaseWorldReady() && director != null && director.frame() != null;
    }

    /** How black the backdrop is, 0..1 (fully black until the first shot fades in). */
    public float fade() {
        return isRenderable() ? director.fade() : 1f;
    }

    /**
     * How far the menu's load has come, 0..1, for the "Loading main menu" bar: the session booting
     * (to 0.2), the world installed (0.25), then the share of the area around the camera drawn, until
     * the reveal. 1 once revealed.
     */
    public float loadProgress() {
        if (revealed || staticBackdrop()) {
            return 1f;
        }
        if (!Game.isShowcaseWorldReady()) {
            if (!started || waitStartNanos == 0L) {
                return 0f;
            }
            float waited = (System.nanoTime() - waitStartNanos) / 1e9f;
            return 0.2f * Math.min(1f, waited / 3f); // the boot has no milestones of its own
        }
        if (director == null || director.frame() == null) {
            return 0.25f;
        }
        if (director.phase() != ShowcaseDirector.Phase.MOVE && director.phase() != ShowcaseDirector.Phase.WAIT) {
            return 1f;
        }
        World world = Game.getWorld();
        if (world == null) {
            return 0.25f;
        }
        CameraFrame frame = director.frame();
        int radius = Math.max(2, Math.min(READY_RADIUS, world.getConfig().getRenderDistance()));
        int cx = Math.floorDiv((int) Math.floor(frame.eye().x), WorldConfiguration.CHUNK_SIZE);
        int cz = Math.floorDiv((int) Math.floor(frame.eye().z), WorldConfiguration.CHUNK_SIZE);
        float drawn = Math.min(1f, world.areaDrawnFraction(cx, cz, radius) / READY_FRACTION);
        return 0.25f + 0.7f * drawn;
    }

    /** The playing shot's caption ("" while black or off). */
    public String caption() {
        return isRenderable() ? director.caption() : "";
    }

    /** True when the menu should show its static backdrop instead (switched off, or the showcase failed). */
    public boolean staticBackdrop() {
        return !enabled() || failed || (configTried && config == null);
    }

    /** The pinned seed (for the menu's seed line), or 0 when none is loaded. */
    public long seed() {
        ShowcaseConfig cfg = config();
        return cfg == null ? 0L : Long.getLong("stonebreak.showcase.seed", cfg.seed());
    }

    private boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("stonebreak.showcase"))
                && Settings.getInstance().getLiveMenuBackground();
    }

    private static int pinnedShot() {
        return Integer.getInteger("stonebreak.showcase.shot", -1);
    }

    private ShowcaseConfig config() {
        if (!configTried) {
            configTried = true;
            try {
                String file = System.getProperty("stonebreak.showcase.file");
                if (file != null && !file.isBlank()) {
                    try (java.io.InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(file))) {
                        config = ShowcaseConfig.parse(in);
                    }
                    logger.info("[showcase] shots from {} (seed {})", file, config.seed());
                } else {
                    config = ShowcaseConfig.load();
                }
            } catch (Exception e) {
                fail("could not read " + ShowcaseConfig.RESOURCE, e);
            }
        }
        return config;
    }

    private void fail(String what, Exception e) {
        failed = true;
        started = false;
        director = null;
        if (e != null) {
            logger.warn("[showcase] {} — the main menu shows its static backdrop", what, e);
        } else {
            logger.warn("[showcase] {} — the main menu shows its static backdrop", what);
        }
    }

    /** Leaving the showcase (any way): give the renderer its gameplay field of view back. */
    private void ensureListening() {
        if (listening) {
            return;
        }
        listening = true;
        MultiplayerSession.addModeListener(new java.util.function.Consumer<>() {
            private boolean wasShowcase;

            @Override
            public void accept(MultiplayerSession.Mode mode) {
                boolean showcase = mode == MultiplayerSession.Mode.SHOWCASE;
                if (wasShowcase && !showcase) {
                    Game game = Game.getInstance();
                    if (game != null) {
                        game.runOnMainThread(() -> {
                            started = false;
                            director = null;
                            Renderer renderer = Game.getRenderer();
                            if (renderer != null) {
                                renderer.resetFov();
                            }
                            Player player = Game.getPlayer();
                            if (player != null) {
                                player.getCamera().clearCinematicView();
                            }
                        });
                    }
                }
                wasShowcase = showcase;
            }
        });
    }

    /** The director's stage: the showcase world installed in the game's world/player slots. */
    private static final class WorldStage implements ShowcaseDirector.Stage {

        @Override
        public void moveCamera(CameraFrame frame) {
            Player player = Game.getPlayer();
            if (player == null) {
                return;
            }
            // The eye IS the player: the server streams, and the world/LOD/sky passes centre, on the
            // player's position. Physics never runs on it (MAIN_MENU skips player.update()).
            player.setPosition(frame.eye());
            player.setVelocity(new org.joml.Vector3f());
            player.getCamera().setCinematicView(frame.eye(), frame.target(), frame.rollDeg());
            Renderer renderer = Game.getRenderer();
            if (renderer != null) {
                renderer.setCinematicFov(frame.fovDeg());
            }
        }

        @Override
        public boolean areaReady(CameraFrame frame) {
            World world = Game.getWorld();
            if (world == null) {
                return false;
            }
            int radius = Math.max(2, Math.min(READY_RADIUS, world.getConfig().getRenderDistance()));
            int cx = Math.floorDiv((int) Math.floor(frame.eye().x), WorldConfiguration.CHUNK_SIZE);
            int cz = Math.floorDiv((int) Math.floor(frame.eye().z), WorldConfiguration.CHUNK_SIZE);
            if (world.areaDrawnFraction(cx, cz, radius) < READY_FRACTION
                    || world.getPendingMeshBuildCount() > READY_PENDING_MESHES) {
                return false;
            }
            // And what the camera looks at, when that is within the detail range at all.
            int tx = Math.floorDiv((int) Math.floor(frame.target().x), WorldConfiguration.CHUNK_SIZE);
            int tz = Math.floorDiv((int) Math.floor(frame.target().z), WorldConfiguration.CHUNK_SIZE);
            int reach = world.getConfig().getRenderDistance() - 2;
            if ((tx - cx) * (tx - cx) + (tz - cz) * (tz - cz) > reach * reach) {
                return true;
            }
            return world.areaDrawnFraction(tx, tz, 2) >= READY_FRACTION;
        }

        @Override
        public void setConditions(Long timeTicks, String weather) {
            if (timeTicks != null) {
                MultiplayerSession.requestServerTimeSet(timeTicks);
                if (Game.getTimeOfDay() != null) {
                    Game.getTimeOfDay().setTicks(timeTicks);
                }
            }
            if (weather != null) {
                WeatherMode mode = WeatherMode.parse(weather);
                if (mode != null) {
                    MultiplayerSession.requestServerWeatherSet(mode, true);
                }
            }
        }

        @Override
        public float liftFor(FlyoverShot shot) {
            World world = Game.getWorld();
            TerrainGenerator terrain = world != null ? world.terrain() : null;
            if (terrain == null) {
                return 0f;
            }
            return shot.liftAbove(new FlyoverShot.GroundProbe() {
                @Override
                public int groundHeight(int x, int z) {
                    return terrain.getFinalTerrainHeightAt(x, z);
                }

                @Override
                public int waterLevel(int x, int z) {
                    return terrain.getWaterLevelAt(x, z);
                }
            }, GROUND_CLEARANCE, WATER_CLEARANCE);
        }
    }
}
