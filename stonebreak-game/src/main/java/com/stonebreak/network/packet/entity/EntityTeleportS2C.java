package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → client: absolute reset of an entity's position + rotation (big-jump fallback). */
public record EntityTeleportS2C(int networkId,
                                float x, float y, float z,
                                float yaw) implements Packet {

    public static final PacketCodec<EntityTeleportS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityTeleportS2C p) {
            out.writeInt(p.networkId());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
        }

        @Override
        public EntityTeleportS2C decode(ByteBuf in) {
            return new EntityTeleportS2C(
                in.readInt(),
                in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat());
        }
    };
}
