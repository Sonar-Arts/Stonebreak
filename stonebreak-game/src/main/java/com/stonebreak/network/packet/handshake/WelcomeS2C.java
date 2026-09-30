package com.stonebreak.network.packet.handshake;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.world.generation.TerrainGeneratorType;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: handshake accepted; carries the assigned player id, world seed, terrain
 * generator, and spawn.
 */
public record WelcomeS2C(int playerId, long worldSeed, TerrainGeneratorType generatorType,
                         float spawnX, float spawnY, float spawnZ) implements Packet {

    public static final PacketCodec<WelcomeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WelcomeS2C p) {
            out.writeInt(p.playerId());
            out.writeLong(p.worldSeed());
            out.writeByte(p.generatorType().ordinal());
            out.writeFloat(p.spawnX());
            out.writeFloat(p.spawnY());
            out.writeFloat(p.spawnZ());
        }

        @Override
        public WelcomeS2C decode(ByteBuf in) {
            return new WelcomeS2C(
                in.readInt(), in.readLong(),
                TerrainGeneratorType.values()[in.readUnsignedByte()],
                in.readFloat(), in.readFloat(), in.readFloat());
        }
    };
}
