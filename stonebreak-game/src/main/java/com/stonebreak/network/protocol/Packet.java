package com.stonebreak.network.protocol;

/**
 * Base type for all multiplayer packets.
 * C2S = client-to-server, S2C = server-to-client.
 */
public sealed interface Packet
        permits Packet.HandshakeC2S,
                Packet.WelcomeS2C,
                Packet.KickS2C,
                Packet.ChunkDataS2C,
                Packet.BlockChangeC2S,
                Packet.BlockChangeS2C,
                Packet.MultiBlockChangeS2C,
                Packet.PlayerStateC2S,
                Packet.PlayerStateS2C,
                Packet.PlayerHeldItemC2S,
                Packet.PlayerHeldItemS2C,
                Packet.GiveItemS2C,
                Packet.PlayerJoinS2C,
                Packet.PlayerLeaveS2C,
                Packet.DisconnectC2S,
                Packet.ChatMessageC2S,
                Packet.ChatMessageS2C,
                Packet.EntitySpawnS2C,
                Packet.EntityDespawnS2C,
                Packet.EntityStateS2C,
                Packet.EntityMoveS2C,
                Packet.EntityTeleportS2C,
                Packet.EntityMetaS2C,
                Packet.BlockStateS2C,
                Packet.FurnaceSlotC2S,
                Packet.PickupRequestC2S,
                Packet.PickupRejectS2C {

    /** Bump on any wire-format change. Mismatched clients are kicked at handshake. */
    int PROTOCOL_VERSION = 5;

    record HandshakeC2S(int protocolVersion, String username) implements Packet {}

    /** Server → client refusal (version mismatch, kick, etc.). Client closes after receiving. */
    record KickS2C(String reason) implements Packet {}

    record WelcomeS2C(int playerId, long worldSeed,
                      float spawnX, float spawnY, float spawnZ) implements Packet {}

    record ChunkDataS2C(int chunkX, int chunkZ, byte[] payload) implements Packet {}

    record BlockChangeC2S(int x, int y, int z, short blockTypeId) implements Packet {}

    record BlockChangeS2C(int x, int y, int z, short blockTypeId) implements Packet {}

    /**
     * Batched block changes within a single 16×16×16 section. Each {@code packed}
     * entry is {@code (localPos << 16) | (blockId & 0xFFFF)} where
     * {@code localPos = (lx << 8) | (ly << 4) | lz}, each axis 0-15. Bandwidth
     * win is ~3-4× over individual {@link BlockChangeS2C} when edits cluster
     * (explosions, fluid spread, fill tools).
     */
    record MultiBlockChangeS2C(int sectionX, int sectionY, int sectionZ, int[] packed) implements Packet {}

    record PlayerStateC2S(float x, float y, float z, float yaw, float pitch) implements Packet {}

    record PlayerStateS2C(int playerId,
                          float x, float y, float z,
                          float yaw, float pitch) implements Packet {}

    /** Client → server: the local player switched their held hotbar item. */
    record PlayerHeldItemC2S(int itemId) implements Packet {}

    /** Server → all: a player's held item changed (or initial snapshot on join). */
    record PlayerHeldItemS2C(int playerId, int itemId) implements Packet {}

    /**
     * Server → one client: hand the receiving client this item stack
     * (host-authoritative drop pickup, command-give, etc.). The client adds it
     * to their local inventory; host already updated its own state.
     */
    record GiveItemS2C(int itemId, int count) implements Packet {}

    record PlayerJoinS2C(int playerId, String username,
                         float x, float y, float z) implements Packet {}

    record PlayerLeaveS2C(int playerId) implements Packet {}

    record DisconnectC2S(String reason) implements Packet {}

    /** Client → server chat submission. Server attaches sender name & broadcasts. */
    record ChatMessageC2S(String text) implements Packet {}

    /** Server → all clients chat broadcast. */
    record ChatMessageS2C(int senderId, String senderName, String text) implements Packet {}

    /**
     * Spawn an entity on a remote client.
     * {@code metadata} is a free-form per-type payload (e.g. cow texture variant).
     */
    record EntitySpawnS2C(int networkId, int entityTypeOrdinal,
                          float x, float y, float z, float yaw,
                          String metadata) implements Packet {}

    record EntityDespawnS2C(int networkId) implements Packet {}

    /**
     * @deprecated Kept for backward-compat; prefer {@link EntityMoveS2C} (delta) or
     *             {@link EntityTeleportS2C} (absolute fallback).
     */
    @Deprecated
    record EntityStateS2C(int networkId,
                          float x, float y, float z,
                          float yaw) implements Packet {}

    /**
     * Compact entity movement update.
     * Position deltas are fixed-point with scale {@code 1/4096} block per unit
     * (range ±8 blocks per packet). Yaw is absolute, in 1/10 degree units
     * (range ±3276°, resolution 0.1°). Use {@link EntityTeleportS2C} when the
     * delta would overflow.
     */
    record EntityMoveS2C(int networkId,
                         short dx, short dy, short dz,
                         short yawDeg10) implements Packet {}

    /** Absolute reset of an entity's position+rotation. Fallback for big jumps. */
    record EntityTeleportS2C(int networkId,
                             float x, float y, float z,
                             float yaw) implements Packet {}

    /**
     * Host → clients: replicated runtime entity state beyond position/rotation.
     * Sent at spawn and whenever a tracked field changes. {@code behaviorState}
     * is the entity's AI-state ordinal (per type; -1 = no behavior), used by the
     * client to pick the matching animation clip on the shadow entity.
     */
    record EntityMetaS2C(int networkId, float health, byte behaviorState) implements Packet {}

    /**
     * Host → clients: full block-entity state string for the block at (x,y,z)
     * — e.g. a furnace's {@code furnace:...} payload (see {@code FurnaceState}).
     * A blank {@code state} means "clear any block-entity state here" (block
     * removed / furnace broken).
     */
    record BlockStateS2C(int x, int y, int z, String state) implements Packet {}

    /**
     * Client → host: intent to set one furnace slot to {@code stack} (encoded
     * {@code kind:id:count}, see {@code FurnaceState}). Host validates, applies
     * to the authoritative furnace, and echoes a {@link BlockStateS2C} to all.
     * {@code slot}: 0=ingredient, 1=fuel, 2=output.
     */
    record FurnaceSlotC2S(int x, int y, int z, byte slot, String stack) implements Packet {}

    /**
     * Client → host: request to pick up the drop entity {@code networkId} the
     * client has predicted picking up. Host arbitrates: on success it grants the
     * item ({@link GiveItemS2C}) and despawns the drop ({@link EntityDespawnS2C});
     * on failure it replies {@link PickupRejectS2C} so the client un-hides it.
     */
    record PickupRequestC2S(int networkId) implements Packet {}

    /** Host → one client: the predicted pickup of {@code networkId} was denied. */
    record PickupRejectS2C(int networkId) implements Packet {}
}
