package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → client: despawn a replicated entity. */
public record EntityDespawnS2C(int networkId) implements Packet {

    public static final PacketCodec<EntityDespawnS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityDespawnS2C p) {
            out.writeInt(p.networkId());
        }

        @Override
        public EntityDespawnS2C decode(ByteBuf in) {
            return new EntityDespawnS2C(in.readInt());
        }
    };
}
