package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: full slot snapshot of the furnace UI the player is editing —
 * {@code slots} is {@code FurnaceState.encodeSlots()} ({@code ing|fuel|out}, each
 * {@code kind:id:count}). Sent whenever the open UI's slots change (drag, place, take).
 *
 * <p>A full snapshot instead of per-op deltas: the drag-drop UI supports arbitrary stack
 * splits that don't map losslessly onto a small op set, and snapshot semantics are
 * echo-corrected — the server applies slots (never timers), re-ticks, and the resulting
 * {@code BlockStateS2C} echo overwrites any client optimism.
 *
 * <p>{@code seq} numbers the intent (issue #353): the server stamps the last one it processed
 * on the sender's echoes as {@code BlockStateS2C.ack}, so the client ignores the slots of an
 * echo that left before its latest snapshot arrived instead of rolling back to them.
 */
public record FurnaceSlotsC2S(int x, int y, int z, int seq, String slots) implements Packet {

    /** Bound on the encoded slot string (three {@code kind:id:count[:state]} tokens). */
    public static final int MAX_SLOTS_LENGTH = 512;

    public static final PacketCodec<FurnaceSlotsC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, FurnaceSlotsC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            ByteBufIO.writeVarInt(out, p.seq());
            ByteBufIO.writeString(out, p.slots(), MAX_SLOTS_LENGTH);
        }

        @Override
        public FurnaceSlotsC2S decode(ByteBuf in) {
            return new FurnaceSlotsC2S(
                in.readInt(), in.readInt(), in.readInt(),
                ByteBufIO.readVarInt(in),
                ByteBufIO.readString(in, MAX_SLOTS_LENGTH));
        }
    };
}
