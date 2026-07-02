package com.stonebreak.network.packet.entity;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: this client's entity-shadow map looks inconsistent (a burst of moves
 * for unknown network ids) — please re-send the full entity spawn snapshot. The snapshot
 * apply is idempotent client-side (known ids are ignored), so over-asking is merely wasted
 * bandwidth; the server rate-limits it regardless.
 */
public record EntityResyncC2S() implements Packet {

    public static final PacketCodec<EntityResyncC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, EntityResyncC2S p) {
            // no payload
        }

        @Override
        public EntityResyncC2S decode(ByteBuf in) {
            return new EntityResyncC2S();
        }
    };
}
