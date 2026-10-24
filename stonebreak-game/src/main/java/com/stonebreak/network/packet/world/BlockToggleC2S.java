package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.SequencedIntent;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: intent to toggle an interactable block's state at a
 * position (today: a door flipping Open ↔ Closed). Carries no target state —
 * the server owns the transition: it validates (reach, block is toggleable),
 * flips the authoritative block-state string, and echoes the result to all
 * clients (originator included) as {@link BlockStateS2C}. On a stalagmite the
 * toggle is the water-bucket use that grows it, which the client has already
 * emptied — {@code seq} numbers the intent ({@link SequencedIntent}).
 */
public record BlockToggleC2S(int x, int y, int z, int seq) implements Packet, SequencedIntent {

    /** An unnumbered toggle. */
    public BlockToggleC2S(int x, int y, int z) {
        this(x, y, z, 0);
    }

    public static final PacketCodec<BlockToggleC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockToggleC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public BlockToggleC2S decode(ByteBuf in) {
            return new BlockToggleC2S(in.readInt(), in.readInt(), in.readInt(), ByteBufIO.readVarInt(in));
        }
    };
}
