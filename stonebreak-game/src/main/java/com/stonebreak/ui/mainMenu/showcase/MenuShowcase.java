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
 * The main menu's live backdrop, one of two kinds:
 * <ul>
 *   <li>the <b>preview</b> of the player's last-played world (a {@code PREVIEW} session: the saved
 *       world loaded but held — nothing simulates, no clock moves, nothing is saved), its camera
 *       slowly orbiting the player; <b>Resume</b> ({@link #resume}) slides the menu off, flies the
 *       camera into the player's eye ({@link ResumeTransition}) and promotes the session to
 *       singleplayer in place;</li>
 *   <li>with no saved world, the Just Cause 3 style <b>showcase</b>: a quiet {@code SHOWCASE}
 *       session — an integrated server on an ephemeral world of the pinned seed
 *       ({@link ShowcaseConfig}) — flown by a {@link ShowcaseDirector} drone camera.</li>
 * </ul>
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

    /** One revolution of the preview's orbit around the player, seconds. */
    static final float PREVIEW_ORBIT_SECONDS = 120f;
    /**
     * How far the preview's camera stays from the player's chest (straight line, blocks) and how
     * steeply it looks down on them: far back and up, so the shot takes in the land around them.
     */
    static final float PREVIEW_ORBIT_DISTANCE = 45f;
    static final float PREVIEW_ORBIT_PITCH_DEG = 35f;
    /** The orbit's horizontal radius and height above the look-at point, from the two above. */
    static final float PREVIEW_ORBIT_RADIUS =
            (float) (PREVIEW_ORBIT_DISTANCE * Math.cos(Math.toRadians(PREVIEW_ORBIT_PITCH_DEG)));
    static final float PREVIEW_ORBIT_HEIGHT =
            (float) (PREVIEW_ORBIT_DISTANCE * Math.sin(Math.toRadians(PREVIEW_ORBIT_PITCH_DEG)));
    /** The preview looks at the player's chest, this far above the feet. */
    static final float PREVIEW_LOOK_HEIGHT = 1.1f;
    /** The orbit starts this far round from the player's facing, so the menu opens on their face. */
    static final float PREVIEW_START_BEARING_DEG = 35f;
    static final float PREVIEW_FOV = 60f;
    /** The gameplay field of view Resume flies into ({@code Renderer.resetFov}'s). */
    private static final float GAMEPLAY_FOV =
            com.openmason.engine.rendering.gl.RenderingConfigurationManager.DEFAULT_FOV_DEGREES;

    /** The last-played saved world, or null for none (looked up on entering the menu). */
    private LastWorld lastWorld;
    /** The world the running (or booting) preview session shows, or null when none does. */
    private String previewWorld;
    /** A preview failed to boot: the menu falls back to the showcase until the game restarts. */
    private boolean previewFailed;
    private PreviewStage previewStage;
    /** The Resume move in progress, or null. */
    private ResumeTransition resume;
    /** Finds the last-played world (one instance: it owns a background stats thread). */
    private com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager discovery;

    /**
     * A saved world the menu can resume. {@code previewable}: it may also be loaded behind the
     * menu. A world whose generator needs the terrain model (DaedalusTGM-Exp) is not: booting it
     * starts TGMPipe and generates spawn through the model, so the menu shows the seed showcase
     * instead and the model only starts when the player presses RESUME (a full load).
     */
    record LastWorld(String name, long seed, boolean previewable) {
    }

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

    /**
     * Entering MAIN_MENU: find the last-played world, then start its preview — or, with none, the
     * showcase — if it should run and nothing is running.
     */
    public void onMenuEntered() {
        ensureListening();
        resume = null;
        lastWorld = findLastWorld();
        if (!enabled() || failed || MultiplayerSession.hasSession()) {
            return;
        }
        if (lastWorld != null && lastWorld.previewable() && !previewFailed) {
            try {
                revealed = false;
                waitStartNanos = System.nanoTime();
                previewWorld = lastWorld.name();
                MultiplayerSession.startPreview(lastWorld.name(), lastWorld.seed());
                started = true;
                director = null;
            } catch (RuntimeException e) {
                previewWorld = null;
                fail("could not start the preview of '" + lastWorld.name() + "'", e);
            }
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
            if (previewWorld != null) {
                // A world that will not open behind the menu: show the seed showcase instead (the
                // world select still offers it, and reports why it will not load).
                logger.warn("[showcase] the preview of '{}' failed to boot — showing the showcase", previewWorld);
                previewFailed = true;
                previewWorld = null;
                started = false;
                director = null;
                return false;
            }
            fail("the showcase session failed to boot", null);
            return false;
        }
        if (resume != null) {
            resume.tick(dt);
            applyFrame(resume.frame());
            if (resume.finished()) {
                finishResume();
            }
            return true;
        }
        if (!Game.isBackdropWorldReady()) {
            director = null;
            if (!started && !failed && !MultiplayerSession.hasSession()) {
                onMenuEntered(); // the setting was switched back on, or the menu came up some other way
            }
            return false;
        }
        if (director == null) {
            director = MultiplayerSession.isPreview() ? previewDirector() : showcaseDirector();
            if (director == null) {
                return false;
            }
        }
        director.tick(dt * TIME_SCALE);
        return true;
    }

    private ShowcaseDirector showcaseDirector() {
        ShowcaseConfig cfg = config();
        if (cfg == null) {
            return null;
        }
        return new ShowcaseDirector(cfg.shots(), new WorldStage(), cfg.seed() ^ System.nanoTime(), pinnedShot());
    }

    /** One endless slow orbit around the player, where they stand in their saved world. */
    private ShowcaseDirector previewDirector() {
        Player player = Game.getPlayer();
        if (player == null) {
            return null;
        }
        previewStage = new PreviewStage();
        FlyoverShot orbit = previewOrbit(player.getPosition(), player.getCamera().getYaw());
        return new ShowcaseDirector(java.util.List.of(orbit), previewStage, 0L, 0);
    }

    /**
     * The preview's shot: a 360° looping orbit around the chest of a player standing at
     * {@code feet}, starting {@link #PREVIEW_START_BEARING_DEG} round from where they face
     * ({@code yawDeg}, {@code Camera}'s convention), so the menu opens on their face.
     */
    static FlyoverShot previewOrbit(org.joml.Vector3f feet, float yawDeg) {
        org.joml.Vector3f look = new org.joml.Vector3f(feet).add(0f, PREVIEW_LOOK_HEIGHT, 0f);
        double bearing = Math.toRadians(yawDeg + PREVIEW_START_BEARING_DEG);
        org.joml.Vector3f eye = new org.joml.Vector3f(look).add(
                (float) Math.cos(bearing) * PREVIEW_ORBIT_RADIUS,
                PREVIEW_ORBIT_HEIGHT,
                (float) Math.sin(bearing) * PREVIEW_ORBIT_RADIUS);
        return new FlyoverShot("", FlyoverShot.Motion.ORBIT, eye, eye, look, look, 360f,
                PREVIEW_FOV, PREVIEW_FOV, 0f, 0f, PREVIEW_ORBIT_SECONDS,
                com.openmason.engine.util.easing.EasingType.Linear, null, null, true);
    }

    // ─── Resume ────────────────────────────────────────────────────────────────

    /** There is a saved world to resume: the menu shows its RESUME entry. */
    public boolean canResume() {
        return lastWorld != null;
    }

    /** The Resume move is running (the menu ignores further clicks). */
    public boolean isResuming() {
        return resume != null;
    }

    /** How far the menu has slid off for Resume, 0..1 (0 when not resuming). */
    public float menuExit() {
        return resume != null ? resume.menuExit() : 0f;
    }

    /**
     * The RESUME entry. With the last world's preview on screen: slide the menu off and fly the
     * camera into the player's eye, then play it in place ({@link #finishResume}). Otherwise (the
     * live backdrop is off, still loading, or failed): load the world the ordinary way.
     */
    public void resume() {
        if (resume != null || lastWorld == null) {
            return;
        }
        Player player = Game.getPlayer();
        CameraFrame from = previewStage != null ? previewStage.lastFrame() : null;
        if (MultiplayerSession.isPreview() && lastWorld.name().equals(previewWorld) && isRenderable()
                && player != null && from != null) {
            // Into the first-person eye whatever perspective they saved in (play resumes from there).
            resume = new ResumeTransition(from,
                    ResumeTransition.firstPerson(player.getPosition(),
                            com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET,
                            player.getCamera().getYaw(), player.getCamera().getPitch(), GAMEPLAY_FOV),
                    ResumeTransition.DEFAULT_SECONDS);
            revealed = true; // the menu is going, not coming
            logger.info("[showcase] resuming '{}'", lastWorld.name());
            return;
        }
        logger.info("[showcase] resuming '{}' with a full load (no preview on screen)", lastWorld.name());
        MultiplayerSession.startSingleplayer(lastWorld.name(), lastWorld.seed());
    }

    /** The camera is in the player's eye: the preview becomes the world being played. */
    private void finishResume() {
        resume = null;
        Game game = Game.getInstance();
        var weather = Game.getWeather();
        double weatherSeconds = weather != null ? weather.seconds() : Double.NaN;
        String name = previewWorld;
        if (!MultiplayerSession.promotePreview(weatherSeconds) || lastWorld == null) {
            logger.warn("[showcase] the preview ended before Resume finished — loading the world instead");
            if (lastWorld != null) {
                MultiplayerSession.startSingleplayer(lastWorld.name(), lastWorld.seed());
            }
            return;
        }
        previewWorld = null;
        started = false;
        director = null;
        previewStage = null;
        game.setCurrentWorldName(name);
        game.setCurrentWorldSeed(lastWorld.seed());
        if (Game.getTimeOfDay() != null) {
            Game.getTimeOfDay().setFrozen(false); // the server's next sync restores a frozen world's flag
        }
        Player player = Game.getPlayer();
        if (player != null) {
            player.getCamera().clearCinematicView();
            if (player.isThirdPerson()) {
                player.togglePerspective(); // the camera arrived in the eye: play from there
            }
        }
        Renderer renderer = Game.getRenderer();
        if (renderer != null) {
            renderer.resetFov();
        }
        game.setState(com.stonebreak.core.GameState.PLAYING);
    }

    /** Puts the scripted camera at {@code frame} (the view only: the player stays where they stand). */
    private static void applyFrame(CameraFrame frame) {
        Player player = Game.getPlayer();
        if (player == null) {
            return;
        }
        player.getCamera().setCinematicView(frame.eye(), frame.target(), frame.rollDeg());
        Renderer renderer = Game.getRenderer();
        if (renderer != null) {
            renderer.setCinematicFov(frame.fovDeg());
        }
    }

    /**
     * The previewed world is about to change on disk (deleted from the world select): end the
     * preview first, unsaved, so nothing holds its folder open.
     */
    public void releaseWorld(String worldName) {
        if (worldName != null && worldName.equals(previewWorld) && MultiplayerSession.isPreview()) {
            stop();
        }
    }

    private LastWorld findLastWorld() {
        try {
            if (discovery == null) {
                discovery = new com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager();
            }
            discovery.clearCache(); // a world just played must sort by the save it just made
            java.util.List<String> worlds = discovery.discoverWorlds();
            if (worlds.isEmpty()) {
                return null;
            }
            String name = worlds.getFirst();
            var data = discovery.getWorldData(name);
            if (data == null || data.getSeed() == 0) {
                return null;
            }
            var generator = data.getGeneratorType();
            return new LastWorld(name, data.getSeed(), generator == null || !generator.needsModelService());
        } catch (RuntimeException e) {
            logger.warn("[showcase] could not look up the last-played world", e);
            return null;
        }
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
        float r = isRenderable() && director != null ? 1f - director.fade() : 0f;
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

    /** Ends a running backdrop session, unsaved (the setting was switched off). */
    public void stop() {
        if (MultiplayerSession.isMenuBackdrop()) {
            MultiplayerSession.shutdown();
        }
        started = false;
        director = null;
        previewWorld = null;
        previewStage = null;
        resume = null;
    }

    /** The world is ready and the camera placed: MAIN_MENU draws the world behind its document. */
    public boolean isRenderable() {
        return Game.isBackdropWorldReady()
                && (resume != null || (director != null && director.frame() != null));
    }

    /** How black the backdrop is, 0..1 (fully black until the first shot fades in). */
    public float fade() {
        if (resume != null) {
            return 0f;
        }
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
        if (!Game.isBackdropWorldReady()) {
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
        return isRenderable() && director != null ? director.caption() : "";
    }

    /** True when the menu should show its static backdrop instead (switched off, or the showcase failed). */
    public boolean staticBackdrop() {
        return !enabled() || failed || (configTried && config == null);
    }

    /** The menu's corner line: the showcase's seed, or nothing over the preview of a saved world. */
    public String seedLine() {
        return previewWorld != null ? "" : "Seed " + seed();
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
                boolean showcase = mode == MultiplayerSession.Mode.SHOWCASE
                        || mode == MultiplayerSession.Mode.PREVIEW;
                if (wasShowcase && !showcase) {
                    Game game = Game.getInstance();
                    if (game != null) {
                        game.runOnMainThread(() -> {
                            started = false;
                            director = null;
                            previewStage = null;
                            previewWorld = null;
                            resume = null; // a session that ended under the move leaves nothing to resume into
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
            return MenuShowcase.areaReady(frame);
        }

        @Override
        public void setConditions(Long timeTicks, String weather) {
            setShotConditions(timeTicks, weather);
        }

        @Override
        public float liftFor(FlyoverShot shot) {
            return liftOverTerrain(shot);
        }
    }

    /**
     * The preview's stage: the player stands where they saved and the camera orbits them. Only the
     * view moves (the streaming centre is the player, who never moves); the clock and weather are
     * never set; and the eye is pulled in short of any solid block (not leaves) between it and the player.
     */
    private static final class PreviewStage implements ShowcaseDirector.Stage {

        /** Minimum eye distance from the player, and how far short of a blocking block it stops. */
        private static final float MIN_DISTANCE = 0.5f;
        private static final float WALL_BACKOFF = 0.3f;
        /** How fast a pulled-in eye may glide back out, blocks per frame (in is instant). */
        private static final float RELEASE_PER_FRAME = 0.05f;

        private CameraFrame lastFrame;
        private float distance = Float.NaN;

        /** The frame last put on screen (after the wall pull-in), or null before the first. */
        CameraFrame lastFrame() {
            return lastFrame;
        }

        @Override
        public void moveCamera(CameraFrame frame) {
            Player player = Game.getPlayer();
            if (player == null) {
                return;
            }
            org.joml.Vector3f look = frame.target();
            org.joml.Vector3f out = new org.joml.Vector3f(frame.eye()).sub(look);
            float desired = out.length();
            if (desired < 1e-4f) {
                return;
            }
            out.div(desired);
            // Through leaves: the orbit looks down over the canopy, and pulling in at every tree
            // between it and the player would keep the wide shot from ever showing in a forest.
            float hit = player.getRaycastEngine().distanceToFirstSolid(look, out, desired, true);
            float allowed = hit == Float.MAX_VALUE ? desired : Math.max(MIN_DISTANCE, hit - WALL_BACKOFF);
            distance = Float.isNaN(distance) || allowed < distance ? allowed
                    : Math.min(allowed, distance + RELEASE_PER_FRAME);
            CameraFrame shown = new CameraFrame(new org.joml.Vector3f(look).fma(distance, out), look,
                    frame.rollDeg(), frame.fovDeg());
            lastFrame = shown;
            applyFrame(shown);
        }

        @Override
        public boolean areaReady(CameraFrame frame) {
            return MenuShowcase.areaReady(frame);
        }

        @Override
        public void setConditions(Long timeTicks, String weather) {
            // The preview never touches the world's clock or weather.
        }

        @Override
        public float liftFor(FlyoverShot shot) {
            return 0f; // kept out of the terrain by the wall pull-in instead
        }
    }

    /** True once the area around {@code frame}'s eye (and what it looks at) has drawn enough to show. */
    private static boolean areaReady(CameraFrame frame) {
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

    /** A showcase shot's time of day and weather, set on the (ephemeral) server while the screen is black. */
    private static void setShotConditions(Long timeTicks, String weather) {
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

    /** How far a showcase shot must rise to clear this world's terrain and water. */
    private static float liftOverTerrain(FlyoverShot shot) {
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
