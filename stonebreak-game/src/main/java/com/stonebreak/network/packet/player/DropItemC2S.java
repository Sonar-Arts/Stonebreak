package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: spawn a drop of {@code count} × {@code itemId} at/in front of this player.
 * Two callers:
 * <ul>
 *   <li><b>Manual toss</b> (Q / inventory drop): the item leaves the client inventory and the
 *       server spawns the authoritative drop entity — replicated to everyone. (Previously the
 *       toss spawned a client-local drop that only the tosser could see.)</li>
 * </ul>
 * A DEBIT request: the server drops only what its own replica holds, clamped to 64 per
 * packet, with a toss pickup-delay so the dropper doesn't instantly re-collect. Give
 * overflow never rides this packet — the server re-drops it itself (issue #334).
 */
public record DropItemC2S(int itemId, int count) implements Packet {

    public static final PacketCodec<DropItemC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, DropItemC2S p) {
            out.writeInt(p.itemId());
            out.writeInt(p.count());
        }

        @Override
        public DropItemC2S decode(ByteBuf in) {
            return new DropItemC2S(in.readInt(), in.readInt());
        }
    };
}
