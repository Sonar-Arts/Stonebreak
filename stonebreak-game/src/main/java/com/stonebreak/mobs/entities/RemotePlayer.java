package com.stonebreak.mobs.entities;

import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * A remote (network) player. Updated entirely from incoming
 * {@link com.stonebreak.network.protocol.Packet.PlayerStateS2C} packets,
 * not local physics or AI.
 */
public class RemotePlayer extends LivingEntity {

    private final int playerId;
    private final String username;
    private float pitch;

    public RemotePlayer(World world, Vector3f position, int playerId, String username) {
        super(world, position, EntityType.REMOTE_PLAYER);
        this.playerId = playerId;
        this.username = username;
    }

    public int getPlayerId() { return playerId; }
    public String getUsername() { return username; }
    public float getPitch() { return pitch; }

    /** Apply an authoritative state update from the network. */
    public void applyNetworkState(float x, float y, float z, float yaw, float pitch) {
        this.position.set(x, y, z);
        this.rotation.y = yaw;
        this.pitch = pitch;
    }

    @Override
    public void update(float deltaTime) {
        // Skip default physics/AI; remote players are network-driven.
        age += deltaTime;
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

    @Override
    public void onInteract(Player player) {}

    @Override
    public void onDamage(float damage, DamageSource source) {}

    @Override
    protected void onDeath() {}

    @Override
    public ItemStack[] getDrops() { return new ItemStack[0]; }
}
