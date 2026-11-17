package com.stonebreak.player.physics;

import com.openmason.engine.physics.PhysicsBody;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import com.stonebreak.world.physics.WorldPhysics;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT;
import static com.stonebreak.player.PlayerConstants.PLAYER_WIDTH;

/**
 * The player's body in the engine physics ({@link WorldPhysics}, OpenMason #117): one swept move
 * per frame that slides along walls, climbs steps while standing (anything up to half a block,
 * snow layers and stair treads up to a full block) and mantles onto ledges mid-jump by the same
 * limits, lands, and drops the ground flag after walking off an edge. Door panels and scene colliders are walls; animated cells, torches and stalagmite
 * cells collide through their models.
 *
 * <p>A horizontal hit zeroes that axis' velocity (no sticking); a vertical hit zeroes the vertical.
 */
public class CollisionHandler {

    /** Speed given to a standing body that just walked off an edge, so gravity takes over. */
    private static final float LEFT_GROUND_NUDGE = -0.1f;

    private final PhysicsState state;
    private World world;
    private final PhysicsBody body = new PhysicsBody()
            .box(PLAYER_WIDTH / 2, PLAYER_WIDTH / 2, 0f, PLAYER_HEIGHT)
            .steps(WorldPhysics.PLAYER_STEP, WorldPhysics.PLAYER_PARTIAL_STEP);

    public CollisionHandler(PhysicsState state, World world) {
        this.state = state;
        this.world = world;
        body.mask = WorldPhysics.PLAYER_MASK;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    /**
     * Moves the player by its velocity times {@code dt} through the world and updates the ground
     * flag. Contacts of the move are left in {@link #lastContacts()}.
     */
    public void move(float dt) {
        Vector3f position = state.getPosition();
        Vector3f velocity = state.getVelocity();
        if (world == null) {
            position.add(velocity.x * dt, velocity.y * dt, velocity.z * dt);
            return;
        }
        body.position(position.x, position.y, position.z).velocity(velocity.x, velocity.y, velocity.z);
        // AIR_STEP: a jump peaks just under one block (~0.95 at 60 fps), so hopping onto a block
        // relies on mantling — once the feet are within the step limits of a ledge's top (half a
        // block, a full block for snow/treads), the player climbs onto it, as the old resolver did.
        body.flags = PhysicsBody.AIR_STEP | (state.isOnGround() ? PhysicsBody.ON_GROUND : 0);
        com.stonebreak.world.physics.WorldPhysics.of(world).move(body, dt);
        position.set(body.x, body.y, body.z);
        velocity.set(body.vx, body.vy, body.vz);
        state.setOnGround(body.onGround());
        if (body.hasContact(PhysicsBody.CONTACT_LEFT_GROUND) && velocity.y == 0f) {
            velocity.y = LEFT_GROUND_NUDGE;
        }
    }

    /** {@code PhysicsBody.CONTACT_*} of the last {@link #move}. */
    public int lastContacts() {
        return body.contacts;
    }

    /**
     * Returns true if the player's box currently overlaps anything it collides with. Used to warn
     * the player that exiting spectator mode will leave them stuck.
     */
    public boolean isPlayerInsideSolidBlock() {
        if (world == null) {
            return false;
        }
        Vector3f p = state.getPosition();
        float half = PLAYER_WIDTH / 2;
        return com.stonebreak.world.physics.WorldPhysics.of(world).overlaps(p.x - half, p.y, p.z - half, p.x + half, p.y + PLAYER_HEIGHT, p.z + half,
                WorldPhysics.PLAYER_MASK);
    }
}
