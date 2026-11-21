package com.stonebreak.network.packet.world;

import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/** Shared wire form of a cube coordinate list: count, then (cx, cy, cz) zigzag varlongs per cube. */
final class CubeListCodec {

    private CubeListCodec() {}

    static void write(ByteBuf out, long[] xyz) {
        if (xyz.length % 3 != 0) {
            throw new IllegalArgumentException("cube list length must be a multiple of 3");
        }
        NetCoords.writeCount(out, xyz.length / 3);
        for (long v : xyz) {
            NetCoords.writeVarLong(out, v);
        }
    }

    static long[] read(ByteBuf in, int maxCubes, String what) {
        int n = NetCoords.readCount(in, maxCubes, what);
        long[] xyz = new long[n * 3];
        for (int i = 0; i < xyz.length; i++) {
            xyz[i] = NetCoords.readVarLong(in);
        }
        return xyz;
    }

    static void requireTriples(long[] xyz, int maxCubes) {
        if (xyz.length % 3 != 0 || xyz.length / 3 > maxCubes) {
            throw new IllegalArgumentException("bad cube list");
        }
    }
}
