package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT;
import static com.stonebreak.player.PlayerConstants.PLAYER_WIDTH;

import com.stonebreak.blocks.cactus.CactusContactRules;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;

/**
 * Applies eighth-heart contact damage while the player's body touches a cactus:
 * one pulse ({@link CactusContactRules#DAMAGE_PER_PULSE}) every
 * {@link CactusContactRules#PULSE_INTERVAL} in contact. World-bound controller —
 * re-pointed at world change like {@code CollisionHandler}. The same gates
 * {@code FallDamageHandler} respect apply here: no damage while flying or under
 * spawn protection.
 *
 * <p>Timer pattern follows {@code FallDamageHandler}/{@code LeylineBreachZone}: the
 * check itself throttles the pulses (the player's only i-frames are dodge's), so the
 * contact probe re-tests the footprint cells each tick and the timer accumulates the
 * interval between them.
 */
public class CactusContactDamage {

    private final PhysicsState state;
    private final HealthController health;
    private World world;
    private float timer;

    public CactusContactDamage(World world, PhysicsState state, HealthController health) {
        this.world = world;
        this.state = state;
        this.health = health;
    }

    /** Re-points this controller at {@code world} (see {@code PlayerControllers.setWorld}). */
    public void setWorld(World world) {
        this.world = world;
    }

    public void update(float deltaTime, boolean flying) {
        if (world == null || flying || health.hasSpawnProtection()) {
            return;
        }
        if (!inContact()) {
            timer = 0f;
            return;
        }
        timer += deltaTime;
        if (timer < CactusContactRules.PULSE_INTERVAL) {
            return;
        }
        timer -= CactusContactRules.PULSE_INTERVAL; // keep remainders so pulses stay even
        health.damage(CactusContactRules.DAMAGE_PER_PULSE);
    }

    /**
     * True when any cactus cell touches the player's body: the standing footprint
     * cells expanded by the probe margin, so a body flush against the solid spiky
     * cell (or resting on its tip) reads as contact.
     */
    private boolean inContact() {
        var position = state.getPosition();
        float halfWidth = PLAYER_WIDTH / 2;
        return CactusContactRules.touchesCactus(world,
                position.x - halfWidth, position.y, position.z - halfWidth,
                position.x + halfWidth, position.y + PLAYER_HEIGHT, position.z + halfWidth);
    }
}
