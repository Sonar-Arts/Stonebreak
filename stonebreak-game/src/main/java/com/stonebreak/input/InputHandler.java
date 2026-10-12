package com.stonebreak.input;

import org.joml.Vector2f;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.player.Player;
import com.stonebreak.ui.characterScreen.CharacterScreen;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.workbench.WorkbenchScreen;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * Facade for player input. Owns the raw input state and delegates to focused
 * collaborators; Main's GLFW callbacks and the game loop only talk to this
 * class, and UI screens poll it for mouse/key state.
 *
 * Collaborators (all package-private, in this package):
 * <ul>
 *   <li>{@link MouseInputState} — button/cursor/scroll state</li>
 *   <li>{@link KeyEdgeTracker} — polled key edge detection</li>
 *   <li>{@link UiToggleKeyHandler} — Escape/E/C/T/Q UI toggles</li>
 *   <li>{@link GameplayKeyHandler} — movement, flight, abilities, breaking, hotbar keys</li>
 *   <li>{@link DebugKeyHandler} — F3–F8 development keys</li>
 *   <li>{@link UiMouseRouter} — click routing to active UI surfaces</li>
 *   <li>{@link WorldMouseHandler} — attack/use clicks that reach the world</li>
 *   <li>{@link ChatInputRouter} — keys routed to an open chat</li>
 *   <li>{@link HotbarSelector} — hotbar slot selection (keys + scroll)</li>
 * </ul>
 */
public class InputHandler {

    private final MouseInputState mouse = new MouseInputState();
    private final KeyEdgeTracker keys;
    private final HotbarSelector hotbar = new HotbarSelector();
    private final UiToggleKeyHandler uiToggleKeys;
    private final GameplayKeyHandler gameplayKeys;
    private final DebugKeyHandler debugKeys;
    private final ChatInputRouter chatRouter;
    private final UiMouseRouter mouseRouter;

    public InputHandler(long window) {
        this.keys = new KeyEdgeTracker(window);
        this.uiToggleKeys = new UiToggleKeyHandler(keys);
        this.gameplayKeys = new GameplayKeyHandler(window, keys, mouse, hotbar);
        this.debugKeys = new DebugKeyHandler(keys);
        this.chatRouter = new ChatInputRouter(keys);
        this.mouseRouter = new UiMouseRouter(new WorldMouseHandler());
    }

    /**
     * Call when a key-event driven surface (the Focus battle HUD) hands control back to the polled
     * in-game input: toggle keys still held from that surface (the Escape that opened the pause
     * menu, the E that confirmed "Explore arena") are ignored until released, instead of firing
     * again as a fresh press on the next poll.
     */
    public void suppressHeldUiToggleKeys() {
        uiToggleKeys.suppressUntilReleased();
    }

    /** Call at the START of each frame's input processing cycle. */
    public void prepareForNewFrame() {
        mouse.beginFrame();
    }

    /** Per-frame polled input, called from the game loop after {@link #prepareForNewFrame()}. */
    public void handleInput(Player player) {
        if (player == null) {
            return;
        }

        try {
            // An open chat owns all input; its own key/char callbacks handle everything.
            ChatSystem chatSystem = Game.getInstance().getChatSystem();
            if (chatSystem != null && chatSystem.isOpen()) {
                return;
            }

            // System-level toggles first, as they might change the active UI.
            uiToggleKeys.pollEscape();
            uiToggleKeys.pollInventoryToggle();
            uiToggleKeys.pollCharacterToggle();
            gameplayKeys.pollClassAbilityKeys(player);
            uiToggleKeys.pollChatOpen();
            uiToggleKeys.pollItemDrop();
            debugKeys.poll();

            // The in-game screens' documents take their own pointer input (#298-#301).
            Game game = Game.getInstance();
            GameState state = game.getState();

            RecipeScreen recipeScreen = game.getRecipeBookScreen();
            if (state == GameState.RECIPE_BOOK_UI && recipeScreen != null && recipeScreen.isVisible()) {
                recipeScreen.handleInput();
                return;
            }

            // Movement and world actions only while actually playing.
            if (state == GameState.PLAYING) {
                gameplayKeys.processPlaying(player);
            }
        } catch (Exception e) {
            System.err.println("Error processing input: " + e.getMessage());
        }
    }

    /** GLFW mouse button callback receiver (wired in Main). */
    public void processMouseButton(int button, int action, int mods) {
        // Record raw state first so screens polling this frame see the event,
        // then route it to whichever UI surface (or the world) should react.
        mouse.onButtonEvent(button, action);
        mouseRouter.route(button, action);
    }

    /** GLFW scroll callback receiver: chat/screen scrolling first, else hotbar cycling. */
    public void handleScroll(double yOffset) {
        // Stored for UI screens that consume scroll via getAndResetScrollY (recipe book).
        mouse.setScroll(yOffset);

        Game game = Game.getInstance();
        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            chatSystem.handleScroll(yOffset);
            return;
        }

        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible()) {
            return; // its document takes the wheel; RecipeScreen drops the stored value
        }
        // An open inventory, crafting table or character sheet keeps the hotbar from cycling (the
        // character sheet's document takes the wheel).
        InventoryScreen inventoryScreen = game.getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            return;
        }
        WorkbenchScreen workbenchScreen = game.getWorkbenchScreen();
        if (workbenchScreen != null && workbenchScreen.isVisible()) {
            return;
        }
        CharacterScreen characterScreen = game.getCharacterScreen();
        if (characterScreen != null && characterScreen.isVisible()) {
            return;
        }

        if (game.getState() != GameState.PLAYING) {
            return;
        }
        hotbar.cycle(yOffset);
        mouse.resetScroll(); // consumed by hotbar selection
    }

    public double getAndResetScrollY() {
        return mouse.getAndResetScroll();
    }

    /** Updates the UI-space cursor position (called from Main's cursor callback). */
    public void updateMousePosition(float xpos, float ypos) {
        mouse.setPosition(xpos, ypos);
    }

    // ── Mouse state polling (used by UI screens) ─────────────────────────

    public Vector2f getMousePosition() {
        return mouse.position();
    }

    public boolean isMouseButtonPressed(int button) {
        return mouse.isPressed(button);
    }

    public boolean isMouseButtonDown(int button) {
        return mouse.isDown(button);
    }

    public void consumeMouseButtonPress(int button) {
        mouse.consumePress(button);
    }

    /**
     * Forgets all held/pressed mouse button state. Called when the game state
     * transitions back to PLAYING so clicks consumed by a menu (e.g. the pause
     * menu's Resume button) don't leak into gameplay as attacks/block breaking.
     */
    public void clearMouseButtonStates() {
        mouse.clearAll();
    }

    // ── Key/char callback routing ────────────────────────────────────────

    /** Routes character input to whichever text consumer is active (chat, recipe search). */
    public void handleCharacterInput(char character) {
        Game game = Game.getInstance();

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            chatSystem.handleCharInput(character);
            return;
        }

        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible() && game.getState() == GameState.RECIPE_BOOK_UI) {
            recipeScreen.handleCharacterInput(character);
        }
    }

    /** Routes key events to whichever text consumer is active (chat, recipe search). */
    public void handleKeyInput(int key, int action, int mods) {
        Game game = Game.getInstance();

        ChatSystem chatSystem = game.getChatSystem();
        if (chatSystem != null && chatSystem.isOpen()) {
            chatRouter.handleKeyInput(chatSystem, key, action);
            return; // chat blocks all other key processing
        }

        RecipeScreen recipeScreen = game.getRecipeBookScreen();
        if (recipeScreen != null && recipeScreen.isVisible() && game.getState() == GameState.RECIPE_BOOK_UI
                && (action == GLFW_PRESS || action == GLFW_REPEAT)) {
            recipeScreen.handleKeyInput(key, action);
        }
    }

    /** True while the key is physically held (direct GLFW query). */
    public boolean isKeyDown(int key) {
        return keys.isDown(key);
    }
}
