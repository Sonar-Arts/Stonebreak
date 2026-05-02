package com.stonebreak.network.protocol;

/**
 * Base type for all multiplayer packets.
 * C2S = client-to-server, S2C = server-to-client.
 */
public sealed interface Packet
        permits Packet.HandshakeC2S,
                Packet.WelcomeS2C,
                Packet.ChunkDataS2C,
                Packet.BlockChangeC2S,
                Packet.BlockChangeS2C,
                Packet.PlayerStateC2S,
                Packet.PlayerStateS2C,
                Packet.PlayerJoinS2C,
                Packet.PlayerLeaveS2C,
                Packet.DisconnectC2S {

    record HandshakeC2S(String username) implements Packet {}

    record WelcomeS2C(int playerId, long worldSeed,
                      float spawnX, float spawnY, float spawnZ) implements Packet {}

    record ChunkDataS2C(int chunkX, int chunkZ, byte[] payload) implements Packet {}

    record BlockChangeC2S(int x, int y, int z, short blockTypeId) implements Packet {}

    record BlockChangeS2C(int x, int y, int z, short blockTypeId) implements Packet {}

    record PlayerStateC2S(float x, float y, float z, float yaw, float pitch) implements Packet {}

    record PlayerStateS2C(int playerId,
                          float x, float y, float z,
                          float yaw, float pitch) implements Packet {}

    record PlayerJoinS2C(int playerId, String username,
                         float x, float y, float z) implements Packet {}

    record PlayerLeaveS2C(int playerId) implements Packet {}

    record DisconnectC2S(String reason) implements Packet {}
}
