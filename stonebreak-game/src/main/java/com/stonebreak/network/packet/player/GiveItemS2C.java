package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetItemStack;
import io.netty.buffer.ByteBuf;

/**
 * Server → one client: hand the receiving client this item stack (authoritative drop
 * pickup, command-give, etc.). The client adds it to its local inventory mirror; the
 * inventory sync that follows is the reconciliation point. The stack keeps its state
 * (issue #342): a picked-up water bucket arrives full.
 */
public record GiveItemS2C(NetItemStack stack) implements Packet {

    public GiveItemS2C {
        stack = stack != null ? stack : NetItemStack.EMPTY;
    }

    /** A give in the item's default state. */
    public GiveItemS2C(int itemId, int count) {
        this(new NetItemStack(itemId, count));
    }

    public int itemId() {
        return stack.itemId();
    }

    public int count() {
        return stack.count();
    }

    public static final PacketCodec<GiveItemS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, GiveItemS2C p) {
            NetItemStack.write(out, p.stack());
        }

        @Override
        public GiveItemS2C decode(ByteBuf in) {
            return new GiveItemS2C(NetItemStack.read(in));
        }
    };
}
