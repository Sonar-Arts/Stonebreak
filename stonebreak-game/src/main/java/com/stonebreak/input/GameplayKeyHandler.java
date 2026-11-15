package com.stonebreak.input;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.player.Player;

import com.openmason.engine.input.ActionKeys;
import com.openmason.engine.input.InputCodes;
import com.openmason.engine.input.InputState;


/**
 * Per-frame gameplay input while in the PLAYING state: movement, flight,
 * dodge/stealth, class ability casts, continuous block breaking, and hotbar
 * slot keys. Every key is a {@link GameplayAction} (#96); the defaults are WASD,
 * Space, Shift, Ctrl, Left Alt, R, F and 1–9.
 */
final class GameplayKeyHandler {

    private final ActionKeys<GameplayAction> keys;
    private final InputState mouse;
    private final HotbarSelector hotbar;

    // Stealth toggles on the crouch action going down while either of its keys is
    // held (held state, not a per-key edge), so that state is tracked here directly.
    private boolean stealthKeyHeld;

    GameplayKeyHandler(ActionKeys<GameplayAction> keys, InputState mouse, HotbarSelector hotbar) {
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
            keys.reset(GameplayAction.ABILITY_PRIMARY);
            keys.reset(GameplayAction.ABILITY_SECONDARY);
            return;
        }

        if (keys.pressedOnce(GameplayAction.ABILITY_PRIMARY)) {
            player.getBerserkerAbilities().tryCastRampage(player);
            player.getRangerAbilities().tryCastSnare(player);
            player.getArcanistAbilities().tryCastLeylineBreach(player);
            player.getIllusionistAbilities().tryCastMirroredDeceit(player);
            player.getRogueAbilities().tryCastShadowStep(player);
        }

        if (keys.pressedOnce(GameplayAction.ABILITY_SECONDARY)) {
            player.getBerserkerAbilities().tryCastSkullCrusher(player, player.getRaycastEngine());
            player.getRangerAbilities().tryCastCullingShot(player);
            player.getArcanistAbilities().tryCastNullSpike(player);
            player.getIllusionistAbilities().tryCastFracture(player);
            player.getRogueAbilities().tryCastCaltropScatter(player);
        }
    }

    /** Runs the PLAYING-state per-frame input: movement, flight, breaking, hotbar keys. */
    void processPlaying(Player player) {
        boolean moveForward = keys.held(GameplayAction.MOVE_FORWARD);
        boolean moveBackward = keys.held(GameplayAction.MOVE_BACK);
        boolean moveLeft = keys.held(GameplayAction.MOVE_LEFT);
        boolean moveRight = keys.held(GameplayAction.MOVE_RIGHT);
        boolean jump = keys.held(GameplayAction.JUMP);
        boolean shift = keys.held(GameplayAction.SPRINT);
        boolean crouch = keys.held(GameplayAction.CROUCH);

        // Seated in a vehicle: WASD pulls the oars (in the rower's seat), Shift gets out. No
        // walking, dodging, stealth or flight from a seat.
        if (player.getRiding().isRiding()) {
            player.getRiding().setKeys(moveForward, moveBackward, moveLeft, moveRight);
            if (keys.pressedOnce(GameplayAction.SPRINT)) {
                player.getRiding().requestDismount(player);
            }
            processWorldActions(player);
            return;
        }

        // Universal dodge (all classes): edge-triggered dash (Left Alt by default). Read here, after
        // the WASD state is captured this frame, so the dash follows live input rather than
        // residual momentum. Fires independent of the movement-lock guard below.
        if (keys.pressedOnce(GameplayAction.DODGE)) {
            player.tryDodge(moveForward, moveBackward, moveLeft, moveRight);
        }

        // Universal stealth toggle (all classes): edge-triggered on crouch (either Ctrl by default). Not while
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

        processWorldActions(player);
    }

    /** Continuous block breaking while the left button is held, and the hotbar number keys. */
    private void processWorldActions(Player player) {
        if (mouse.isDown(InputCodes.MOUSE_BUTTON_LEFT)) {
            player.startBreakingBlock();
        } else {
            player.stopBreakingBlock();
        }

        hotbar.pollNumberKeys(keys);
    }
}
