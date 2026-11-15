package com.stonebreak.input;

import java.util.Arrays;

import com.openmason.engine.input.ActionMap;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;

/**
 * Stonebreak's polled gameplay actions (#96): the game declares the action set and its default keys,
 * the engine's {@link ActionMap} owns matching and the settings wire form. The defaults are exactly
 * the keys that used to be hard-coded in the handlers. Developer function keys (F1-F8) stay plain key
 * constants in {@link DebugKeyHandler}; UI navigation is the UI layer's {@code UiActionMap}.
 */
public enum GameplayAction {

    MOVE_FORWARD("move.forward", GLFW_KEY_W),
    MOVE_BACK("move.back", GLFW_KEY_S),
    MOVE_LEFT("move.left", GLFW_KEY_A),
    MOVE_RIGHT("move.right", GLFW_KEY_D),
    JUMP("move.jump", GLFW_KEY_SPACE),
    /** Sprint on the ground, faster flight; also gets out of a seat. */
    SPRINT("move.sprint", GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT),
    /** Crouch, flight descent, and the stealth toggle (on its press). */
    CROUCH("move.crouch", GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL),
    DODGE("combat.dodge", GLFW_KEY_LEFT_ALT),
    /** Rampage / Snare / Leyline Breach / Mirrored Deceit / Shadow Step, by class. */
    ABILITY_PRIMARY("combat.ability.primary", GLFW_KEY_R),
    /** Skull Crusher / Culling Shot / Null Spike / Fracture / Caltrop Scatter, by class. */
    ABILITY_SECONDARY("combat.ability.secondary", GLFW_KEY_F),
    /** Close the topmost surface, else toggle the pause menu. */
    PAUSE("ui.pause", GLFW_KEY_ESCAPE),
    INVENTORY("ui.inventory", GLFW_KEY_E),
    CHARACTER("ui.character", GLFW_KEY_C),
    CHAT("ui.chat", GLFW_KEY_T),
    DROP_ITEM("item.drop", GLFW_KEY_Q),
    HOTBAR_1("hotbar.1", GLFW_KEY_1),
    HOTBAR_2("hotbar.2", GLFW_KEY_1 + 1),
    HOTBAR_3("hotbar.3", GLFW_KEY_1 + 2),
    HOTBAR_4("hotbar.4", GLFW_KEY_1 + 3),
    HOTBAR_5("hotbar.5", GLFW_KEY_1 + 4),
    HOTBAR_6("hotbar.6", GLFW_KEY_1 + 5),
    HOTBAR_7("hotbar.7", GLFW_KEY_1 + 6),
    HOTBAR_8("hotbar.8", GLFW_KEY_1 + 7),
    HOTBAR_9("hotbar.9", GLFW_KEY_1 + 8);

    /** Every action, by its wire id. */
    public static final ActionMap.ActionSet<GameplayAction> ACTIONS =
            ActionMap.ActionSet.ofEnum(GameplayAction.class, GameplayAction::id);

    private static final GameplayAction[] HOTBAR = {
        HOTBAR_1, HOTBAR_2, HOTBAR_3, HOTBAR_4, HOTBAR_5, HOTBAR_6, HOTBAR_7, HOTBAR_8, HOTBAR_9};

    private static final ActionMap<GameplayAction> DEFAULTS = createDefaults();

    private final String id;
    private final int[] defaultKeys;

    GameplayAction(String id, int... defaultKeys) {
        this.id = id;
        this.defaultKeys = defaultKeys;
    }

    /** Stable settings id ({@code "move.forward"}). */
    public String id() {
        return id;
    }

    /** The hotbar-slot action for slot {@code index} (0-based), or null past slot 9. */
    public static GameplayAction hotbar(int index) {
        return index >= 0 && index < HOTBAR.length ? HOTBAR[index] : null;
    }

    /** Today's keys: every action bound with any modifiers held. */
    public static ActionMap<GameplayAction> defaults() {
        return DEFAULTS;
    }

    private static ActionMap<GameplayAction> createDefaults() {
        ActionMap<GameplayAction> map = ActionMap.empty(ACTIONS);
        for (GameplayAction a : values()) {
            map = map.withAnyKeys(a, Arrays.copyOf(a.defaultKeys, a.defaultKeys.length));
        }
        return map;
    }
}
