package com.stonebreak.battle.stage;

import com.stonebreak.battle.BattleConfig;
import com.stonebreak.battle.BattleState;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleScreenHost;
import com.stonebreak.battle.api.BattleSimulation;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.camera.BattleCameraSystem;
import com.stonebreak.battle.camera.CameraFrame;
import com.stonebreak.battletest.BattleTestSession;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.input.InputHandler;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.focusBattle.FocusBattleScreen;
import com.stonebreak.ui.focusBattle.intro.EncounterSwirl;
import com.stonebreak.ui.focusBattle.intro.FrameGrab;
import io.github.humbleui.skija.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;

/**
 * Coordinator of one Focus battle: joins the battle model, the cinematic camera and the HUD, staged
 * inside the battle-test arena session. Static facade in the style of {@link BattleTestSession};
 * everything here runs on the main thread.
 *
 * <p>While a battle is live the player is parked on the arena's spawn ring, the game sits in
 * {@link GameState#FOCUS_BATTLE} (the world and the arena session are not simulated) and the player's
 * {@code Camera} answers from the battle camera. Every exit path goes through {@link #end}, which
 * always gives the camera and the field of view back.
 */
public final class FocusBattle implements BattleScreenHost {

    private static final Logger logger = LoggerFactory.getLogger(FocusBattle.class);

    /** {@code -Dstonebreak.battle.seed=<long>}: fixed seed for the model and the camera director. */
    private static final String SEED_PROPERTY = "stonebreak.battle.seed";
    /** {@code -Dstonebreak.battle.camera=static}: reduced motion, pins the WIDE shot. */
    private static final String CAMERA_PROPERTY = "stonebreak.battle.camera";
    /** {@code -Dstonebreak.battle.archonhp=<int>}: dev override of the Archon's HP pool. */
    private static final String ARCHON_HP_PROPERTY = "stonebreak.battle.archonhp";

    /** A hitch (arena assets load on entry) must not fast-forward the intro or an action. */
    private static final float MAX_STEP_SECONDS = 1f / 15f;
    private static final float MIN_FOV_DEGREES = 15f;
    private static final float MAX_FOV_DEGREES = 120f;
    private static final int FALLBACK_DEXTERITY = 10;
    /**
     * Overworld health is on a hearts scale (about 20); the encounter is tuned for a three-digit
     * pool, so the monk's battle HP is the player's max health times this. The battle keeps its own
     * HP and never touches the live player's health.
     */
    private static final float BATTLE_HP_PER_HEALTH = 9f;

    private static FocusBattle active;
    private static boolean stepFailureLogged;
    /** Set by {@link #start()}; the encounter really begins at the end of the next field frame. */
    private static boolean startRequested;

    private final BattleTestSession session;
    private final BattleStageLayout layout;
    private final FocusBattleScreen screen;
    private final Player player;
    private final Player.Perspective perspectiveOnEntry;

    private BattleSimulation sim;
    private BattleCameraSystem camera;
    /** The freeze-twist-whiteout over the last field frame; the HUD binds when it burns out. */
    private final EncounterSwirl swirl = new EncounterSwirl();
    private boolean screenBound;

    private FocusBattle(BattleTestSession session, BattleStageLayout layout, FocusBattleScreen screen, Player player) {
        this.session = session;
        this.layout = layout;
        this.screen = screen;
        this.player = player;
        this.perspectiveOnEntry = player.getPerspective();
    }

    // ─── Facade ───────────────────────────────────────────────────────────────

    public static boolean isActive() {
        return active != null;
    }

    /** The running battle, or null. */
    public static BattleView view() {
        FocusBattle battle = active;
        return battle == null ? null : battle.sim;
    }

    /** The running battle's stage layout, or null. */
    public static BattleStageLayout layout() {
        FocusBattle battle = active;
        return battle == null ? null : battle.layout;
    }

    /** Dev tooling only ({@link BattleAutoScript}): the running battle's input, or null. */
    static com.stonebreak.battle.api.BattleInput input() {
        FocusBattle battle = active;
        return battle == null ? null : battle.sim;
    }

    /** Dev tooling only ({@link BattleAutoScript}): the running battle's camera, or null. */
    static BattleCameraSystem camera() {
        FocusBattle battle = active;
        return battle == null ? null : battle.camera;
    }

    /** Name of the live camera shot for the debug overlay; empty when no battle is running. */
    public static String shotName() {
        FocusBattle battle = active;
        if (battle == null) {
            return "";
        }
        try {
            return battle.camera.shotName();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /**
     * Starts the encounter, entering the arena first when needed.
     *
     * @return a short player-facing status line (success or the reason it did not start)
     */
    public static String start() {
        if (active != null) {
            return "A Focus battle is already running.";
        }
        Game game = Game.getInstance();
        if (game.getState() != GameState.PLAYING) {
            return "A Focus battle can only start during gameplay.";
        }
        if (game.getFocusBattleScreen() == null) {
            return "The Focus battle screen is not available.";
        }
        String refusal = arenaRefusal();
        if (refusal != null) {
            return refusal; // say so now, not after "An Ice Archon draws near!"
        }
        // The command runs while the chat box is still open; close it now so the freeze-frame the
        // encounter transition twists is a clean view of the field.
        ChatSystem chat = game.getChatSystem();
        if (chat != null && chat.isOpen()) {
            chat.closeChat();
        }
        startRequested = true;
        return "An Ice Archon draws near!";
    }

    /** True between {@link #start()} and the frame the encounter actually begins. */
    public static boolean isStartPending() {
        return startRequested;
    }

    /**
     * Called by the frame renderer at the end of every ordinary in-game frame, before the buffer
     * swap. When an encounter was requested this grabs that finished frame as the still the
     * transition works on, then enters the arena: asset loading and the battle theme's decode both
     * happen here, hidden behind the frozen image.
     */
    public static void afterFieldFrame(int width, int height) {
        if (!startRequested) {
            return;
        }
        startRequested = false;
        String refusal = arenaRefusal();
        if (refusal != null || Game.getInstance().getState() != GameState.PLAYING) {
            // Things changed in the one frame since the request: do not pay for a full-window readback.
            logger.warn("Focus battle did not start: {}", refusal != null ? refusal : "no longer in gameplay");
            return;
        }
        Image still = FrameGrab.backBuffer(width, height);
        String failure = beginNow(still);
        if (failure != null) {
            // On the failure paths that ran after the swirl took the still, the swirl already closed it;
            // closing twice is a no-op in Skija.
            if (still != null) {
                still.close();
            }
            ChatSystem chat = Game.getInstance().getChatSystem();
            if (chat != null) {
                chat.addMessage(failure);
            }
            logger.warn("Focus battle did not start: {}", failure);
        }
    }

    /**
     * The cheap preconditions the arena session enforces on entry, checked up front with its wording,
     * so a refusal is reported instead of an announcement. Null = nothing stands in the way. Entering
     * an already-active arena session needs none of them.
     */
    private static String arenaRefusal() {
        if (BattleTestSession.isActive()) {
            return null;
        }
        if (com.stonebreak.network.MultiplayerSession.getMode()
                != com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER) {
            return "A Focus battle needs a singleplayer world.";
        }
        Player player = Game.getPlayer();
        if (player == null || player.isDead()
                || !com.stonebreak.network.MultiplayerSession.isLocalPlayerDataReady()) {
            return "Load a world and respawn before starting a Focus battle.";
        }
        return null;
    }

    /** @return null on success, otherwise the player-facing reason */
    private static String beginNow(Image still) {
        if (active != null) {
            return "A Focus battle is already running.";
        }
        Game game = Game.getInstance();
        if (game.getState() != GameState.PLAYING) {
            return "A Focus battle can only start during gameplay.";
        }
        FocusBattleScreen screen = game.getFocusBattleScreen();
        if (screen == null) {
            return "The Focus battle screen is not available.";
        }

        boolean enteredArenaHere = false;
        try {
            if (!BattleTestSession.isActive()) {
                // Inherits the arena's own singleplayer / alive checks and their wording.
                String arenaMessage = BattleTestSession.enter();
                if (!BattleTestSession.isActive()) {
                    return arenaMessage;
                }
                enteredArenaHere = true;
            }
            BattleTestSession session = BattleTestSession.current();
            Player player = Game.getPlayer();
            if (session == null || player == null) {
                return "Load a world before starting a Focus battle.";
            }

            session.reset(); // park the live player on the cyan ring
            FocusBattle battle = new FocusBattle(session,
                    BattleStageLayouts.fromArena(session.world().arena()), screen, player);
            battle.beginEncounter(false);
            battle.swirl.begin(still); // takes ownership of the still
            // ~30 MB synchronous decode: pay it here, hidden behind the frozen frame, not on whichever
            // later frame first wants the track (e.g. music switched on in Settings mid-battle).
            com.stonebreak.audio.MusicManager music = Game.getMusicManager();
            if (music != null) {
                music.preload(com.stonebreak.audio.MusicManager.Scene.BATTLE);
            }
            active = battle;

            game.openFocusBattle();
            if (game.getState() != GameState.FOCUS_BATTLE) {
                battle.end(false);
                if (enteredArenaHere) {
                    BattleTestSession.leave();
                }
                return "Could not enter Focus battle mode right now.";
            }
            battle.applyCamera(battle.camera.frame()); // the arena is framed before the first reveal
            return null;
        } catch (IOException | RuntimeException e) {
            logger.error("Unable to start the Focus battle", e);
            if (active != null) {
                active.end(false);
            }
            if (enteredArenaHere) {
                BattleTestSession.leave();
            }
            return "Could not start the Focus battle: " + e.getMessage();
        }
    }

    /**
     * Runs a HUD render or input call under the battle's failure policy: an exception ends the battle
     * (logged once, reported in chat) instead of escaping into the frame loop and killing the game.
     */
    public static void guardHud(String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            FocusBattle battle = active;
            if (battle != null) {
                battle.failed(what, e);
            } else {
                logger.error("Focus battle HUD failed during '{}' with no battle running", what, e);
            }
        }
    }

    /**
     * {@link #guardHud(String, Runnable)} for calls that report whether they consumed the input.
     * Deliberately NOT an overload: an expression lambda such as {@code () -> flag = call()} returns
     * a value, so as an overload it bound to the BooleanSupplier form and the wrapper called itself
     * until the stack overflowed.
     */
    public static boolean guardHudInput(String what, java.util.function.BooleanSupplier call) {
        try {
            return call.getAsBoolean();
        } catch (RuntimeException e) {
            FocusBattle battle = active;
            if (battle != null) {
                battle.failed(what, e);
            } else {
                logger.error("Focus battle HUD failed during '{}' with no battle running", what, e);
            }
            return false;
        }
    }

    /** True while the freeze-twist-whiteout encounter transition is covering the screen. */
    public static boolean encounterTransitionActive() {
        FocusBattle battle = active;
        return battle != null && battle.swirl.active();
    }

    /**
     * Draws the encounter transition over everything. Called by the frame renderer after the battle
     * HUD; a no-op once the transition has burned out.
     */
    public static void renderEncounterTransition(int width, int height) {
        FocusBattle battle = active;
        if (battle == null || !battle.swirl.active()) {
            return;
        }
        // Through the HUD's own MasonryUI frame, like every other battle surface: one sanctioned path
        // for Skija frame bracketing and the GL state reset that follows it.
        com.stonebreak.rendering.UI.masonryUI.MasonryUI ui = battle.screen.renderer().ui();
        if (ui == null || !ui.beginFrame(width, height, 1.0f)) {
            return;
        }
        try {
            battle.swirl.paint(ui.canvas(), width, height);
        } finally {
            ui.endFrame();
        }
    }

    /** Per-frame step, called by the game loop only while the state is {@link GameState#FOCUS_BATTLE}. */
    public static void tick(float dt) {
        FocusBattle battle = active;
        if (battle == null) {
            Game.getInstance().closeFocusBattle(); // the state outlived its battle: fall back to gameplay
            return;
        }
        battle.step(dt);
    }

    /**
     * Stops any running battle and leaves the arena session. Chat is closed for the whole of a battle,
     * so in practice {@code /battle leave} is typed from arena free roam after "Explore arena"; a live
     * fight is left through its result panel or the pause menu's quit.
     *
     * @return false when there was neither a battle nor an arena session to leave
     */
    public static boolean leave() {
        FocusBattle battle = active;
        if (battle != null) {
            battle.returnToWorld();
            return true;
        }
        return BattleTestSession.leave();
    }

    /**
     * Releases the camera, FOV and screen bindings without touching the arena session. For game
     * shutdown and the return to the main menu, where the session is torn down by its own hooks.
     */
    public static void shutdown() {
        startRequested = false;
        FocusBattle battle = active;
        if (battle != null) {
            battle.end(false);
        }
    }

    /** Opens the pause menu over the running battle (the HUD's Escape, and the input router's fallback). */
    public static void requestPauseMenu() {
        FocusBattle battle = active;
        if (battle != null) {
            battle.openPauseMenu();
        }
    }

    /** Dev hook ({@code -Dstonebreak.autobattle=<s>:skipintro}): what a confirm press does during the intro. */
    public static void skipIntroNow() {
        FocusBattle battle = active;
        if (battle != null) {
            battle.skipIntro();
        }
    }

    // ─── Encounter ────────────────────────────────────────────────────────────

    /** Fresh model + camera, bound to the HUD. Used on start and on retry. */
    private void beginEncounter(boolean bindScreenNow) {
        stepFailureLogged = false;
        BattleActors.resetDiagnostics();
        long seed = nextSeed();
        int dexterity = player.getCharacterStats() != null
                ? player.getCharacterStats().getDexterity() : FALLBACK_DEXTERITY;
        BattleConfig config = BattleConfig.defaults(player.getMaxHealth() * BATTLE_HP_PER_HEALTH, dexterity);
        Integer archonHp = Integer.getInteger(ARCHON_HP_PROPERTY);
        if (archonHp != null && archonHp > 0) {
            config = config.withArchon(config.archon().withMaxHp(archonHp)); // dev: shorter scripted fights
        }
        sim = new BattleState(config, new Random(seed));
        camera = new BattleCameraSystem(layout, seed, "static".equalsIgnoreCase(System.getProperty(CAMERA_PROPERTY)));
        screenBound = false;
        if (bindScreenNow) {
            bindScreen();
        }
        logger.info("Focus battle encounter started (seed {})", seed);
    }

    private void bindScreen() {
        screen.bind(sim, sim, layout, this);
        screenBound = true;
    }

    private static long nextSeed() {
        Long fixed = Long.getLong(SEED_PROPERTY);
        return fixed != null ? fixed : System.nanoTime();
    }

    private void step(float rawDt) {
        if (BattleTestSession.current() != session) {
            // The arena was torn down underneath us (world reset, /battletest leave, shutdown).
            end(false);
            return;
        }
        float dt = Math.max(0f, Math.min(rawDt, MAX_STEP_SECONDS));
        if (swirl.active()) {
            // Encounter transition: the battle, its camera intro and the HUD all wait behind the
            // frozen field frame, and start together the moment it burns out to white.
            swirl.update(dt);
            if (swirl.active()) {
                return;
            }
        }
        try {
            if (!screenBound) {
                bindScreen();
            }
            // Slow-motion beats scale battle time only; the camera itself always runs on real time.
            float timeScale = camera.timeScale();
            sim.update(dt * (Float.isFinite(timeScale) ? Math.max(0f, timeScale) : 1f));
            camera.update(sim, dt);
            if (sim.phase() == BattlePhase.INTRO && camera.introFinished()) {
                sim.introFinished();
            }
            applyCamera(camera.frame());
            screen.update(dt);
        } catch (RuntimeException e) {
            failed("step", e);
        }
    }

    private void applyCamera(CameraFrame frame) {
        if (frame == null) {
            return;
        }
        player.getCamera().setCinematicView(frame.eye(), frame.target(), frame.rollDeg());
        Renderer renderer = Game.getRenderer();
        float fov = frame.fovDeg();
        if (renderer != null && Float.isFinite(fov)) {
            // The projection matrix is shared by every renderer; never hand it a degenerate FOV.
            renderer.setCinematicFov(Math.max(MIN_FOV_DEGREES, Math.min(MAX_FOV_DEGREES, fov)));
        }
    }

    /** Runs a model/camera/HUD call that must not be able to crash the game. */
    private void guarded(String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            failed(what, e);
        }
    }

    /** A broken (or not yet implemented) battle part ends the battle; it never takes the game down. */
    private void failed(String what, RuntimeException e) {
        if (!stepFailureLogged) {
            stepFailureLogged = true;
            logger.error("Focus battle failed during '{}'; stopping the battle", what, e);
        }
        ChatSystem chat = Game.getInstance().getChatSystem();
        if (chat != null) {
            chat.addMessage("Focus battle stopped: " + e);
        }
        if (active == this) {
            exploreArena();
        }
    }

    /**
     * The single exit path. Idempotent.
     *
     * @param reseatPlayer true to put the first-person camera back on the player's spawn ring
     */
    private void end(boolean reseatPlayer) {
        if (active != this) {
            return;
        }
        // Cleared first: GameStateController redirects PLAYING to FOCUS_BATTLE while a battle is active.
        active = null;

        swirl.close();
        player.getCamera().clearCinematicView();
        Renderer renderer = Game.getRenderer();
        if (renderer != null) {
            renderer.resetFov();
        }
        try {
            screen.unbind();
        } catch (RuntimeException e) {
            logger.warn("Focus battle screen failed to unbind", e);
        }
        if (player.getPerspective() != perspectiveOnEntry) {
            player.togglePerspective();
        }
        if (reseatPlayer && BattleTestSession.current() == session) {
            session.reset();
        }

        Game game = Game.getInstance();
        InputHandler input = game.getInputHandler();
        if (input != null) {
            input.suppressHeldUiToggleKeys(); // the confirm/back key that ended the battle is still held
        }
        if (game.getState() == GameState.FOCUS_BATTLE) {
            game.closeFocusBattle();
        }
        logger.info("Focus battle ended");
    }

    // ─── BattleScreenHost ─────────────────────────────────────────────────────

    @Override
    public void skipIntro() {
        if (active == this) {
            guarded("skip intro", camera::skipIntro);
        }
    }

    @Override
    public void retry() {
        if (active == this) {
            guarded("retry", () -> beginEncounter(true));
        }
    }

    @Override
    public void exploreArena() {
        end(true);
    }

    @Override
    public void returnToWorld() {
        if (active != this) {
            return;
        }
        end(false);
        BattleTestSession.leave();
    }

    @Override
    public void openPauseMenu() {
        Game game = Game.getInstance();
        if (active != this || game.getState() != GameState.FOCUS_BATTLE) {
            return;
        }
        InputHandler input = game.getInputHandler();
        if (input != null) {
            // Polling resumes in PAUSED; without this the same Escape press closes the menu again.
            input.suppressHeldUiToggleKeys();
        }
        game.togglePauseMenu();
    }
}
