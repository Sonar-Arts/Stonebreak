package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.generation.TerrainGeneratorType;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: handshake accepted; carries the assigned player id, world seed, terrain
 * generator, spawn (int64 cell + float offset per axis), and the client's join secret for this server ("" for the in-process host).
 * The client keeps the secret and presents it in its next {@link HandshakeC2S} (issue #335).
 */
public record WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                         com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset terrainScale,
                         double spawnX, double spawnY, double spawnZ, String clientSecret) implements Packet {

    public WelcomeS2C {
        clientSecret = clientSecret == null ? "" : clientSecret;
        terrainScale = terrainScale == null ? com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR : terrainScale;
    }

    /** A 1:4 world's welcome. */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      double spawnX, double spawnY, double spawnZ, String clientSecret) {
        this(playerId, worldSeed, generatorType, null, spawnX, spawnY, spawnZ, clientSecret);
    }

    /** A welcome without a secret (the in-process host). */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      double spawnX, double spawnY, double spawnZ) {
        this(playerId, worldSeed, generatorType, spawnX, spawnY, spawnZ, "");
    }

    public static final PacketCodec<WelcomeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WelcomeS2C p) {
            out.writeInt(p.playerId());
            out.writeLong(p.worldSeed());
            out.writeByte(p.generatorType().ordinal());
            out.writeByte(p.terrainScale().ordinal());
            com.stonebreak.network.packet.NetCoords.writePosition(out, p.spawnX(), p.spawnY(), p.spawnZ());
            ByteBufIO.writeString(out, p.clientSecret(), HandshakeC2S.MAX_SECRET_CHARS);
        }

        @Override
        public WelcomeS2C decode(ByteBuf in) {
            int id = in.readInt();
            long seed = in.readLong();
            int type = in.readUnsignedByte();
            if (type >= TerrainGeneratorType.values().length) {
                throw new IllegalArgumentException("Unknown terrain generator " + type);
            }
            int scale = in.readUnsignedByte();
            var scales = com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset.values();
            if (scale >= scales.length) {
                throw new IllegalArgumentException("Unknown terrain scale " + scale);
            }
            double x = com.stonebreak.network.packet.NetCoords.readPosition(in);
            double y = com.stonebreak.network.packet.NetCoords.readPosition(in);
            double z = com.stonebreak.network.packet.NetCoords.readPosition(in);
            return new WelcomeS2C(id, seed, TerrainGeneratorType.values()[type], scales[scale], x, y, z,
                ByteBufIO.readString(in, HandshakeC2S.MAX_SECRET_CHARS));
        }
    };
}
