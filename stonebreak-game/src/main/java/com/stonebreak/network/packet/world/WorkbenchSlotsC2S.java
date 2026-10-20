package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: full grid snapshot of the crafting-table UI the player is editing —
 * {@code slots} is {@code WorkbenchState.encodeSlots()} (nine {@code |}-separated
 * {@code kind:id:count[:state]} tokens, row-major). Sent whenever the open UI's grid changes
 * (drag, place, take, craft). Same snapshot + echo-correction semantics as
 * {@link FurnaceSlotsC2S}: the server persists the grid and its {@code BlockStateS2C} echo
 * overwrites any client optimism. {@code seq} numbers the intent (issue #353): the server
 * stamps the last one it processed on the sender's echoes as {@code BlockStateS2C.ack}, so
 * the client can tell an echo that predates its latest snapshot from a current one.
 */
public record WorkbenchSlotsC2S(int x, int y, int z, int seq, String slots) implements Packet {

    /** Bound on the encoded grid (nine tokens of at most ~45 chars). */
    public static final int MAX_SLOTS_LENGTH = 512;

    public static final PacketCodec<WorkbenchSlotsC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, WorkbenchSlotsC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            ByteBufIO.writeVarInt(out, p.seq());
            ByteBufIO.writeString(out, p.slots(), MAX_SLOTS_LENGTH);
        }

        @Override
        public WorkbenchSlotsC2S decode(ByteBuf in) {
            return new WorkbenchSlotsC2S(
                in.readInt(), in.readInt(), in.readInt(),
                ByteBufIO.readVarInt(in),
                ByteBufIO.readString(in, MAX_SLOTS_LENGTH));
        }
    };
}
