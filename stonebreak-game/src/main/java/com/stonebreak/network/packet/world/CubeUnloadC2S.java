package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the client's stream unloaded these cubes, (cx, cy, cz) triples (cubic voxel runtime,
 * #447; successor of the column {@code ChunkUnloadC2S}, #363). The server forgets them for that player, so
 * block deltas stop and a later request sends them again.
 */
public record CubeUnloadC2S(long[] cubes) implements Packet {

    public static final int MAX_CUBES = 1024;

    public int count() {
        return cubes.length / 3;
    }

    public static final PacketCodec<CubeUnloadC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, CubeUnloadC2S p) {
            CubeListCodec.write(out, p.cubes());
        }

        @Override
        public CubeUnloadC2S decode(ByteBuf in) {
            return new CubeUnloadC2S(CubeListCodec.read(in, MAX_CUBES, "cube unload"));
        }
    };
}
