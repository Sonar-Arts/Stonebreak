package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: this client's copy of a chunk is unusable (decode failure, apply
 * failure) — forget it server-side so the streaming scan re-sends a fresh snapshot.
 * Rate-limited on both sides (client per-chunk cooldown; server per-player budget).
 */
public record ChunkResyncRequestC2S(int chunkX, int chunkZ) implements Packet {

    public static final PacketCodec<ChunkResyncRequestC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ChunkResyncRequestC2S p) {
            out.writeInt(p.chunkX());
            out.writeInt(p.chunkZ());
        }

        @Override
        public ChunkResyncRequestC2S decode(ByteBuf in) {
            return new ChunkResyncRequestC2S(in.readInt(), in.readInt());
        }
    };
}
