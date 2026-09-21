package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the local player sheared a replicated sheep with shears. The server
 * is authoritative — it validates range/species and shears the real entity, then
 * replicates {@code EntityVariantS2C} to everyone; the client never mutates its
 * shadow's variant as anything more than a predicted visual swap.
 *
 * @param targetNetworkId network id of the sheep that was sheared
 */
public record EntityShearC2S(int targetNetworkId) implements Packet {

    public static final PacketCodec<EntityShearC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityShearC2S p) {
            out.writeInt(p.targetNetworkId());
        }

        @Override
        public EntityShearC2S decode(ByteBuf in) {
            return new EntityShearC2S(in.readInt());
        }
    };
}
