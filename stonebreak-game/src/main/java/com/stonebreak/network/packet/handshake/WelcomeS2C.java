package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.generation.TerrainGeneratorType;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: handshake accepted; carries the assigned player id, world seed, terrain
 * generator, spawn, and the client's join secret for this server ("" for the in-process host).
 * The client keeps the secret and presents it in its next {@link HandshakeC2S} (issue #335).
 */
public record WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                         float spawnX, float spawnY, float spawnZ, String clientSecret) implements Packet {

    public WelcomeS2C {
        clientSecret = clientSecret == null ? "" : clientSecret;
    }

    /** A welcome without a secret (the in-process host). */
    public WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                      float spawnX, float spawnY, float spawnZ) {
        this(playerId, worldSeed, generatorType, spawnX, spawnY, spawnZ, "");
    }

    public static final PacketCodec<WelcomeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WelcomeS2C p) {
            out.writeInt(p.playerId());
            out.writeLong(p.worldSeed());
            out.writeByte(p.generatorType().ordinal());
            out.writeFloat(p.spawnX());
            out.writeFloat(p.spawnY());
            out.writeFloat(p.spawnZ());
            ByteBufIO.writeString(out, p.clientSecret(), HandshakeC2S.MAX_SECRET_CHARS);
        }

        @Override
        public WelcomeS2C decode(ByteBuf in) {
            return new WelcomeS2C(
                in.readInt(), in.readLong(),
                TerrainGeneratorType.values()[in.readUnsignedByte()],
                in.readFloat(), in.readFloat(), in.readFloat(),
                ByteBufIO.readString(in, HandshakeC2S.MAX_SECRET_CHARS));
        }
    };
}
