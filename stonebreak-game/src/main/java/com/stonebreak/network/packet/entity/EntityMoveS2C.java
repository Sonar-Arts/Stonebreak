package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: compact entity movement. Position deltas are fixed-point at
 * {@code 1/4096} block per unit (±8 blocks/packet); yaw is absolute in 1/10° units. Use
 * {@link EntityTeleportS2C} when the delta would overflow. See
 * {@link com.openmason.engine.net.protocol.codec.EntityDeltaCodec}.
 */
public record EntityMoveS2C(int networkId,
                            short dx, short dy, short dz,
                            short yawDeg10) implements Packet {

    public static final PacketCodec<EntityMoveS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityMoveS2C p) {
            out.writeInt(p.networkId());
            out.writeShort(p.dx());
            out.writeShort(p.dy());
            out.writeShort(p.dz());
            out.writeShort(p.yawDeg10());
        }

        @Override
        public EntityMoveS2C decode(ByteBuf in) {
            return new EntityMoveS2C(
                in.readInt(),
                in.readShort(), in.readShort(), in.readShort(),
                in.readShort());
        }
    };
}
