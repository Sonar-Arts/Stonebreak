package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the authoritative per-block SBO state STRING at a position (furnace
 * lit/unlit + contents + progress today; any future stateful block). Deduped at the source
 * — the server emits only when the string actually changed — so an idle furnace costs
 * nothing. Clients write it through {@code World.setBlockStateAt} (STATE layer variant id, payload in the
 * cube) and feed their display furnace registry so open UIs track live. 64-bit block coordinates.
 *
 * <p>{@code ack} is the {@code seq} of the last furnace/workbench slot intent the server
 * processed FROM THE RECIPIENT at this block (0 if none), so the same echo can carry a
 * different ack per client (issue #353). A client whose latest slot snapshot is newer than
 * the ack knows the echo predates it and keeps its own slots.
 */
public record BlockStateS2C(long x, long y, long z, String state, int ack) implements Packet {

    /** An echo that acknowledges no slot intent (doors, stairs, other players' views). */
    public BlockStateS2C(long x, long y, long z, String state) {
        this(x, y, z, state, 0);
    }

    /** Bound on one state string; furnace states run ~100 chars. */
    public static final int MAX_STATE_LENGTH = 512;

    public static final PacketCodec<BlockStateS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockStateS2C p) {
            NetCoords.writeBlock(out, p.x(), p.y(), p.z());
            ByteBufIO.writeString(out, p.state(), MAX_STATE_LENGTH);
            ByteBufIO.writeVarInt(out, p.ack());
        }

        @Override
        public BlockStateS2C decode(ByteBuf in) {
            long x = NetCoords.readVarLong(in);
            long y = NetCoords.readVarLong(in);
            long z = NetCoords.readVarLong(in);
            return new BlockStateS2C(x, y, z, ByteBufIO.readString(in, MAX_STATE_LENGTH), ByteBufIO.readVarInt(in));
        }
    };
}
