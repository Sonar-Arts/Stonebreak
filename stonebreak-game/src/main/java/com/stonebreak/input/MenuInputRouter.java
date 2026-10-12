package com.stonebreak.input;

import java.nio.DoubleBuffer;
import java.util.function.Consumer;

import org.lwjgl.system.MemoryStack;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.core.window.GameWindow;
import com.stonebreak.player.Player;
import com.stonebreak.ui.runtime.GameUiInput;

/**
 * Routes raw window input to whichever surface the current {@link GameState} puts in front.
 *
 * <p>Open UI documents ({@link GameUiInput}, #288) are offered every event first; every screen is a
 * document, so what a document consumes goes no further. The few keyboard owners outside the
 * documents are consulted next: the terrain mapper (its document leaves the keyboard to
 * {@code TerrainInputHandler}) and the Focus battle HUD. Everything left falls through to
 * {@link InputHandler}. Screens are consulted through {@link #dispatch}, which absorbs the
 * not-yet-constructed case; a screen that is absent simply does not consume the event.</p>
 */
public final class MenuInputRouter {

    private final GameWindow window;
    private InputHandler inputHandler;
    /** Keys whose PRESS the battle HUD saw; see {@link #routeBattleKey}. */
    private final java.util.BitSet battleKeysDown = new java.util.BitSet();

    public MenuInputRouter(GameWindow window) {
        this.window = window;
    }

    /** Set once the input handler exists; until then only the documents and screens see events. */
    public void setInputHandler(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    // ─── Keyboard ─────────────────────────────────────────────────────────────

    public void onKey(int key, int action, int mods) {
        if (GameUiInput.get().onKey(key, action, mods, cursorCaptured(Game.getInstance()))) {
            return;
        }
        Game game = Game.getInstance();
        GameState state = game.getState();
        if (state != GameState.FOCUS_BATTLE) {
            battleKeysDown.clear();
        }
        boolean consumed = state != null && switch (state) {
            case TERRAIN_MAPPER -> dispatch(game.getTerrainMapperScreen(), s -> s.handleKeyInput(key, action, mods));
            case FOCUS_BATTLE -> routeBattleKey(game, key, action, mods);
            default -> false;
        };
        if (!consumed && inputHandler != null) {
            inputHandler.handleKeyInput(key, action, mods);
        }
    }

    public void onCharacter(int codepoint) {
        // Documents take whole code points (emoji included) before the BMP filter below.
        if (GameUiInput.get().onCharacter(codepoint, cursorCaptured(Game.getInstance()))) {
            return;
        }
        // Drop codepoints outside the BMP; casting them to a single char would produce an unpaired
        // surrogate that crashes Skija's text layout on the next measureTextWidth call.
        if (codepoint < 0 || codepoint > 0xFFFF || Character.isSurrogate((char) codepoint)) {
            return;
        }
        char character = (char) codepoint;

        Game game = Game.getInstance();
        GameState state = game.getState();
        boolean consumed = state != null && switch (state) {
            case TERRAIN_MAPPER -> dispatch(game.getTerrainMapperScreen(), s -> s.handleCharacterInput(character));
            case FOCUS_BATTLE -> true; // no text entry in a battle; keep it away from chat/search fields
            default -> false;
        };
        if (!consumed && inputHandler != null) {
            inputHandler.handleCharacterInput(character);
        }
    }

    // ─── Mouse ────────────────────────────────────────────────────────────────

    public void onMouseButton(int button, int action, int mods) {
        Game game = Game.getInstance();
        if (GameUiInput.get().active() && withUiCursor((x, y) ->
                GameUiInput.get().onMouseButton(x, y, button, action, mods, cursorCaptured(game)))) {
            return;
        }
        // The battle HUD document takes its own clicks; the battle swallows the rest so nothing
        // reaches the world.
        boolean consumed = game.getState() == GameState.FOCUS_BATTLE;
        if (!consumed && inputHandler != null) {
            inputHandler.processMouseButton(button, action, mods);
        }
    }

    /**
     * @param rawX cursor position in window coordinates — camera look consumes raw deltas, so the
     *             capture manager must not see UI-scaled values
     */
    public void onMouseMove(double rawX, double rawY) {
        Game game = Game.getInstance();

        MouseCaptureManager mouseCapture = game.getMouseCaptureManager();
        if (mouseCapture != null) {
            mouseCapture.processMouseMovement(rawX, rawY);
        }

        // UI hit-testing happens in framebuffer-pixel space.
        double x = window.toUiX(rawX);
        double y = window.toUiY(rawY);

        if (inputHandler != null) {
            inputHandler.updateMousePosition((float) x, (float) y);
        }
        GameUiInput.get().onMouseMove(x, y, cursorCaptured(game));
    }

    public void onScroll(double xOffset, double yOffset) {
        Game game = Game.getInstance();
        int mods = heldMods();
        if (GameUiInput.get().active() && withUiCursor((x, y) ->
                GameUiInput.get().onScroll(x, y, xOffset, yOffset, mods, cursorCaptured(game)))) {
            return;
        }
        GameState state = game.getState();
        boolean consumed = state != null && switch (state) {
            case FOCUS_BATTLE -> true; // swallowed: the hotbar must not cycle under the battle
            default -> false;
        };
        if (!consumed && inputHandler != null) {
            // Hotbar selection and other in-game scroll interactions.
            inputHandler.handleScroll(yOffset);
        }
    }

    // ─── Per-frame polling ────────────────────────────────────────────────────

    /**
     * Once per frame: the open documents' router tick, then, in a world, {@link InputHandler}'s look
     * at held keys (it decides for itself what applies while the world is paused for a UI). The menu
     * states are documents and take their input as events; the Focus battle is event-driven only, as
     * polling {@link InputHandler} would run the gameplay and UI-toggle keys (Escape, E, C, T, Q,
     * movement) underneath it.
     */
    public void pollActiveScreen() {
        Game game = Game.getInstance();
        GameUiInput.get().frame(cursorCaptured(game));
        GameState state = game.getState();
        if (state == null) {
            return;
        }
        switch (state) {
            case PLAYING, PAUSED, WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI, CHARACTER_SHEET_UI, FURNACE_UI ->
                    pollInGame(game);
            default -> { }
        }
    }

    private void pollInGame(Game game) {
        if (inputHandler == null) {
            return;
        }
        // The in-game screens (inventory, character sheet, workbench, furnace, recipe book) are UI
        // documents that take their own pointer input; InputHandler polls the keys around them.
        Player player = game.getPlayer();
        if (player != null) {
            inputHandler.handleInput(player);
        }
    }

    /**
     * The battle HUD owns every key while {@link GameState#FOCUS_BATTLE} is up, so the event is always
     * consumed. An Escape press the HUD declines (nothing bound yet, or a HUD that does not handle it)
     * still opens the pause menu, so the player can never be trapped in the state.
     *
     * <p>The HUD only sees REPEAT/RELEASE for keys it saw go down. A key held across the state switch
     * (the Enter that submitted {@code /battle}, the Escape that resumed from the pause menu) would
     * otherwise auto-repeat straight into "confirm" or "open pause menu".</p>
     */
    private boolean routeBattleKey(Game game, int key, int action, int mods) {
        if (key >= 0) {
            if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                battleKeysDown.set(key);
            } else if (!battleKeysDown.get(key)) {
                return true;
            } else if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
                battleKeysDown.clear(key);
            }
        }
        com.stonebreak.ui.focusBattle.FocusBattleScreen screen = game.getFocusBattleScreen();
        boolean handled = screen != null && com.stonebreak.battle.stage.FocusBattle.guardHudInput("hud key",
                () -> screen.handleKeyInput(key, action, mods));
        if (!handled && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                && action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            com.stonebreak.battle.stage.FocusBattle.requestPauseMenu();
        }
        return true;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Reads the position given to a UI handler exactly once per event. */
    @FunctionalInterface
    private interface CursorConsumer {
        boolean accept(double uiX, double uiY);
    }

    private boolean withUiCursor(CursorConsumer consumer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);
            DoubleBuffer y = stack.mallocDouble(1);
            window.uiCursorPos(x, y);
            return consumer.accept(x.get(0), y.get(0));
        }
    }

    /** Modifier bits held right now; GLFW's scroll callback carries none. */
    private int heldMods() {
        long h = window.handle();
        int mods = 0;
        if (PolledKeys.physicallyDown(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
                || PolledKeys.physicallyDown(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            mods |= org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
        }
        if (PolledKeys.physicallyDown(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL)
                || PolledKeys.physicallyDown(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            mods |= org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
        }
        if (PolledKeys.physicallyDown(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT)
                || PolledKeys.physicallyDown(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT)) {
            mods |= org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
        }
        return mods;
    }

    private static boolean cursorCaptured(Game game) {
        MouseCaptureManager capture = game.getMouseCaptureManager();
        return capture != null && capture.isMouseCaptured();
    }

    /** Invokes {@code action} on {@code screen} unless it does not exist yet. */
    private static <T> boolean dispatch(T screen, Consumer<T> action) {
        if (screen == null) {
            return false;
        }
        action.accept(screen);
        return true;
    }
}
