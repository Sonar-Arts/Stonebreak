package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.world.World;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * A remote (network) player. Updated entirely from incoming
 * {@link com.stonebreak.network.packet.player.PlayerStateS2C} packets,
 * not local physics or AI.
 */
public class RemotePlayer extends LivingEntity {

    /**
     * No stride-counted mob footsteps: a player shadow steps on its body's foot contacts instead
     * ({@link #updateClientVisuals}), the same signal the local player's own steps use.
     */
    @Override
    protected boolean hasFootsteps() {
        return false;
    }

    private final int playerId;
    private final String username;
    private float pitch;
    /** Block/item id this remote player is currently holding. 0 = empty/air. */
    private volatile int heldItemId;
    /**
     * Resolved identity of the held item for third-person in-hand rendering. A
     * {@link BlockType} (cube or flower) or an {@link com.stonebreak.items.ItemType}
     * (voxelized tool); {@code null} when empty. Networked remote players only ever
     * carry blocks (see {@link #setHeldItemId}); decoy figures such as
     * {@link IllusionDecoy} set the full item via {@link #setHeldItem}.
     */
    private volatile Item heldItem;

    private Vector3d prevPosition;
    /**
     * This body's pose (#457): the player animation graph's own instance, fed the replicated flags + displacement
     * through the same {@link com.stonebreak.player.animation.PlayerPoseInputs} as the local player. Client only
     * (created on the first visual update); null on the server.
     */
    private com.stonebreak.player.animation.PlayerBodyAnimator bodyAnimator;
    private final com.stonebreak.player.animation.PlayerPoseInputs poseInputs =
        new com.stonebreak.player.animation.PlayerPoseInputs();
    /** Horizontal speed estimated from the last displacement (blocks/second). */
    private float horizontalSpeed;
    /** Whether the last body update planted a foot. */
    private boolean footContact;
    private final com.stonebreak.player.animation.PlayerBodyAnimator.Events bodyEvents =
        new com.stonebreak.player.animation.PlayerBodyAnimator.Events() {
            @Override
            public void footstep() {
                footContact = true;
            }

            @Override
            public void event(String name) {
                com.stonebreak.player.animation.PlayerAnimEvents.assetEvent(name, position);
            }
        };

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
    private volatile short stateFlags;

    /**
     * Splash/ripple FX, the same as {@link Player}'s ({@link com.stonebreak.fx.bindings.SwimFx}), but
     * driven from the replicated {@code PARTIALLY_IN_WATER}/{@code SWIMMING} flags and position deltas
     * instead of local block queries — a remote player has no water physics of its own.
     */
    private final com.stonebreak.fx.bindings.SwimFx swimFx = new com.stonebreak.fx.bindings.SwimFx();
    private boolean wasPartiallyInWaterLastFrame;
    private boolean waterStateInitialized;
    /**
     * True once any real state packet has landed (both {@link #setStateFlags} and
     * {@link #updateWaterEffects} run on the game thread — packets are drained by the
     * per-frame pump in {@code ClientWorldView}, not a network thread). Lets
     * {@link #updateWaterEffects} distinguish "just entered water" from "this player was
     * already in water before we ever saw them" (e.g. joining mid-swim) — until this is
     * true, {@code stateFlags} is still its unset default and must not be edge-detected
     * against. Volatile only for consistency with the other replicated fields; read it
     * BEFORE {@code stateFlags} so the pair stays correct even if packet application ever
     * moves off-thread.
     */
    private volatile boolean receivedFirstState;

    public void setStateFlags(short flags) {
        this.stateFlags = flags;
        receivedFirstState = true;
    }
    public short getStateFlags() { return stateFlags; }

    public RemotePlayer(World world, Vector3dc position, int playerId, String username) {
        super(world, position, EntityType.REMOTE_PLAYER);
        this.playerId = playerId;
        this.username = username;
        this.prevPosition = new Vector3d(position);
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

    /** Hand offset from the feet, in body space: right, up, forward (third-person held item). */
    private static final float HAND_RIGHT = 0.35f;
    private static final float HAND_UP = 1.2f;
    private static final float HAND_FORWARD = 0.15f;

    /**
     * Where this figure's held item sits, in world space: the one hand position the held-item
     * renderer draws at and a held torch lights from. Turned with the model-space body yaw, the
     * frame the figure itself is drawn in.
     */
    public Vector3d handPosition(Vector3d out) {
        float yawRad = (float) Math.toRadians(getBodyYaw());
        float cos = (float) Math.cos(yawRad);
        float sin = (float) Math.sin(yawRad);
        // World vectors: forward = (-sin, 0, -cos), right = (cos, 0, -sin).
        return out.set(position.x + HAND_RIGHT * cos - HAND_FORWARD * sin,
                position.y + HAND_UP,
                position.z - HAND_RIGHT * sin - HAND_FORWARD * cos);
    }
    /** Sets the full held-item identity (block or tool/flower). Used by local-only figures. */
    public void setHeldItem(Item item) {
        this.heldItem = item;
        this.heldItemId = item == null ? 0 : item.getId();
    }

    /**
     * This body's animator (its graph palette is what the figure, its shadow and wireframe draw); null until the
     * first client visual update.
     */
    public com.stonebreak.player.animation.PlayerBodyAnimator bodyAnimator() { return bodyAnimator; }

    /** Horizontal speed from the last displacement, blocks/second (the body graph's {@code speed}). */
    public float getHorizontalSpeed() { return horizontalSpeed; }

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
    public void applyNetworkState(double x, double y, double z, float yaw, float pitch) {
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
     * Derives speed and facing from the horizontal displacement since the last call. Extracted so subclasses driven
     * externally (e.g. {@link IllusionDecoy}, moved by mirroring its owner rather than the network) can reuse the same
     * derivation after repositioning. Server-safe: the pose itself is {@link #animateBody} (client only).
     */
    protected void updateMovementAnimation(float deltaTime) {
        float dx = (float) (position.x - prevPosition.x);
        float dy = (float) (position.y - prevPosition.y);
        float dz = (float) (position.z - prevPosition.z);
        horizontalSpeed = deltaTime > 0f ? (float) Math.sqrt(dx * dx + dz * dz) / deltaTime : 0f;
        prevPosition.set(position);

        updateWaterEffects(deltaTime, dx, dy, dz);

        // Convert the replicated camera yaw to model space and drive the body/head
        // orientation from it plus the displacement-estimated velocity. Renderers read
        // getBodyYaw()/getHeadYaw()/getHeadPitch() instead of the raw rotation.
        float yawRad = (float) Math.toRadians(rotation.y);
        lookModelYaw = com.stonebreak.player.PlayerBodyOrientation.modelYawFromDirection(
                (float) Math.cos(yawRad), (float) Math.sin(yawRad));
        com.stonebreak.vehicles.Rideable vehicle = getVehicle();
        if (vehicle != null) {
            bodyOrientation.seat(vehicle.seatYaw(getSeatIndex()));
        } else if (deltaTime > 0f) {
            velocityEstimate.set(dx / deltaTime, 0f, dz / deltaTime);
            bodyOrientation.update(deltaTime, velocityEstimate, lookModelYaw);
        }
        clipClock.advance(deltaTime, 0.0f);
    }

    /**
     * Poses this body for the frame (#457): the replicated flags + displacement speed through the same
     * {@link com.stonebreak.player.animation.PlayerPoseInputs} as the local player, at the update rate its distance
     * to the camera allows. Returns whether a foot landed (grounded, out of water).
     */
    protected boolean animateBody(float deltaTime) {
        if (bodyAnimator == null) bodyAnimator = com.stonebreak.player.animation.PlayerBodyAnimator.player();
        poseInputs.set(stateFlags, horizontalSpeed, getVehicle() != null, getHeadYaw(), getHeadPitch());
        footContact = false;
        bodyAnimator.update(deltaTime, poseInputs, bodyUpdateInterval(), bodyEvents);
        return footContact;
    }

    /** Every frame near the camera, less often farther out (Unreal's update rate optimization). */
    private int bodyUpdateInterval() {
        com.stonebreak.player.Player viewer = com.stonebreak.core.Game.getPlayer();
        if (viewer == null || viewer.getCamera() == null) return 1;
        return com.stonebreak.player.animation.PlayerBodyAnimator.intervalFor(
                viewer.getCamera().getPosition().distanceSquared(position));
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        if (bodyAnimator != null) {
            bodyAnimator.close();
            bodyAnimator = null;
        }
    }

    /**
     * Mirrors {@link Player}'s splash-on-entry / periodic-surface-ripple triggering
     * ({@code Player.update}), but reads the replicated water flags and derives velocity from
     * position deltas instead of local block queries.
     */
    private void updateWaterEffects(float deltaTime, float dx, float dy, float dz) {
        // Read order matters: receivedFirstState before stateFlags, so a true here always
        // pairs with the flags of a real packet (see the field's doc).
        boolean hasState = receivedFirstState;
        short flags = stateFlags;
        boolean partiallyInWater = com.stonebreak.network.packet.player.PlayerStateFlags.has(
                flags, com.stonebreak.network.packet.player.PlayerStateFlags.PARTIALLY_IN_WATER);
        boolean eyesInWater = com.stonebreak.network.packet.player.PlayerStateFlags.has(
                flags, com.stonebreak.network.packet.player.PlayerStateFlags.SWIMMING);

        if (!waterStateInitialized) {
            // First real state observed for this player: adopt it as the baseline instead of
            // treating the unset-default→actual jump as an entry edge (avoids a spurious splash
            // when joining/rendering a player who was already in water).
            if (hasState) {
                waterStateInitialized = true;
                wasPartiallyInWaterLastFrame = partiallyInWater;
            }
        } else if (partiallyInWater && !wasPartiallyInWaterLastFrame && deltaTime > 0f) {
            swimFx.enteredWater(com.stonebreak.player.PlayerCharacter.ASSET_ID, com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT, world, position, Math.max(0f, -dy / deltaTime));
        }
        if (waterStateInitialized) {
            wasPartiallyInWaterLastFrame = partiallyInWater;
        }

        // Surface ripples while touching water but not submerged, same as the local player.
        float horizSpeed = deltaTime > 0f ? (float) Math.sqrt(dx * dx + dz * dz) / deltaTime : 0f;
        swimFx.update(deltaTime, com.stonebreak.player.PlayerCharacter.ASSET_ID, com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT, world, position, partiallyInWater, eyesInWater, horizSpeed);
    }

    @Override
    protected void updateAI(float deltaTime) {
        // No AI.
    }

    /**
     * Client shadow path (network shadows never run {@link #update}): derive speed and facing from
     * the freshly interpolated position and pose the body through its graph, stepping audibly on
     * each foot contact.
     */
    @Override
    public void updateClientVisuals(float deltaTime) {
        updateMovementAnimation(deltaTime);
        // Footsteps (issue #344) on the body's foot contacts, as the local player's are.
        if (animateBody(deltaTime)) {
            com.stonebreak.audio.PlayerSounds.playStepAt(world, position);
        }
    }

    @Override
    public void render(Renderer renderer) {
        // Rendering is handled centrally by EntityRenderer dispatch.
    }

    @Override
    public EntityType getType() { return EntityType.REMOTE_PLAYER; }

    /** Decoys too are drawn with the player model, turned to the body yaw. */
    @Override
    protected String modelObjectId() { return com.stonebreak.player.PlayerCharacter.ASSET_ID; }

    @Override
    protected float modelYaw() { return getBodyYaw(); }

    // RemotePlayer is a display-only proxy for a server-authoritative remote client.
    // Interaction, damage, and death are resolved server-side and broadcast via packets;
    // the local ghost intentionally ignores these events.
    @Override
    public void onInteract(Player player) {}

    @Override
    public void onDamage(float damage, DamageSource source) {}

    /**
     * A combat target only while the world allows PvP (#343, mirrored from {@code PvpStateS2C});
     * the server still refuses hits when it is off. Decoys never are ({@link IllusionDecoy}).
     */
    @Override
    public boolean isPlayerAttackable() { return isAlive() && com.stonebreak.core.Game.isPvpEnabled(); }

    @Override
    protected void onDeath() {}

    @Override
    public ItemStack[] getDrops() { return new ItemStack[0]; }
}
