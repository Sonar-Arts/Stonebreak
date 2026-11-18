package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: block-state registry names for ids {@code firstId .. firstId + names.size() - 1}
 * (cubic voxel runtime, #447). Cube bytes carry the STATE layer as raw {@code BlockStateRegistry} ids, so the
 * client's registry must mirror the server's: the whole table goes out on join (firstId 1) and every new
 * registration follows before any cube or state echo that could use it (the channel is FIFO).
 */
public record BlockStateNamesS2C(int firstId, List<String> names) implements Packet {

    /** Names per packet (the server splits a long table). */
    public static final int MAX_NAMES = 1024;
    public static final int MAX_NAME_CHARS = 512;

    public BlockStateNamesS2C {
        names = List.copyOf(names);
    }

    public static final PacketCodec<BlockStateNamesS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockStateNamesS2C p) {
            ByteBufIO.writeVarInt(out, p.firstId());
            NetCoords.writeCount(out, p.names().size());
            for (String s : p.names()) {
                ByteBufIO.writeString(out, s, MAX_NAME_CHARS);
            }
        }

        @Override
        public BlockStateNamesS2C decode(ByteBuf in) {
            int first = ByteBufIO.readVarInt(in);
            if (first < 1 || first > 0xFFFF) {
                throw new IllegalArgumentException("Invalid block-state id: " + first);
            }
            int n = NetCoords.readCount(in, MAX_NAMES, "block-state name");
            if (first + n - 1 > 0xFFFF) {
                throw new IllegalArgumentException("Block-state ids past 65535");
            }
            List<String> names = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                names.add(ByteBufIO.readString(in, MAX_NAME_CHARS));
            }
            return new BlockStateNamesS2C(first, names);
        }
    };
}
