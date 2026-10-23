package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.SequencedIntent;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the local player placed or broke a block (an intent, server-validated).
 *
 * <p>{@code placementState} is an OPTIONAL client-proposed per-position state string for a
 * placed block whose state depends on what the client clicked (e.g. a torch's Ground/Side +
 * facing comes from the targeted face, which the server cannot reconstruct from the player's
 * position alone). The server validates it per block type before applying — it is a proposal,
 * never trusted verbatim. Empty for breaks and for blocks whose state the server derives itself.
 *
 * <p>{@code prevBlockTypeId} is the client's view of the block AT the moment they edited it.
 * The server never takes it as "what was broken" — drops and container cleanup come from the
 * server's own cell (issues #333, #370). It is the edit's precondition: a break of a block the
 * server holds as something else (another player replaced it first) is refused, and the real
 * block is echoed back. 0 (air) means "unknown" and is not checked.
 *
 * <p>{@code seq} numbers the intent ({@link SequencedIntent}): a placement or bucket use has
 * already changed the client's inventory mirror, so an inventory sync built before the server
 * saw this edit must not restore what it spent.
 */
public record BlockChangeC2S(int x, int y, int z, short blockTypeId, short prevBlockTypeId,
                             String placementState, int seq) implements Packet, SequencedIntent {

    /** Upper bound on a proposed state string (torch/door/stair strings are &lt; 40 chars). */
    public static final int MAX_STATE_CHARS = 96;

    /** A plain, unnumbered block change with no proposed placement state. */
    public BlockChangeC2S(int x, int y, int z, short blockTypeId, short prevBlockTypeId) {
        this(x, y, z, blockTypeId, prevBlockTypeId, "", 0);
    }

    /** An unnumbered block change with a proposed placement state. */
    public BlockChangeC2S(int x, int y, int z, short blockTypeId, short prevBlockTypeId,
                          String placementState) {
        this(x, y, z, blockTypeId, prevBlockTypeId, placementState, 0);
    }

    public BlockChangeC2S {
        if (placementState == null) placementState = "";
    }

    /** True when the client proposed a per-position state for the placed block. */
    public boolean hasPlacementState() {
        return !placementState.isEmpty();
    }

    public static final PacketCodec<BlockChangeC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockChangeC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            out.writeShort(p.blockTypeId());
            out.writeShort(p.prevBlockTypeId());
            ByteBufIO.writeString(out, p.placementState(), MAX_STATE_CHARS);
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public BlockChangeC2S decode(ByteBuf in) {
            return new BlockChangeC2S(in.readInt(), in.readInt(), in.readInt(), in.readShort(), in.readShort(),
                    ByteBufIO.readString(in, MAX_STATE_CHARS), ByteBufIO.readVarInt(in));
        }
    };
}
