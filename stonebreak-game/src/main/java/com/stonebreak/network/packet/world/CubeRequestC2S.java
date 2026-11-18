package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: cubes the client's REMOTE stream wants and does not hold ({@code CubeStream.missing()},
 * nearest first), as (cx, cy, cz) triples (cubic voxel runtime, #447). The server sends each one it has
 * installed (with its column record first), answers cubes it knows are air with the column record alone, and
 * keeps the rest pending until its own stream installs them. A request for a cube the server believes the
 * client already holds is a resync (the client lost it). Budgeted per player ({@code allowChunkRequest}).
 */
public record CubeRequestC2S(long[] cubes) implements Packet {

    public static final int MAX_CUBES = 1024;

    public int count() {
        return cubes.length / 3;
    }

    public static final PacketCodec<CubeRequestC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CubeRequestC2S p) {
            CubeListCodec.write(out, p.cubes());
        }

        @Override
        public CubeRequestC2S decode(ByteBuf in) {
            return new CubeRequestC2S(CubeListCodec.read(in, MAX_CUBES, "cube request"));
        }
    };
}
