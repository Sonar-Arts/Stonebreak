package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the authoritative per-block SBO state STRING at a position (furnace
 * lit/unlit + contents + progress today; any future stateful block). Deduped at the source
 * — the server emits only when the string actually changed — so an idle furnace costs
 * nothing. Clients write it into the chunk state map (remeshing on render-state change)
 * and feed their display furnace registry so open UIs track live.
 */
public record BlockStateS2C(int x, int y, int z, String state) implements Packet {

    /** Bound on one state string; furnace states run ~100 chars. */
    public static final int MAX_STATE_LENGTH = 512;

    public static final PacketCodec<BlockStateS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockStateS2C p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            ByteBufIO.writeString(out, p.state(), MAX_STATE_LENGTH);
        }

        @Override
        public BlockStateS2C decode(ByteBuf in) {
            return new BlockStateS2C(
                in.readInt(), in.readInt(), in.readInt(),
                ByteBufIO.readString(in, MAX_STATE_LENGTH));
        }
    };
}
