package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the player started mining the block at a position (issue #333). The first
 * half of a break; the finish is the {@link BlockChangeC2S} to air. The server times the pair
 * on its own clock against the block's hardness and the held tool it knows
 * ({@code ToolMiningRules.breakSeconds}) — a finish that arrives too early waits until the
 * block would really have broken, and a finish without a matching start is refused.
 *
 * <p>Sent once per target: when mining begins and whenever it moves to another block (the
 * client's progress restarts then too). Blocks that break instantly need none.
 */
public record BlockBreakStartC2S(int x, int y, int z) implements Packet {

    public static final PacketCodec<BlockBreakStartC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockBreakStartC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
        }

        @Override
        public BlockBreakStartC2S decode(ByteBuf in) {
            return new BlockBreakStartC2S(in.readInt(), in.readInt(), in.readInt());
        }
    };
}
