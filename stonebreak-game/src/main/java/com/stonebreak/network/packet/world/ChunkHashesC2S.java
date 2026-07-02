package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: periodic desync audit — {@code ChunkHasher} hashes of a small
 * round-robin batch of resident chunks. The server compares against its own cached hashes
 * and re-streams any mismatching chunk. Entries are {@code {cx, cz, hash}} triples packed
 * flat as {@code [cx0, cz0, h0, cx1, cz1, h1, ...]}.
 */
public record ChunkHashesC2S(int[] entries) implements Packet {

    /** Max audited chunks per packet (client sends ≤16; bound guards hostile input). */
    public static final int MAX_ENTRIES = 64;

    public static final PacketCodec<ChunkHashesC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ChunkHashesC2S p) {
            int[] entries = p.entries();
            ByteBufIO.writeVarInt(out, entries.length);
            for (int v : entries) {
                out.writeInt(v);
            }
        }

        @Override
        public ChunkHashesC2S decode(ByteBuf in) {
            int n = ByteBufIO.readVarInt(in);
            if (n < 0 || n > MAX_ENTRIES * 3 || n % 3 != 0) {
                throw new IllegalArgumentException("Invalid chunk-hash entry count: " + n);
            }
            int[] entries = new int[n];
            for (int i = 0; i < n; i++) {
                entries[i] = in.readInt();
            }
            return new ChunkHashesC2S(entries);
        }
    };
}
