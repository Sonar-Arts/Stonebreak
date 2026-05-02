package com.stonebreak.network.sync;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.Entity;
import org.joml.Vector3f;

/**
 * Local state-change events that the SyncService routes to interested
 * synchronizers. Anything in the game that mutates syncable state should emit
 * one of these instead of touching the network directly.
 */
public sealed interface SyncEvent {

    /** A block was placed/broken locally (player-driven). */
    record BlockChanged(int x, int y, int z, BlockType type) implements SyncEvent {}

    /** A chat message was submitted locally. */
    record ChatSubmitted(String text) implements SyncEvent {}

    /** A new entity spawned locally (host only). */
    record EntitySpawned(Entity entity) implements SyncEvent {}

    /** An entity was removed locally (host only). */
    record EntityDespawned(Entity entity) implements SyncEvent {}

    /** Local player state snapshot, emitted periodically by the player synchronizer. */
    record LocalPlayerState(Vector3f pos, float yaw, float pitch) implements SyncEvent {}

    /** Host-only: a remote peer just completed handshake. Synchronizers can send snapshots. */
    record PeerJoined(int playerId, String username) implements SyncEvent {}
}

