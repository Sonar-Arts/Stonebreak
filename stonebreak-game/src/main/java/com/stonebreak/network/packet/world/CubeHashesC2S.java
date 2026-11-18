package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server desync audit (cubic voxel runtime, #447; successor of {@code ChunkHashesC2S}): for each
 * (cx, cy, cz) triple in {@code cubes}, {@code hashes[i]} = CRC32C of the client's
 * {@code CubeWorld.encode(PARTS_NETWORK)} bytes (canonical: equal content encodes to equal bytes). The server
 * compares its own and re-sends a cube that differs (budgeted like a resync).
 */
public record CubeHashesC2S(long[] cubes, int[] hashes) implements Packet {

    public static final int MAX_CUBES = 1024;

    public CubeHashesC2S {
        if (cubes.length != hashes.length * 3) {
            throw new IllegalArgumentException("one hash per cube");
        }
    }

    public static final PacketCodec<CubeHashesC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CubeHashesC2S p) {
            CubeListCodec.write(out, p.cubes());
            for (int h : p.hashes()) {
                out.writeInt(h);
            }
        }

        @Override
        public CubeHashesC2S decode(ByteBuf in) {
            long[] cubes = CubeListCodec.read(in, MAX_CUBES, "cube hash");
            int n = cubes.length / 3;
            if (in.readableBytes() < n * 4L) {
                throw new IllegalArgumentException("Truncated cube hashes");
            }
            int[] hashes = new int[n];
            for (int i = 0; i < n; i++) {
                hashes[i] = in.readInt();
            }
            return new CubeHashesC2S(cubes, hashes);
        }
    };
}
