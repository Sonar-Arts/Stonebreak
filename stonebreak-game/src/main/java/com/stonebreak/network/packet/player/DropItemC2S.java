package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetItemStack;
import com.stonebreak.network.packet.SequencedIntent;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: spawn a drop of {@code stack} at/in front of this player.
 * Two callers:
 * <ul>
 *   <li><b>Manual toss</b> (Q / inventory drop): the item leaves the client inventory and the
 *       server spawns the authoritative drop entity — replicated to everyone. (Previously the
 *       toss spawned a client-local drop that only the tosser could see.)</li>
 * </ul>
 * A DEBIT request: the server drops only what its own replica holds, clamped to 64 per
 * packet, with a toss pickup-delay so the dropper doesn't instantly re-collect. The stack
 * names its state (issue #342), so the server debits exactly that kind — tossing a water
 * bucket from the inventory screen never takes the empty one in the hand. Give overflow
 * never rides this packet — the server re-drops it itself (issue #334).
 * {@code seq} numbers the toss ({@link SequencedIntent}): the item already left the mirror.
 */
public record DropItemC2S(NetItemStack stack, int seq) implements Packet, SequencedIntent {

    public DropItemC2S {
        stack = stack != null ? stack : NetItemStack.EMPTY;
    }

    /** An unnumbered toss in the item's default state. */
    public DropItemC2S(int itemId, int count) {
        this(new NetItemStack(itemId, count), 0);
    }

    /** A numbered toss in the item's default state. */
    public DropItemC2S(int itemId, int count, int seq) {
        this(new NetItemStack(itemId, count), seq);
    }

    public int itemId() {
        return stack.itemId();
    }

    public int count() {
        return stack.count();
    }

    public static final PacketCodec<DropItemC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, DropItemC2S p) {
            NetItemStack.write(out, p.stack());
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public DropItemC2S decode(ByteBuf in) {
            return new DropItemC2S(NetItemStack.read(in), ByteBufIO.readVarInt(in));
        }
    };
}
