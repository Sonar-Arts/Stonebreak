package com.stonebreak.core;

import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWMonitorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openmason.engine.diagnostics.MemoryProfiler;
import com.stonebreak.config.Settings;
import com.stonebreak.core.render.FrameRenderer;
import com.stonebreak.core.window.DisplayBackend;
import com.stonebreak.core.window.GameWindow;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MenuInputRouter;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.rendering.vram.CearlBootstrap;
import com.stonebreak.world.World;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glEnable;

/**
 * Entry point for Stonebreak: owns the application lifecycle — start up, run the frame loop, shut
 * down — and nothing else.
 *
 * <p>The pieces it drives each own one concern: {@link DisplayBackend} picks and starts the GLFW
 * platform, {@link GameWindow} owns the window and its GL context, {@link MenuInputRouter} decides
 * which screen an input event belongs to, and {@link FrameRenderer} draws the frame.</p>
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final String WINDOW_TITLE = "Stonebreak";

    /** Static handle for the few systems that need to reach the live window (settings, resolution). */
    private static Main instance;

    private GameWindow window;
    private MenuInputRouter inputRouter;
    private FrameRenderer frameRenderer;

    private Renderer renderer;
    private InputHandler inputHandler;

    private boolean running = false;

    public static void main(String[] args) {
        GcEnforcement.enforce();
        new Main().run();
    }

    private void run() {
        instance = this;
        logger.info("Starting Stonebreak with LWJGL {}", Version.getVersion());

        try {
            init();
            loop();
        } finally {
            cleanup();
            logger.debug("Stonebreak shutdown complete.");
        }
        System.exit(0);
    }

    // ─── Startup ──────────────────────────────────────────────────────────────

    private void init() {
        Settings settings = Settings.getInstance();
        logger.info("Settings loaded - Window size: {}x{}", settings.getWindowWidth(), settings.getWindowHeight());

        DisplayBackend.initialize();

        window = new GameWindow(settings.getWindowWidth(), settings.getWindowHeight());
        window.setResizeListener(this::onFramebufferResized);
        inputRouter = new MenuInputRouter(window);
        frameRenderer = new FrameRenderer(window);

        window.create(WINDOW_TITLE);
        installCallbacks();
        // Compile the CEARL program and install its VRAM plan BEFORE any
        // renderer exists: region arenas and the staging ring read the plan
        // when they are created. Needs the GL context (VRAM detection).
        CearlBootstrap.install();
        initializeGameComponents();
    }

    /** Keeps the projection matrix and the Game singleton's cached dimensions on the real size. */
    private void onFramebufferResized(int width, int height) {
        if (renderer != null) {
            renderer.updateProjectionMatrix(width, height);
        }
        Game.getInstance().setWindowDimensions(width, height);
    }

    /**
     * Wires GLFW's callbacks to the input router. Each one only adapts the raw GLFW signature —
     * which screen an event reaches is {@link MenuInputRouter}'s decision, not this class's.
     */
    private void installCallbacks() {
        long handle = window.handle();

        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> inputRouter.onKey(key, action, mods));
        glfwSetCharCallback(handle, (win, codepoint) -> inputRouter.onCharacter(codepoint));
        glfwSetMouseButtonCallback(handle, (win, button, action, mods) ->
                inputRouter.onMouseButton(button, action, mods));
        glfwSetCursorPosCallback(handle, (win, x, y) -> inputRouter.onMouseMove(x, y));
        glfwSetScrollCallback(handle, (win, xOffset, yOffset) -> inputRouter.onScroll(yOffset));

        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> window.onFramebufferResized(w, h));
        // Window (screen-coordinate) size can change independently of the framebuffer on
        // Wayland/HiDPI; keep the cursor-to-UI scale in sync.
        glfwSetWindowSizeCallback(handle, (win, w, h) -> window.updateCursorScale());
        // A moved window may now be on a different monitor, so the VSync target changes. (Never fires
        // on Wayland — there refreshMonitorHz caps at the fastest monitor instead.)
        glfwSetWindowPosCallback(handle, (win, x, y) -> window.refreshMonitorHz());

        glfwSetWindowFocusCallback(handle, (win, focused) -> {
            var mouseCapture = Game.getInstance().getMouseCaptureManager();
            if (mouseCapture == null) {
                return;
            }
            if (focused) {
                mouseCapture.updateCaptureState();
            } else {
                mouseCapture.temporaryRelease();
            }
        });

        glfwSetWindowCloseCallback(handle, win -> {
            logger.debug("Window close requested - initiating shutdown...");
            running = false;
        });
    }

    private void initializeGameComponents() {
        MemoryProfiler profiler = MemoryProfiler.getInstance();
        profiler.takeSnapshot("before_initialization");

        renderer = new Renderer(window.width(), window.height());
        profiler.takeSnapshot("after_renderer_init");

        inputHandler = new InputHandler(window.handle());
        inputRouter.setInputHandler(inputHandler);

        BlockTextureArray textureAtlas = renderer.getBlockTextureArray();
        Game.getInstance().initCoreComponents(renderer, textureAtlas, inputHandler, window.handle());
        Game.getInstance().setWindowDimensions(window.width(), window.height());
        profiler.takeSnapshot("after_game_init");

        running = true;

        Game.logDetailedMemoryInfo("Core game components initialized - no world created");
        profiler.compareSnapshots("before_initialization", "after_game_init");
    }

    // ─── Frame loop ───────────────────────────────────────────────────────────

    @SuppressWarnings("BusyWait")
    private void loop() {
        glClearColor(0.5f, 0.8f, 1.0f, 0.0f);
        glEnable(GL_DEPTH_TEST);

        while (!window.shouldClose() && running) {
            long frameStartNanos = System.nanoTime();

            if (inputHandler != null) {
                inputHandler.prepareForNewFrame();
            }
            glfwPollEvents();

            Game.getInstance().update();
            maybeAutoStartWorld();
            Game.displayDebugInfo();
            inputRouter.pollActiveScreen();

            frameRenderer.renderFrame();
            maybeAutoFurnace();
            maybeAutoTorch();
            maybeAutoBattle();
            maybeAutoScreenshot();
            maybeAutoFly();
            window.swapBuffers();

            if (!sleepToFrameBudget(System.nanoTime() - frameStartNanos)) {
                break;
            }
        }
    }

    // ─── Dev: -Dstonebreak.autoworld=<name>[:<seed>] ─────────────────────────

    private boolean autoWorldDone;
    private int autoWorldMenuFrames;

    /**
     * Development shortcut: once the main menu is up, start (or load) the named
     * singleplayer world exactly as the world-select screen would — the same
     * {@code MultiplayerSession.startSingleplayer} path, integrated server and
     * all — so rendering changes can be eyeballed from one command line
     * without clicking through menus. Inert unless the property is set.
     */
    private void maybeAutoStartWorld() {
        if (autoWorldDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autoworld");
        if (spec == null || spec.isBlank()) {
            autoWorldDone = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.MAIN_MENU) {
            return;
        }
        if (++autoWorldMenuFrames < 10) {
            return; // let the menu settle for a few frames first
        }
        autoWorldDone = true;
        String name = spec;
        long seed = 20260820L;
        int colon = spec.lastIndexOf(':');
        if (colon > 0) {
            name = spec.substring(0, colon);
            try {
                seed = Long.parseLong(spec.substring(colon + 1));
            } catch (NumberFormatException e) {
                System.err.println("[autoworld] bad seed in " + spec + " — using " + seed);
            }
        }
        System.out.println("[autoworld] starting singleplayer world '" + name + "' seed " + seed);
        com.stonebreak.network.MultiplayerSession.startSingleplayer(name, seed);
    }

    // ─── Dev: -Dstonebreak.autotorch=<seconds>[:night][:cluster] ─────────────

    private long autoTorchDeadlineNanos = -1;
    private boolean autoTorchDone;
    private boolean autoTorchPlaced;
    private float autoTorchLookX, autoTorchLookY, autoTorchLookZ;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds
     * after the world is entered, clears a pocket in front of the player, raises
     * a stone wall, places a ground torch and a wall torch (through the same
     * state-carrying {@code World.setBlockAt} the BlockPlacer uses), hands the
     * player a stack of torches (selected, so the held-torch light shows) and
     * aims the camera at them. {@code :night} jumps the clock to midnight so
     * the point lights are visible. Inert unless the property is set.
     */
    private void maybeAutoTorch() {
        if (autoTorchDone) {
            if (autoTorchPlaced) {
                aimCameraAt(autoTorchLookX, autoTorchLookY, autoTorchLookZ);
            }
            return;
        }
        String spec = System.getProperty("stonebreak.autotorch");
        if (spec == null || spec.isBlank()) {
            autoTorchDone = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.PLAYING && autoTorchDeadlineNanos < 0) {
            return;
        }
        String[] parts = spec.trim().split(":");
        if (autoTorchDeadlineNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(parts[0]);
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoTorchDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoTorchDeadlineNanos) {
            return;
        }
        autoTorchDone = true;
        var world = Game.getWorld();
        var player = Game.getPlayer();
        if (world == null || player == null) {
            System.err.println("[autotorch] world/player not ready");
            return;
        }
        var torchItem = com.stonebreak.blocks.torch.TorchBlock.item();
        if (torchItem == null) {
            System.err.println("[autotorch] torch item not registered");
            return;
        }
        org.joml.Vector3f pos = player.getPosition();
        int px = (int) Math.floor(pos.x);
        int py = (int) Math.floor(pos.y);
        int pz = (int) Math.floor(pos.z);
        var air = com.stonebreak.blocks.BlockType.AIR;
        var stone = com.stonebreak.blocks.BlockType.STONE;
        // Pocket: x+1..x+7, z-3..z+3, floor of stone, back wall of stone at x+7.
        for (int x = px + 1; x <= px + 7; x++) {
            for (int z = pz - 3; z <= pz + 3; z++) {
                for (int y = py; y <= py + 4; y++) {
                    boolean wall = x == px + 7;
                    var want = wall ? stone : air;
                    if (world.getBlockAt(x, y, z) != want) {
                        world.setBlockAt(x, y, z, want, true);
                    }
                }
                if (world.getBlockAt(x, py - 1, z) != stone) {
                    world.setBlockAt(x, py - 1, z, stone, true);
                }
            }
        }
        var torchBlock = com.stonebreak.blocks.torch.TorchBlock.block();
        // Ground torch on the floor, 3 blocks ahead.
        String ground = com.stonebreak.blocks.torch.TorchState.ground().toStateString();
        boolean g = world.setBlockAt(px + 3, py, pz - 1, torchBlock, true, ground);
        // Wall torch hanging off the back wall (the wall is at +X of its cell → EAST).
        String side = com.stonebreak.blocks.torch.TorchState
                .side(com.stonebreak.blocks.torch.TorchState.Facing.EAST).toStateString();
        boolean w = world.setBlockAt(px + 6, py + 1, pz + 1, torchBlock, true, side);
        // ":cluster" — a ring of ground torches around the first one, to check
        // that overlapping lights saturate gracefully instead of bleaching.
        if (java.util.Arrays.asList(parts).contains("cluster")) {
            int[][] ring = {{2, -2}, {4, -2}, {2, 1}, {4, 1}, {5, -1}, {3, 2}, {1, -1}, {5, 2}};
            for (int[] o : ring) {
                world.setBlockAt(px + o[0], py, pz + o[1], torchBlock, true, ground);
            }
        }
        // Hand the player torches and select them.
        var inv = player.getInventory();
        inv.addItem(new com.stonebreak.items.ItemStack(torchItem, 16));
        for (int i = 0; i < 9; i++) {
            inv.setSelectedHotbarSlotIndex(i);
            var sel = inv.getSelectedHotbarSlot();
            if (sel != null && !sel.isEmpty() && sel.getItem() == torchItem) break;
        }
        if (java.util.Arrays.asList(parts).contains("night")) {
            // The integrated server owns the clock (TimeSyncS2C snaps the client
            // back), so route through the same path as /timeset.
            com.stonebreak.network.MultiplayerSession.requestServerTimeSet(18000L);
            if (Game.getTimeOfDay() != null) Game.getTimeOfDay().setTicks(18000L);
        }
        autoTorchLookX = px + 4.5f;
        autoTorchLookY = py + 0.6f;
        autoTorchLookZ = pz + 0f;
        autoTorchPlaced = true;
        System.out.println("[autotorch] ground=" + g + " @" + (px + 3) + "," + py + "," + (pz - 1)
                + " [" + world.getBlockStateAt(px + 3, py, pz - 1) + "]"
                + " wall=" + w + " @" + (px + 6) + "," + (py + 1) + "," + (pz + 1)
                + " [" + world.getBlockStateAt(px + 6, py + 1, pz + 1) + "]"
                + " held=" + (inv.getSelectedHotbarSlot() != null ? inv.getSelectedHotbarSlot().getItem().getName() : "-"));
    }

    private void aimCameraAt(float x, float y, float z) {
        var player = Game.getPlayer();
        if (player == null) return;
        org.joml.Vector3f pos = player.getPosition();
        float dx = x - pos.x;
        float dz = z - pos.z;
        float dy = y - (pos.y + 1.6f);
        player.getCamera().setYaw((float) Math.toDegrees(Math.atan2(dz, dx)));
        player.getCamera().setPitch((float) Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    // ─── Dev: -Dstonebreak.autofurnace=<seconds> ─────────────────────────────

    private long autoFurnaceDeadlineNanos = -1;
    private boolean autoFurnaceDone;
    private int autoFurnaceX, autoFurnaceY, autoFurnaceZ;
    private boolean autoFurnacePlaced;
    private int autoFurnaceFrames;
    private String autoFurnaceLastState;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds
     * after the world is entered, places a furnace a few blocks from the player
     * and loads it with the first registered fuel + smeltable through the same
     * slot-intent packet the furnace UI sends, so the Unlit→Lit re-mesh can be
     * reproduced from a script. Inert unless the property is set.
     */
    private void maybeAutoFurnace() {
        if (autoFurnaceDone) {
            if (autoFurnacePlaced) {
                autoFurnaceWatch();
            }
            return;
        }
        String spec = System.getProperty("stonebreak.autofurnace");
        if (spec == null || spec.isBlank()) {
            autoFurnaceDone = true;
            return;
        }
        if (Game.getInstance().getState() != GameState.PLAYING && autoFurnaceDeadlineNanos < 0) {
            return;
        }
        if (autoFurnaceDeadlineNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(spec.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoFurnaceDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoFurnaceDeadlineNanos) {
            return;
        }
        autoFurnaceDone = true;
        var world = Game.getWorld();
        var player = Game.getPlayer();
        var smelting = Game.getInstance().getSmeltingManager();
        if (world == null || player == null || smelting == null) {
            System.err.println("[autofurnace] world/player/smelting not ready");
            return;
        }
        com.stonebreak.items.Item fuel = null;
        for (com.stonebreak.items.ItemType t : com.stonebreak.items.ItemType.values()) {
            if (smelting.getBurnTimePerUnit(t) > 0) { fuel = t; break; }
        }
        if (fuel == null) {
            for (com.stonebreak.blocks.BlockType t : com.stonebreak.blocks.BlockType.values()) {
                if (smelting.getBurnTimePerUnit(t) > 0) { fuel = t; break; }
            }
        }
        var recipes = smelting.getAllRecipes();
        if (fuel == null || recipes.isEmpty()) {
            System.err.println("[autofurnace] no fuel/recipe registered (fuel=" + fuel
                + ", recipes=" + recipes.size() + ")");
            return;
        }
        org.joml.Vector3f pos = player.getPosition();
        int px = (int) Math.floor(pos.x);
        int py = (int) Math.floor(pos.y);
        int pz = (int) Math.floor(pos.z);
        int fx = px + 3;
        int fy = py;
        int fz = pz;
        // Clear a pocket in front of the player so the furnace and its
        // neighbours are in view (stone floor under it).
        for (int x = px + 1; x <= px + 6; x++) {
            for (int z = pz - 3; z <= pz + 3; z++) {
                for (int y = py; y <= py + 5; y++) {
                    if (world.getBlockAt(x, y, z) != com.stonebreak.blocks.BlockType.AIR) {
                        world.setBlockAt(x, y, z, com.stonebreak.blocks.BlockType.AIR, true);
                    }
                }
                if (world.getBlockAt(x, py - 1, z) == com.stonebreak.blocks.BlockType.AIR) {
                    world.setBlockAt(x, py - 1, z, com.stonebreak.blocks.BlockType.STONE, true);
                }
            }
        }
        if (!world.setBlockAt(fx, fy, fz, com.stonebreak.blocks.BlockType.FURNACE, true)) {
            System.err.println("[autofurnace] setBlockAt failed at " + fx + "," + fy + "," + fz);
            return;
        }
        var fr = Game.getInstance().getFurnaceRegistry();
        if (fr != null) {
            fr.onBlockPlaced(world, fx, fy, fz, com.stonebreak.blocks.BlockType.FURNACE);
        }
        var state = new com.stonebreak.blocks.furnace.FurnaceState(
            new com.openmason.engine.util.BlockPos(fx, fy, fz));
        state.setIngredient(new com.stonebreak.items.ItemStack(recipes.getFirst().getInput().getItem(), 8));
        state.setFuel(new com.stonebreak.items.ItemStack(fuel, 8));
        String slots = state.encodeSlots();
        com.stonebreak.network.MultiplayerSession.sendFurnaceSlots(fx, fy, fz, slots);
        autoFurnaceX = fx;
        autoFurnaceY = fy;
        autoFurnaceZ = fz;
        autoFurnacePlaced = true;
        System.out.println("[autofurnace] placed furnace at " + fx + "," + fy + "," + fz
            + " fuel=" + fuel.getName() + " input=" + recipes.getFirst().getInput().getItem().getName()
            + " slots=" + slots);
    }

    /** Keeps the camera on the placed furnace and logs its client-side state whenever it changes. */
    private void autoFurnaceWatch() {
        var world = Game.getWorld();
        var player = Game.getPlayer();
        if (world == null || player == null) {
            return;
        }
        org.joml.Vector3f pos = player.getPosition();
        float dx = autoFurnaceX + 0.5f - pos.x;
        float dz = autoFurnaceZ + 0.5f - pos.z;
        float dy = autoFurnaceY + 0.5f - (pos.y + 1.6f);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx));
        float pitch = (float) Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.getCamera().setYaw(yaw);
        player.getCamera().setPitch(pitch);
        if (++autoFurnaceFrames % 30 != 0) {
            return;
        }
        // -Dstonebreak.autofurnace.ui=<frames-after-place>[:close] — open the
        // furnace UI on the furnace (and optionally close it again 120 frames
        // later) to reproduce UI-triggered rendering state leaks.
        String uiSpec = System.getProperty("stonebreak.autofurnace.ui");
        if (uiSpec != null && !uiSpec.isBlank()) {
            String[] parts = uiSpec.split(":");
            int openAt = Integer.parseInt(parts[0].trim());
            boolean close = parts.length > 1 && parts[1].equals("close");
            if (autoFurnaceFrames == openAt) {
                System.out.println("[autofurnace] opening furnace UI");
                Game.getInstance().openFurnaceScreen(
                    new com.openmason.engine.util.BlockPos(autoFurnaceX, autoFurnaceY, autoFurnaceZ));
            } else if (close && autoFurnaceFrames == openAt + 120) {
                System.out.println("[autofurnace] closing furnace UI");
                Game.getInstance().closeFurnaceScreen();
            }
        }
        String state = world.getBlockStateAt(autoFurnaceX, autoFurnaceY, autoFurnaceZ);
        String render = com.stonebreak.blocks.BlockRenderState.meshVariantKey(state);
        if (!java.util.Objects.equals(render, autoFurnaceLastState)) {
            autoFurnaceLastState = render;
            System.out.println("[autofurnace] render state now " + render + " (raw " + state + ")");
        }
    }

    // ─── Dev: -Dstonebreak.autobattle=<seconds>[:skipintro] ───────────────────

    private long autoBattleDeadlineNanos = -1;
    private boolean autoBattleDone;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds after the world is
     * entered, starts the Focus battle exactly as {@code /battle} does (entering the arena on the
     * way), optionally skipping the camera intro, so the mode can be screenshotted from a script.
     * Inert unless the property is set.
     */
    private com.stonebreak.battle.stage.BattleAutoScript autoBattleScript;
    private boolean autoBattleSkipIntro;

    private void maybeAutoBattle() {
        if (autoBattleDone && autoBattleSkipIntro && com.stonebreak.battle.stage.FocusBattle.isActive()
                && !com.stonebreak.battle.stage.FocusBattle.encounterTransitionActive()) {
            autoBattleSkipIntro = false; // the battle begins a frame after it is requested
            com.stonebreak.battle.stage.FocusBattle.skipIntroNow();
            System.out.println("[autobattle] intro skipped");
        }
        if (autoBattleDone) {
            // ...:script:<dir> / ...:shots:<dir>: a bot plays the fight and dumps labelled screenshots.
            if (autoBattleScript != null
                    && autoBattleScript.frame(Game.getDeltaTime(), window.width(), window.height())) {
                autoBattleScript = null;
                running = false;
            }
            return;
        }
        String spec = System.getProperty("stonebreak.autobattle");
        if (spec == null || spec.isBlank()) {
            autoBattleDone = true;
            return;
        }
        // Arm on the first PLAYING frame, and only fire from PLAYING: a window that lost focus under
        // the compositor auto-pauses, and the battle refuses to start from the pause menu.
        if (Game.getInstance().getState() != GameState.PLAYING) {
            return;
        }
        String[] parts = spec.split(":");
        if (autoBattleDeadlineNanos < 0) {
            double seconds = 3;
            try {
                seconds = Double.parseDouble(parts[0].trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoBattleDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoBattleDeadlineNanos) {
            return;
        }
        autoBattleDone = true;
        String result = com.stonebreak.battle.stage.FocusBattle.start();
        System.out.println("[autobattle] " + result + " (pending="
                + com.stonebreak.battle.stage.FocusBattle.isStartPending() + ")");
        autoBattleScript = com.stonebreak.battle.stage.BattleAutoScript.parse(parts);
        autoBattleSkipIntro = parts.length > 1 && parts[1].trim().equalsIgnoreCase("skipintro");
    }

    // ─── Dev: -Dstonebreak.autoscreenshot=<seconds>:<file.png>[:quit] ──────────

    private long autoShotDeadlineNanos = -1;
    private boolean autoShotDone;
    private GameState lastLoggedState;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: N seconds
     * after the world is entered, reads the back buffer into a PNG (and
     * optionally quits) so a rendering change can be verified from a script
     * without a compositor screenshot. Inert unless the property is set.
     */
    private void maybeAutoScreenshot() {
        if (autoShotDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autoscreenshot");
        if (spec == null || spec.isBlank()) {
            autoShotDone = true;
            return;
        }
        GameState state = Game.getInstance().getState();
        if (state != lastLoggedState) {
            lastLoggedState = state;
            System.out.println("[autoscreenshot] game state: " + state);
        }
        // A window that loses focus under the compositor auto-pauses; the back
        // buffer still holds the rendered world behind the pause menu.
        // Arm on the first PLAYING frame; afterwards shoot on schedule whatever
        // UI state stray focus/keys may have toggled (the world is still drawn).
        if (autoShotDeadlineNanos < 0 && state != GameState.PLAYING) {
            return;
        }
        String[] parts = spec.split(":");
        if (autoShotDeadlineNanos < 0) {
            double seconds = 5;
            try {
                seconds = Double.parseDouble(parts[0]);
            } catch (NumberFormatException ignored) {
                // keep default
            }
            autoShotDeadlineNanos = System.nanoTime() + (long) (seconds * 1e9);
            return;
        }
        if (System.nanoTime() < autoShotDeadlineNanos) {
            return;
        }
        autoShotDone = true;
        var tracker = com.openmason.engine.diagnostics.GpuMemoryTracker.getInstance();
        StringBuilder vram = new StringBuilder("[autoscreenshot] vram");
        for (var c : com.openmason.engine.diagnostics.GpuMemoryTracker.Category.values()) {
            vram.append(' ').append(c).append('=').append(tracker.getBytes(c) >> 10).append("KiB");
        }
        System.out.println(vram.append(" total=").append(tracker.getTotalBytes() >> 10).append("KiB"));
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            var rr = com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance();
            System.out.println("[autoscreenshot] regions atlas=" + (rr.layerBytes(0) >> 10) + "KiB/"
                + rr.layerMeshes(0) + " water=" + (rr.layerBytes(1) >> 10) + "KiB/" + rr.layerMeshes(1)
                + " stamp=" + (rr.layerBytes(2) >> 10) + "KiB/" + rr.layerMeshes(2)
                + " format=" + com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat.active());
        }
        var lod = com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher.active();
        if (lod != null) {
            System.out.println("[autoscreenshot] lod terrain=" + (lod.layerBytes(0) >> 10) + "KiB/"
                + lod.layerMeshes(0) + " nodes/" + lod.layerQuads(0) + " quads"
                + " water=" + (lod.layerBytes(1) >> 10) + "KiB/" + lod.layerMeshes(1) + " nodes/"
                + lod.layerQuads(1) + " quads");
        }
        String file = parts.length > 1 ? parts[1] : "autoscreenshot.png";
        int w = window.width();
        int h = window.height();
        java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_BACK);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
        org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL11.GL_RGBA,
            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = ((h - 1 - y) * w + x) * 4;
                int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF, b = pixels.get(i + 2) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
            System.out.println("[autoscreenshot] wrote " + file + " (" + w + "x" + h + ")");
        } catch (java.io.IOException e) {
            System.err.println("[autoscreenshot] failed: " + e.getMessage());
        }
        if (parts.length > 2 && "quit".equalsIgnoreCase(parts[2])) {
            running = false;
        }
    }

    // ─── Dev: -Dstonebreak.autofly=<delay>:<speed>:<seconds>:<dir>[:<everyN>] ──

    private long autoFlyStartNanos = -1;
    private boolean autoFlyDone;
    private boolean autoFlyFlying;
    private float autoFlyX, autoFlyY, autoFlyZ;
    private long autoFlyFrame;
    private java.util.concurrent.ExecutorService autoFlyWriter;

    /**
     * Development shortcut paired with {@code stonebreak.autoworld}: after
     * {@code delay} seconds in the world, flies the player along +X at
     * {@code speed} blocks/s, 40 blocks above the start, sweeping the yaw so
     * chunks stream in and out of view continuously, and dumps every
     * {@code everyN}-th frame (default 15) to {@code dir/f<frame>.png}. Quits
     * after {@code seconds} of flight. Built to reproduce transient
     * mesh-streaming corruption from a script. Inert unless the property is set.
     */
    private void maybeAutoFly() {
        if (autoFlyDone) {
            return;
        }
        String spec = System.getProperty("stonebreak.autofly");
        if (spec == null || spec.isBlank()) {
            autoFlyDone = true;
            return;
        }
        GameState state = Game.getInstance().getState();
        if (autoFlyStartNanos < 0) {
            if (state != GameState.PLAYING) {
                return;
            }
            autoFlyStartNanos = System.nanoTime();
            return;
        }
        if (state == GameState.PAUSED) {
            Game.getInstance().setState(GameState.PLAYING); // a stray focus loss must not stop the run
        }
        String[] parts = spec.split(":");
        double delay = parseOr(parts, 0, 10);
        double speed = parseOr(parts, 1, 40);
        double seconds = parseOr(parts, 2, 60);
        String dir = parts.length > 3 ? parts[3] : "autofly";
        int everyN = (int) parseOr(parts, 4, 15);
        double t = (System.nanoTime() - autoFlyStartNanos) / 1e9 - delay;
        if (t < 0) {
            return;
        }
        var player = Game.getPlayer();
        if (player == null) {
            return;
        }
        if (!autoFlyFlying) {
            autoFlyFlying = true;
            org.joml.Vector3f p = player.getPosition();
            autoFlyX = p.x;
            autoFlyY = Math.min(p.y + 40f,
                com.stonebreak.world.operations.WorldConfiguration.WORLD_HEIGHT - 8f);
            autoFlyZ = p.z;
            new java.io.File(dir).mkdirs();
            autoFlyWriter = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread th = new Thread(r, "autofly-png");
                th.setDaemon(true);
                return th;
            });
            System.out.println("[autofly] start @" + autoFlyX + "," + autoFlyY + "," + autoFlyZ
                + " speed=" + speed + " seconds=" + seconds + " dir=" + dir + " every=" + everyN);
        }
        if (t > seconds) {
            autoFlyDone = true;
            for (int spin = 0; spin < 2000 && java.util.Arrays.stream(autoFlyPboFence).anyMatch(f -> f != 0); spin++) {
                org.lwjgl.opengl.GL11.glFinish();
                drainAutoFlyReadbacks(dir);
            }
            autoFlyWriter.shutdown();
            try {
                autoFlyWriter.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[autofly] done after " + autoFlyFrame + " frames, skipped " + autoFlySkipped);
            running = false;
            return;
        }
        // Fixed-rate path (not dt-integrated) so runs at different FPS cover the same ground.
        float x = autoFlyX + (float) (speed * t);
        // Terrain-following: hold ~25 blocks over the highest loaded surface just
        // ahead, climbing fast and sinking slowly, so the camera never ends up
        // inside a mountain (which reads as garbage to the frame analyzer).
        float ground = Float.NEGATIVE_INFINITY;
        for (int ahead = 0; ahead <= 48; ahead += 8) {
            ground = Math.max(ground, autoFlySurface(x + ahead, autoFlyZ));
        }
        if (ground > Float.NEGATIVE_INFINITY) {
            float target = Math.min(ground + 25f,
                com.stonebreak.world.operations.WorldConfiguration.WORLD_HEIGHT - 8f);
            float dt = Math.min(0.1f, Game.getDeltaTime());
            autoFlyY = target > autoFlyY
                ? Math.min(target, autoFlyY + 60f * dt)
                : Math.max(target, autoFlyY - 15f * dt);
        }
        player.setFlying(true);
        player.teleport(x, autoFlyY, autoFlyZ);
        player.getCamera().setYaw((float) (60.0 * Math.sin(t * 0.7)));
        player.getCamera().setPitch(-20f);

        autoFlyFrame++;
        drainAutoFlyReadbacks(dir);
        if (everyN > 0 && autoFlyFrame % everyN == 0) {
            // Async PBO readback: a blocking glReadPixels drains the GPU queue
            // every capture, which is exactly the CPU/GPU lag a streaming race
            // needs — so never stall; skip the capture if every PBO is busy.
            int slot = (int) ((autoFlyFrame / everyN) % AUTOFLY_PBOS);
            if (autoFlyPboFence[slot] != 0) {
                autoFlySkipped++;
                return;
            }
            int w = window.width();
            int h = window.height();
            if (autoFlyPbo[slot] == 0 || autoFlyPboW != w || autoFlyPboH != h) {
                if (autoFlyPbo[slot] == 0) {
                    autoFlyPbo[slot] = org.lwjgl.opengl.GL15.glGenBuffers();
                }
                autoFlyPboW = w;
                autoFlyPboH = h;
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, autoFlyPbo[slot]);
                org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER,
                    (long) w * h * 4, org.lwjgl.opengl.GL15.GL_STREAM_READ);
            }
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, autoFlyPbo[slot]);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_BACK);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL11.GL_RGBA,
                org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, 0L);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
            autoFlyPboFence[slot] = org.lwjgl.opengl.GL32.glFenceSync(
                org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            autoFlyPboFrame[slot] = autoFlyFrame;
            autoFlyPboW2[slot] = w;
            autoFlyPboH2[slot] = h;
            System.out.printf("[autofly] frame %d t=%.2f x=%.1f fps=%.0f skipped=%d%n",
                autoFlyFrame, t, x, 1.0 / Math.max(1e-4, Game.getDeltaTime()), autoFlySkipped);
        }
    }

    private static final int AUTOFLY_PBOS = 6;
    private final int[] autoFlyPbo = new int[AUTOFLY_PBOS];
    private final long[] autoFlyPboFence = new long[AUTOFLY_PBOS];
    private final long[] autoFlyPboFrame = new long[AUTOFLY_PBOS];
    private final int[] autoFlyPboW2 = new int[AUTOFLY_PBOS];
    private final int[] autoFlyPboH2 = new int[AUTOFLY_PBOS];
    private int autoFlyPboW, autoFlyPboH;
    private long autoFlySkipped;

    /** Hands every finished PBO readback to the PNG writer without waiting on the GPU. */
    private void drainAutoFlyReadbacks(String dir) {
        for (int i = 0; i < AUTOFLY_PBOS; i++) {
            long fence = autoFlyPboFence[i];
            if (fence == 0) {
                continue;
            }
            int status = org.lwjgl.opengl.GL32.glClientWaitSync(fence, 0, 0L);
            if (status != org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED
                    && status != org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED) {
                continue;
            }
            org.lwjgl.opengl.GL32.glDeleteSync(fence);
            autoFlyPboFence[i] = 0;
            int w = autoFlyPboW2[i];
            int h = autoFlyPboH2[i];
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, autoFlyPbo[i]);
            java.nio.ByteBuffer mapped = org.lwjgl.opengl.GL15.glMapBuffer(
                org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, org.lwjgl.opengl.GL15.GL_READ_ONLY);
            if (mapped != null) {
                java.nio.ByteBuffer copy = java.nio.ByteBuffer.allocate(w * h * 4);
                copy.put(mapped.limit(w * h * 4)).flip();
                org.lwjgl.opengl.GL15.glUnmapBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER);
                String file = String.format("%s/f%06d.png", dir, autoFlyPboFrame[i]);
                autoFlyWriter.submit(() -> writePng(copy, w, h, file));
            }
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    /** Top solid block + 1 at (x, z), or -inf when the column's chunk isn't loaded. */
    private static float autoFlySurface(float x, float z) {
        var world = Game.getWorld();
        if (world == null) {
            return Float.NEGATIVE_INFINITY;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int size = com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE;
        var chunk = world.getChunkIfLoaded(Math.floorDiv(bx, size), Math.floorDiv(bz, size));
        if (chunk == null) {
            return Float.NEGATIVE_INFINITY;
        }
        int top = Math.min(chunk.getHighestNonAirY(),
            com.stonebreak.world.operations.WorldConfiguration.WORLD_HEIGHT - 1);
        for (int y = top; y >= 0; y--) {
            var block = world.getBlockAt(bx, y, bz);
            if (block != null && block.isSolid()) {
                return y + 1;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    private static double parseOr(String[] parts, int i, double fallback) {
        if (parts.length <= i) {
            return fallback;
        }
        try {
            return Double.parseDouble(parts[i].trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Writes the bottom-up RGBA readback as a half-resolution PNG (keeps up with every-2nd-frame capture). */
    private static void writePng(java.nio.ByteBuffer pixels, int w, int h, String file) {
        int ow = w / 2, oh = h / 2;
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(ow, oh, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int i = ((h - 1 - 2 * y) * w + 2 * x) * 4;
                int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF, b = pixels.get(i + 2) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
        } catch (java.io.IOException e) {
            System.err.println("[autofly] png failed: " + e.getMessage());
        }
    }

    /**
     * Paces the loop to the active FPS cap. A budget of 0 means uncapped, so no sleep happens.
     *
     * @return false if the sleep was interrupted and the loop should exit
     */
    private boolean sleepToFrameBudget(long frameTimeNanos) {
        long budgetNanos = window.frameBudgetNanos();
        long remainingNanos = budgetNanos - frameTimeNanos;
        if (remainingNanos <= 0) {
            return true;
        }
        long millis = remainingNanos / 1_000_000;
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis, (int) (remainingNanos % 1_000_000));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ─── Shutdown ─────────────────────────────────────────────────────────────

    private void cleanup() {
        Game.logDetailedMemoryInfo("Before cleanup");

        // Stop the terrain-diffusion service processes (if this session started any) before the
        // rest of cleanup. Independent of the GL context, so safe to run first; also registered
        // as a JVM shutdown hook as a safety net if cleanup() itself never runs (crash/kill -9).
        com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe.getInstance().shutdown();

        // OpenGL resources must be released while their context is still current, so all of this
        // happens before the window (and with it the context) is destroyed.
        if (window != null) {
            window.makeContextCurrent();
        }

        try {
            if (CBRResourceManager.isInitialized()) {
                CBRResourceManager.getInstance().close();
                logger.debug("CBRResourceManager cleaned up successfully");
            }
        } catch (Exception e) {
            logger.error("Error cleaning up CBRResourceManager", e);
        }
        Game.logDetailedMemoryInfo("After CBR cleanup");

        if (renderer != null) {
            renderer.cleanup();
            Game.logDetailedMemoryInfo("After renderer cleanup");
        }

        World world = Game.getInstance().getWorld();
        if (world != null) {
            world.cleanup();
            Game.logDetailedMemoryInfo("After world cleanup");
        }

        Game.getInstance().cleanup();
        Game.logDetailedMemoryInfo("After game cleanup");

        if (window != null) {
            window.destroy();
            Game.logDetailedMemoryInfo("After GLFW window cleanup");
        }

        Game.forceGCAndReport("Final cleanup");

        GLFWMonitorCallback monitorCallback = glfwSetMonitorCallback(null);
        if (monitorCallback != null) {
            monitorCallback.free();
        }
        glfwTerminate();
        GLFWErrorCallback errorCallback = glfwSetErrorCallback(null);
        if (errorCallback != null) {
            errorCallback.free();
        }
    }

    // ─── Access for systems that outlive a single screen ──────────────────────

    /** The live GLFW window handle, or 0 before startup completes. */
    public static long getWindowHandle() {
        return instance != null && instance.window != null ? instance.window.handle() : 0;
    }

    /** Re-reads the real framebuffer size after a programmatic resize (e.g. a resolution change). */
    public static void refreshWindowSize() {
        if (instance != null && instance.window != null && instance.window.handle() != 0) {
            instance.window.syncFramebufferSize();
        }
    }

    /** Applies the persisted VSync preference to the frame limiter. */
    public static void applyVsyncSetting() {
        if (instance != null && instance.window != null) {
            instance.window.applyVsyncSetting();
        }
    }
}
