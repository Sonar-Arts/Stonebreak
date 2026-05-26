package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: absolute entity position + yaw.
 *
 * @deprecated kept for compatibility; prefer {@link EntityMoveS2C} (delta) or
 *             {@link EntityTeleportS2C} (absolute fallback).
 */
@Deprecated
public record EntityStateS2C(int networkId,
                             float x, float y, float z,
                             float yaw) implements Packet {

    public static final PacketCodec<EntityStateS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityStateS2C p) {
            out.writeInt(p.networkId());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
        }

        @Override
        public EntityStateS2C decode(ByteBuf in) {
            return new EntityStateS2C(
                in.readInt(),
                in.readFloat(), in.readFloat(), in.readFloat(),
                in.readFloat());
        }
    };
}
