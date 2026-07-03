package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
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
    /**
     * Resolved identity of the held item for third-person in-hand rendering. A
     * {@link BlockType} (cube or flower) or an {@link com.stonebreak.items.ItemType}
     * (voxelized tool); {@code null} when empty. Networked remote players only ever
     * carry blocks (see {@link #setHeldItemId}); local-only figures such as
     * {@link IllusionDecoy} set the full item via {@link #setHeldItem}.
     */
    private volatile Item heldItem;

    private PlayerStateMapping.PlayerMovementState movementState = PlayerStateMapping.PlayerMovementState.IDLE;
    private Vector3f prevPosition;

    /**
     * Body facing / head-swivel logic, shared with the local player's third-person view.
     * The replicated {@code rotation.y} is a raw CAMERA yaw (front = (cos, 0, sin)), which
     * is NOT the SBE model's yaw space — rendering it directly pointed the figure in a
     * wrong (mirrored) direction. This converts the camera yaw into model space each
     * visual tick and drives body-faces-movement / head-leads-look exactly like the
     * local third-person path.
     */
    private final com.stonebreak.player.PlayerBodyOrientation bodyOrientation =
        new com.stonebreak.player.PlayerBodyOrientation();
    /** Look yaw in model space, updated every visual tick (input to head swivel). */
    private float lookModelYaw;
    private final Vector3f velocityEstimate = new Vector3f();

    /** Latest replicated movement/action flags ({@code PlayerStateFlags} bits). */
    private volatile byte stateFlags;
    /**
     * Attack-overlay envelope driven by the replicated ATTACKING flag — the exact pattern
     * the local {@code Player} uses, so remote swings render with the same pop-free
     * fade-in/out through the overlay-capable render path.
     */
    private final com.stonebreak.mobs.sbe.OverlayAnimState attackOverlay =
        new com.stonebreak.mobs.sbe.OverlayAnimState();

    public void setStateFlags(byte flags) { this.stateFlags = flags; }
    public byte getStateFlags() { return stateFlags; }
    public com.stonebreak.mobs.sbe.OverlayAnimState getAttackOverlay() { return attackOverlay; }

    public RemotePlayer(World world, Vector3f position, int playerId, String username) {
        super(world, position, EntityType.REMOTE_PLAYER);
        this.playerId = playerId;
        this.username = username;
        this.prevPosition = new Vector3f(position);
    }

    public int getPlayerId() { return playerId; }
    public String getUsername() { return username; }
    public float getPitch() { return pitch; }
    public int getHeldItemId() { return heldItemId; }
    public void setHeldItemId(int id) {
        this.heldItemId = id;
        // Block ids and item ids share one id space (mirrors ItemStack.setBlockTypeId):
        // resolve BlockType first, then ItemType — previously only blocks resolved, so a
        // remote player holding a tool rendered empty-handed.
        BlockType block = BlockType.getById(id);
        if (block != null && block != BlockType.AIR) {
            this.heldItem = block;
        } else {
            com.stonebreak.items.ItemType itemType = com.stonebreak.items.ItemType.getById(id);
            this.heldItem = itemType;
        }
    }

    /** Held item identity for third-person hand rendering. {@code null} = empty-handed. */
    public Item getHeldItem() { return heldItem; }
    /** Sets the full held-item identity (block or tool/flower). Used by local-only figures. */
    public void setHeldItem(Item item) {
        this.heldItem = item;
        this.heldItemId = item == null ? 0 : item.getId();
    }

    public PlayerStateMapping.PlayerMovementState getMovementState() { return movementState; }

    /** Body facing in SBE model space (degrees) — what renderers should rotate the figure by. */
    public float getBodyYaw() { return bodyOrientation.getBodyYaw(); }

    /** Head yaw relative to the body, clamped to the comfortable swivel range. */
    public float getHeadYaw() { return bodyOrientation.getHeadYaw(lookModelYaw); }

    /** Head pitch from the replicated camera pitch, clamped. */
    public float getHeadPitch() { return bodyOrientation.getHeadPitch(pitch); }

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
        updateMovementAnimation(deltaTime);
    }

    /**
     * Derives walk/idle from horizontal displacement since the last call and
     * advances the animation clock. Extracted so subclasses driven externally
     * (e.g. {@link IllusionDecoy}, moved by the ability rather than the network)
     * can reuse the same state/animation derivation after repositioning.
     */
    protected void updateMovementAnimation(float deltaTime) {
        float dx = position.x - prevPosition.x;
        float dz = position.z - prevPosition.z;
        float horizDist = (float) Math.sqrt(dx * dx + dz * dz);
        boolean moving = horizDist > WALK_THRESHOLD;
        prevPosition.set(position);

        // Convert the replicated camera yaw to model space and drive the body/head
        // orientation from it plus the displacement-estimated velocity. Renderers read
        // getBodyYaw()/getHeadYaw()/getHeadPitch() instead of the raw rotation.
        float yawRad = (float) Math.toRadians(rotation.y);
        lookModelYaw = com.stonebreak.player.PlayerBodyOrientation.modelYawFromDirection(
                (float) Math.cos(yawRad), (float) Math.sin(yawRad));
        if (deltaTime > 0f) {
            velocityEstimate.set(dx / deltaTime, 0f, dz / deltaTime);
            bodyOrientation.update(deltaTime, velocityEstimate, lookModelYaw);
        }

        // Replicated flags pick the clip; the displacement heuristic remains the walk/idle
        // fallback (local-only figures like IllusionDecoy never set flags). Airborne maps to
        // the jumping clip; sprint/sneak/swim flags are replicated but render as walking
        // until those clips are authored in SB_Player.sbe.
        byte flags = stateFlags;
        if (com.stonebreak.network.packet.player.PlayerStateFlags.has(
                flags, com.stonebreak.network.packet.player.PlayerStateFlags.AIRBORNE)) {
            movementState = PlayerStateMapping.PlayerMovementState.JUMPING;
        } else {
            movementState = moving
                    ? PlayerStateMapping.PlayerMovementState.WALKING
                    : PlayerStateMapping.PlayerMovementState.IDLE;
        }

        // Attack overlay from the replicated flag (~6 packets across a swing at 20 Hz —
        // a dropped droppable packet delays an edge by ≤50 ms, invisible under the fades).
        attackOverlay.update(deltaTime, com.stonebreak.network.packet.player.PlayerStateFlags.has(
                flags, com.stonebreak.network.packet.player.PlayerStateFlags.ATTACKING));

        animationController.updateAnimations(deltaTime);
    }

    @Override
    protected void updateAI(float deltaTime) {
        // No AI.
    }

    /**
     * Client shadow path (network shadows never run {@link #update}): derive
     * walk/idle from the freshly interpolated position and advance the
     * animation clock so the remote player's clips actually play.
     */
    @Override
    public void updateClientVisuals(float deltaTime) {
        updateMovementAnimation(deltaTime);
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
