package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: an ability knocked back a replicated entity it just hit (e.g. Rampage's T3
 * cleave). Applying it to the shadow did nothing — shadows never integrate velocity — so the
 * ability's force never reached the real entity. The server applies it only right after its
 * own accepted damage from this player to this target, with clamped forces.
 *
 * @param dirX/dirZ horizontal push direction (normalized server-side)
 */
public record EntityKnockbackC2S(int targetNetworkId, float dirX, float dirZ,
                                 float horizontal, float vertical) implements Packet {

    public static final PacketCodec<EntityKnockbackC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityKnockbackC2S p) {
            out.writeInt(p.targetNetworkId());
            out.writeFloat(p.dirX());
            out.writeFloat(p.dirZ());
            out.writeFloat(p.horizontal());
            out.writeFloat(p.vertical());
        }

        @Override
        public EntityKnockbackC2S decode(ByteBuf in) {
            return new EntityKnockbackC2S(in.readInt(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat());
        }
    };
}
