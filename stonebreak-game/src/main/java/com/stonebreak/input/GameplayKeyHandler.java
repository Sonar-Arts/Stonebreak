package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.player.Player;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Per-frame gameplay input while in the PLAYING state: WASD movement, flight,
 * dodge/stealth, class ability casts, continuous block breaking, and hotbar
 * number keys.
 */
final class GameplayKeyHandler {

    private final long window;
    private final KeyEdgeTracker keys;
    private final MouseInputState mouse;
    private final HotbarSelector hotbar;

    // Stealth edges on either Ctrl key (a composite the single-key tracker
    // can't represent), so its held state is tracked here directly.
    private boolean stealthKeyHeld;

    GameplayKeyHandler(long window, KeyEdgeTracker keys, MouseInputState mouse, HotbarSelector hotbar) {
        this.window = window;
        this.keys = keys;
        this.mouse = mouse;
        this.hotbar = hotbar;
    }

    /**
     * Class ability casts on shared keys: R = Rampage (Berserker) / Snare (Ranger) /
     * Leyline Breach (Arcanist) / Mirrored Deceit (Illusionist) / Shadow Step (Rogue),
     * F = Skull Crusher / Culling Shot / Null Spike / Fracture / Caltrop Scatter.
     * Every controller self-gates on the selected class and CP unlock, so each press
     * acts through at most one class and is harmless for the others.
     */
    void pollClassAbilityKeys(Player player) {
        if (Game.getInstance().getState() != GameState.PLAYING) {
            // Forget held state outside gameplay so a key held across the
            // transition back into PLAYING still casts immediately.
            keys.reset(GLFW_KEY_R);
            keys.reset(GLFW_KEY_F);
            return;
        }

        if (keys.pressedOnce(GLFW_KEY_R)) {
            player.getBerserkerAbilities().tryCastRampage(player);
            player.getRangerAbilities().tryCastSnare(player);
            player.getArcanistAbilities().tryCastLeylineBreach(player);
            player.getIllusionistAbilities().tryCastMirroredDeceit(player);
            player.getRogueAbilities().tryCastShadowStep(player);
        }

        if (keys.pressedOnce(GLFW_KEY_F)) {
            player.getBerserkerAbilities().tryCastSkullCrusher(player, player.getRaycastEngine());
            player.getRangerAbilities().tryCastCullingShot(player);
            player.getArcanistAbilities().tryCastNullSpike(player);
            player.getIllusionistAbilities().tryCastFracture(player);
            player.getRogueAbilities().tryCastCaltropScatter(player);
        }
    }

    /** Runs the PLAYING-state per-frame input: movement, flight, breaking, hotbar keys. */
    void processPlaying(Player player) {
        boolean moveForward = keys.isDown(GLFW_KEY_W);
        boolean moveBackward = keys.isDown(GLFW_KEY_S);
        boolean moveLeft = keys.isDown(GLFW_KEY_A);
        boolean moveRight = keys.isDown(GLFW_KEY_D);
        boolean jump = keys.isDown(GLFW_KEY_SPACE);
        boolean shift = keys.isDown(GLFW_KEY_LEFT_SHIFT) || keys.isDown(GLFW_KEY_RIGHT_SHIFT);
        boolean crouch = keys.isDown(GLFW_KEY_LEFT_CONTROL) || keys.isDown(GLFW_KEY_RIGHT_CONTROL);

        // Universal dodge (all classes): edge-triggered dash on Left Alt. Read here, after
        // the WASD state is captured this frame, so the dash follows live input rather than
        // residual momentum. Fires independent of the movement-lock guard below.
        if (keys.pressedOnce(GLFW_KEY_LEFT_ALT)) {
            player.tryDodge(moveForward, moveBackward, moveLeft, moveRight);
        }

        // Universal stealth toggle (all classes): edge-triggered on either Ctrl. Not while
        // flying (Ctrl also drives flight descent), where stealth has no meaning.
        if (crouch && !stealthKeyHeld) {
            stealthKeyHeld = true;
            if (!player.isFlying()) {
                player.getStealth().toggle(player);
            }
        } else if (!crouch) {
            stealthKeyHeld = false;
        }

        // Movement is suppressed mid-Rampage / mid-Skull-Crusher-windup / mid-Culling-
        // Shot-dash, which drive the player directly and would otherwise fight with
        // normal input-driven movement.
        if (!player.isAbilityMovementLocked()) {
            player.processMovement(moveForward, moveBackward, moveLeft, moveRight, jump, shift, crouch);
        }

        // Flight controls: Space for ascent, Ctrl for descent.
        if (player.isFlying()) {
            if (jump) {
                player.processFlightAscent(shift);
            }
            if (crouch) {
                player.processFlightDescent(shift);
            }
        }

        // Continuous block breaking while the left button is held.
        if (mouse.isDown(GLFW_MOUSE_BUTTON_LEFT)) {
            player.startBreakingBlock();
        } else {
            player.stopBreakingBlock();
        }

        hotbar.pollNumberKeys(window);
    }
}
