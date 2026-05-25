package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: spawn a replicated entity. {@code metadata} is a free-form per-type
 * payload (e.g. a cow's texture variant).
 */
public record EntitySpawnS2C(int networkId, int entityTypeOrdinal,
                             float x, float y, float z, float yaw,
                             String metadata) implements Packet {

    public static final PacketCodec<EntitySpawnS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntitySpawnS2C p) {
            out.writeInt(p.networkId());
            out.writeInt(p.entityTypeOrdinal());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
            ByteBufIO.writeString(out, p.metadata(), ByteBufIO.MAX_METADATA_CHARS);
        }

        @Override
        public EntitySpawnS2C decode(ByteBuf in) {
            return new EntitySpawnS2C(
                in.readInt(), in.readInt(),
                in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                ByteBufIO.readString(in, ByteBufIO.MAX_METADATA_CHARS));
        }
    };
}
