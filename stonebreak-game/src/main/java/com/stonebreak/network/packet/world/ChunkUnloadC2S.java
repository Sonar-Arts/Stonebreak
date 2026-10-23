package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: this client dropped these chunks (they left its keep radius, or a late
 * snapshot landed outside it and was discarded). The server forgets them for this player so
 * they re-stream when they come back into view. Without it the server only forgot chunks from
 * its own 20 Hz position samples, and a quick border crossing left a chunk the client had
 * dropped recorded as sent — a permanent hole (issue #363). Coordinates are packed flat as
 * {@code [cx0, cz0, cx1, cz1, ...]}. {@code centerX/centerZ} is the chunk the client stood in
 * when it dropped them ({@link #UNKNOWN_CENTER} = not reported): whether a dropped chunk is
 * still in view is judged from there, not from the server's lagging position sample — after
 * a teleport that sample still says "old area", and every dropped chunk used to re-stream.
 */
public record ChunkUnloadC2S(int[] coords, int centerX, int centerZ) implements Packet {

    public static final int UNKNOWN_CENTER = Integer.MIN_VALUE;

    public ChunkUnloadC2S(int[] coords) {
        this(coords, UNKNOWN_CENTER, UNKNOWN_CENTER);
    }

    /** Max chunks per packet; the client splits larger sweeps. Bound guards hostile input. */
    public static final int MAX_CHUNKS = 512;

    public static final PacketCodec<ChunkUnloadC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ChunkUnloadC2S p) {
            int[] coords = p.coords();
            ByteBufIO.writeVarInt(out, coords.length);
            for (int v : coords) {
                out.writeInt(v);
            }
            out.writeInt(p.centerX());
            out.writeInt(p.centerZ());
        }

        @Override
        public ChunkUnloadC2S decode(ByteBuf in) {
            int n = ByteBufIO.readVarInt(in);
            if (n < 0 || n > MAX_CHUNKS * 2 || n % 2 != 0) {
                throw new IllegalArgumentException("Invalid chunk-unload coordinate count: " + n);
            }
            int[] coords = new int[n];
            for (int i = 0; i < n; i++) {
                coords[i] = in.readInt();
            }
            return new ChunkUnloadC2S(coords, in.readInt(), in.readInt());
        }
    };
}
