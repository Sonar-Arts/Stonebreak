package com.stonebreak.input;

import java.nio.DoubleBuffer;
import java.util.function.Consumer;

import org.lwjgl.system.MemoryStack;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.core.window.GameWindow;
import com.stonebreak.player.Player;

/**
 * Routes raw window input to whichever screen the current {@link GameState} puts in front.
 *
 * <p>Every handler answers the same question — "does a menu own this event, or does it fall through
 * to {@link InputHandler}?" — so the GLFW callbacks stay one line each and the state-to-screen
 * mapping lives in exactly one place. Screens are consulted through {@link #dispatch}, which absorbs
 * the not-yet-constructed case; a screen that is absent simply does not consume the event.</p>
 */
public final class MenuInputRouter {

    private final GameWindow window;
    private InputHandler inputHandler;

    public MenuInputRouter(GameWindow window) {
        this.window = window;
    }

    /** Set once the input handler exists; until then everything falls through to the menus only. */
    public void setInputHandler(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    // ─── Keyboard ─────────────────────────────────────────────────────────────

    public void onKey(int key, int action, int mods) {
        Game game = Game.getInstance();
        GameState state = game.getState();
        boolean consumed = state != null && switch (state) {
            case WORLD_SELECT -> dispatch(game.getWorldSelectScreen(), s -> s.handleKeyInput(key, action, mods));
            case TERRAIN_MAPPER -> dispatch(game.getTerrainMapperScreen(), s -> s.handleKeyInput(key, action, mods));
            case HOST_WORLD_SELECT -> dispatch(game.getHostWorldScreen(), s -> s.handleKeyInput(key, action, mods));
            case JOIN_WORLD_SCREEN -> dispatch(game.getJoinWorldScreen(), s -> s.handleKeyInput(key, action, mods));
            default -> false;
        };
        if (!consumed && inputHandler != null) {
            inputHandler.handleKeyInput(key, action, mods);
        }
    }

    public void onCharacter(int codepoint) {
        // Drop codepoints outside the BMP; casting them to a single char would produce an unpaired
        // surrogate that crashes Skija's text layout on the next measureTextWidth call.
        if (codepoint < 0 || codepoint > 0xFFFF || Character.isSurrogate((char) codepoint)) {
            return;
        }
        char character = (char) codepoint;

        Game game = Game.getInstance();
        GameState state = game.getState();
        boolean consumed = state != null && switch (state) {
            case WORLD_SELECT -> dispatch(game.getWorldSelectScreen(), s -> s.handleCharacterInput(character));
            case TERRAIN_MAPPER -> dispatch(game.getTerrainMapperScreen(), s -> s.handleCharacterInput(character));
            case HOST_WORLD_SELECT -> dispatch(game.getHostWorldScreen(), s -> s.handleCharInput(character));
            case JOIN_WORLD_SCREEN -> dispatch(game.getJoinWorldScreen(), s -> s.handleCharInput(character));
            default -> false;
        };
        if (!consumed && inputHandler != null) {
            inputHandler.handleCharacterInput(character);
        }
    }

    // ─── Mouse ────────────────────────────────────────────────────────────────

    public void onMouseButton(int button, int action, int mods) {
        Game game = Game.getInstance();
        GameState state = game.getState();
        int width = window.width();
        int height = window.height();

        boolean consumed = state != null && switch (state) {
            case STARTUP_INTRO -> isLeftPress(button, action)
                    && dispatch(game.getStartupIntroScreen(), s -> s.skipToMainMenu());
            // The main menu only reacts to a left press, but it still owns the event either way.
            case MAIN_MENU -> !isLeftPress(button, action) || withUiCursor((x, y) ->
                    dispatch(game.getMainMenu(), s -> s.handleMouseClick(x, y, width, height)));
            case WORLD_SELECT -> withUiCursor((x, y) -> dispatch(game.getWorldSelectScreen(),
                    s -> s.handleMouseClick(x, y, width, height, button, action)));
            case SETTINGS -> withUiCursor((x, y) -> dispatch(game.getSettingsMenu(),
                    s -> s.handleMouseClick(x, y, width, height, button, action)));
            case CHARACTER_CREATION -> withUiCursor((x, y) -> dispatch(game.getCharacterCreationScreen(),
                    s -> s.handleMouseClick(x, y, width, height, button, action)));
            case TERRAIN_MAPPER -> withUiCursor((x, y) -> dispatch(game.getTerrainMapperScreen(),
                    s -> s.handleMouseClick(x, y, width, height, button, action)));
            case MULTIPLAYER_MENU -> withUiCursor((x, y) -> dispatch(game.getMultiplayerMenu(),
                    s -> s.handleMouseClick(x, y, width, height, button, action)));
            case HOST_WORLD_SELECT -> withUiCursor((x, y) -> dispatch(game.getHostWorldScreen(),
                    s -> s.handleMouseClick(x, y, width, height, button, action)));
            case JOIN_WORLD_SCREEN -> withUiCursor((x, y) -> dispatch(game.getJoinWorldScreen(),
                    s -> s.handleMouseClick(x, y, width, height, button, action)));
            default -> false;
        };
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
        int width = window.width();
        int height = window.height();

        if (inputHandler != null) {
            inputHandler.updateMousePosition((float) x, (float) y);
        }

        GameState state = game.getState();
        if (state == null) {
            return;
        }
        switch (state) {
            case MAIN_MENU -> dispatch(game.getMainMenu(), s -> s.handleMouseMove(x, y, width, height));
            case WORLD_SELECT -> dispatch(game.getWorldSelectScreen(), s -> s.handleMouseMove(x, y, width, height));
            case SETTINGS -> dispatch(game.getSettingsMenu(), s -> s.handleMouseMove(x, y, width, height));
            case CHARACTER_CREATION ->
                    dispatch(game.getCharacterCreationScreen(), s -> s.handleMouseMove(x, y, width, height));
            case TERRAIN_MAPPER ->
                    dispatch(game.getTerrainMapperScreen(), s -> s.handleMouseMove(x, y, width, height));
            case MULTIPLAYER_MENU -> dispatch(game.getMultiplayerMenu(), s -> s.handleMouseMove(x, y, width, height));
            case HOST_WORLD_SELECT -> dispatch(game.getHostWorldScreen(), s -> s.handleMouseMove(x, y, width, height));
            case JOIN_WORLD_SCREEN -> dispatch(game.getJoinWorldScreen(), s -> s.handleMouseMove(x, y, width, height));
            default -> { }
        }
    }

    public void onScroll(double yOffset) {
        Game game = Game.getInstance();
        GameState state = game.getState();
        boolean consumed = state != null && switch (state) {
            // The world list scrolls wherever the pointer is, so it needs no cursor position.
            case WORLD_SELECT -> dispatch(game.getWorldSelectScreen(), s -> s.handleMouseWheel(yOffset));
            case CHARACTER_CREATION -> withUiCursor((x, y) -> dispatch(game.getCharacterCreationScreen(),
                    s -> s.handleMouseWheel(x, y, yOffset)));
            case TERRAIN_MAPPER -> withUiCursor((x, y) -> dispatch(game.getTerrainMapperScreen(),
                    s -> s.handleMouseWheel(x, y, yOffset)));
            case SETTINGS -> withUiCursor((x, y) -> dispatch(game.getSettingsMenu(),
                    s -> s.handleMouseWheel(x, y, yOffset)));
            default -> false;
        };
        if (!consumed && inputHandler != null) {
            // Hotbar selection and other in-game scroll interactions.
            inputHandler.handleScroll(yOffset);
        }
    }

    // ─── Per-frame polling ────────────────────────────────────────────────────

    /**
     * Gives the active screen its once-per-frame look at held keys and mouse state. Menus poll the
     * window directly; in-game states go through {@link InputHandler}, which decides for itself what
     * applies while the world is paused for a UI.
     */
    public void pollActiveScreen() {
        Game game = Game.getInstance();
        GameState state = game.getState();
        if (state == null) {
            return;
        }
        long handle = window.handle();

        switch (state) {
            case STARTUP_INTRO -> dispatch(game.getStartupIntroScreen(), s -> s.handleInput(handle));
            case MAIN_MENU -> dispatch(game.getMainMenu(), s -> s.handleInput(handle));
            case WORLD_SELECT -> dispatch(game.getWorldSelectScreen(), s -> s.handleInput(handle));
            case CHARACTER_CREATION -> dispatch(game.getCharacterCreationScreen(), s -> s.handleInput(handle));
            case TERRAIN_MAPPER -> dispatch(game.getTerrainMapperScreen(), s -> s.handleInput(handle));
            case LOADING -> dispatch(game.getLoadingScreen(), s -> s.handleInput(handle));
            case SETTINGS -> dispatch(game.getSettingsMenu(), s -> s.handleInput(handle));
            case MULTIPLAYER_MENU -> dispatch(game.getMultiplayerMenu(), s -> s.handleInput(handle));
            case HOST_WORLD_SELECT -> dispatch(game.getHostWorldScreen(), s -> s.handleInput(handle));
            case JOIN_WORLD_SCREEN -> dispatch(game.getJoinWorldScreen(), s -> s.handleInput(handle));
            case PLAYING, PAUSED, WORKBENCH_UI, RECIPE_BOOK_UI, INVENTORY_UI, CHARACTER_SHEET_UI, FURNACE_UI ->
                    pollInGame(game);
            default -> { }
        }
    }

    private void pollInGame(Game game) {
        if (inputHandler == null) {
            return;
        }
        // Inventory and character-screen input is handled inside InputHandler.handleInput below —
        // calling their mouse handlers here too breaks single-click drag.
        if (game.getRecipeBookScreen() != null && game.getRecipeBookScreen().isVisible()) {
            game.getRecipeBookScreen().handleInput();
        } else if (game.getWorkbenchScreen() != null && game.getWorkbenchScreen().isVisible()) {
            game.getWorkbenchScreen().handleInput(inputHandler);
        }
        Player player = game.getPlayer();
        if (player != null) {
            inputHandler.handleInput(player);
        }
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

    /** Invokes {@code action} on {@code screen} unless it does not exist yet. */
    private static <T> boolean dispatch(T screen, Consumer<T> action) {
        if (screen == null) {
            return false;
        }
        action.accept(screen);
        return true;
    }

    private static boolean isLeftPress(int button, int action) {
        return button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
                && action == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }
}
