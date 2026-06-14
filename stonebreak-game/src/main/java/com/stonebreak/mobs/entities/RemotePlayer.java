package com.stonebreak.mobs.entities;

import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.sbe.PlayerStateMapping;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * A remote (network) player. Updated entirely from incoming
 * {@link com.stonebreak.network.packet.player.PlayerStateS2C} packets,
 * not local physics or AI.
 */
public class RemotePlayer extends LivingEntity {

    private static final float WALK_THRESHOLD = 0.05f; // blocks/frame to count as walking

    private final int playerId;
    private final String username;
    private float pitch;
    /** Block/item id this remote player is currently holding. 0 = empty/air. */
    private volatile int heldItemId;

    private final AnimationController animationController;
    private PlayerStateMapping.PlayerMovementState movementState = PlayerStateMapping.PlayerMovementState.IDLE;
    private Vector3f prevPosition;

    public RemotePlayer(World world, Vector3f position, int playerId, String username) {
        super(world, position, EntityType.REMOTE_PLAYER);
        this.playerId = playerId;
        this.username = username;
        this.animationController = new AnimationController(this);
        this.prevPosition = new Vector3f(position);
    }

    public int getPlayerId() { return playerId; }
    public String getUsername() { return username; }
    public float getPitch() { return pitch; }
    public int getHeldItemId() { return heldItemId; }
    public void setHeldItemId(int id) { this.heldItemId = id; }

    public AnimationController getAnimationController() { return animationController; }
    public PlayerStateMapping.PlayerMovementState getMovementState() { return movementState; }

    /**
     * Apply an authoritative state update from the network.
     * If an interpolator is attached the new state is queued as the next
     * target sample (smoothed across the next snapshot interval); otherwise
     * the position is set immediately.
     */
    public void applyNetworkState(float x, float y, float z, float yaw, float pitch) {
        com.stonebreak.network.client.NetworkInterpolator interp = getInterpolator();
        if (interp != null) {
            interp.receive(x, y, z, yaw, pitch, this);
        } else {
            this.position.set(x, y, z);
            this.rotation.y = yaw;
        }
        this.pitch = pitch;
    }

    @Override
    public void update(float deltaTime) {
        // Skip default physics/AI; remote players are network-driven.
        age += deltaTime;

        // Derive walk/idle from horizontal displacement since last frame.
        float dx = position.x - prevPosition.x;
        float dz = position.z - prevPosition.z;
        float horizDist = (float) Math.sqrt(dx * dx + dz * dz);
        movementState = horizDist > WALK_THRESHOLD
                ? PlayerStateMapping.PlayerMovementState.WALKING
                : PlayerStateMapping.PlayerMovementState.IDLE;
        prevPosition.set(position);

        animationController.updateAnimations(deltaTime);
    }

    @Override
    protected void updateAI(float deltaTime) {
        // No AI.
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering is handled centrally by EntityRenderer dispatch.
    }

    @Override
    public EntityType getType() { return EntityType.REMOTE_PLAYER; }

    // RemotePlayer is a display-only proxy for a server-authoritative remote client.
    // Interaction, damage, and death are resolved server-side and broadcast via packets;
    // the local ghost intentionally ignores these events.
    @Override
    public void onInteract(Player player) {}

    @Override
    public void onDamage(float damage, DamageSource source) {}

    @Override
    protected void onDeath() {}

    @Override
    public ItemStack[] getDrops() { return new ItemStack[0]; }
}
