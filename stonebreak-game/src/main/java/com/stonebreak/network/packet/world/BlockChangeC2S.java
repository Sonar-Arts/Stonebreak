package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
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
 * <p>{@code prevBlockTypeId} is the client's view of the block AT the moment they edited it —
 * needed for break-drop spawning so the server isn't forced to infer "what was there" from its
 * own (possibly out-of-sync) world snapshot. The server treats {@code prevBlockTypeId} as the
 * authoritative source of "what the player broke" for drop decisions; it still applies
 * {@code blockTypeId} to its own world for state correctness.
 */
public record BlockChangeC2S(int x, int y, int z, short blockTypeId, short prevBlockTypeId,
                             String placementState) implements Packet {

    /** Upper bound on a proposed state string (torch/door/stair strings are &lt; 40 chars). */
    public static final int MAX_STATE_CHARS = 96;

    /** A plain block change with no proposed placement state. */
    public BlockChangeC2S(int x, int y, int z, short blockTypeId, short prevBlockTypeId) {
        this(x, y, z, blockTypeId, prevBlockTypeId, "");
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
        }

        @Override
        public BlockChangeC2S decode(ByteBuf in) {
            return new BlockChangeC2S(in.readInt(), in.readInt(), in.readInt(), in.readShort(), in.readShort(),
                    ByteBufIO.readString(in, MAX_STATE_CHARS));
        }
    };
}
