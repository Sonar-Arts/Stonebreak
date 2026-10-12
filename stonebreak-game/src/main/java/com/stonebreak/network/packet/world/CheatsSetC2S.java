package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: toggle the world's cheats flag (the /cheats command). Cheats are a
 * per-world setting persisted in the server-owned {@code WorldData}, so the toggle must
 * route here rather than flipping only the client's runtime flag. The server validates
 * (host-only, like {@code TimeSetC2S}), applies it to {@code ServerLevel} so it saves with
 * the world, and broadcasts {@code CheatsStateS2C} so every client adopts the new flag.
 */
public record CheatsSetC2S(boolean enabled) implements Packet {

    public static final PacketCodec<CheatsSetC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CheatsSetC2S p) {
            out.writeBoolean(p.enabled());
        }

        @Override
        public CheatsSetC2S decode(ByteBuf in) {
            return new CheatsSetC2S(in.readBoolean());
        }
    };
}
