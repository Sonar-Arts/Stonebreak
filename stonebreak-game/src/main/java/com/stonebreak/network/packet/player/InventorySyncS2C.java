package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Server → client: the authoritative contents of THIS player's inventory — the 36 slots
 * (0–8 hotbar, 9–35 main), the stack carried on the container-screen cursor, the selected
 * hotbar slot and the derived held item id. The payload is the {@code '|'}-joined
 * {@link com.stonebreak.items.ItemStackStateCodec} encoding produced by
 * {@code ServerInventory.encode()}.
 *
 * <p>The client's {@code Inventory} is a server-reconciled mirror: this packet replaces
 * its contents wholesale. Sent on join, after every accepted inventory-affecting request,
 * and after every refused one (refusal = "sync, no mutation").
 *
 * <p>{@code ack} is the seq of the newest {@code ContainerActionC2S} the server had
 * processed from this player when it built the sync (0 = none). A client that has sent a
 * newer action keeps its predicted slots: the sync predates that action, and the reply to
 * it (every action is answered with a sync) carries the state that includes it — the
 * inventory-side analogue of {@code BlockStateS2C.ack} (issue #353).
 */
public record InventorySyncS2C(String payload, int ack) implements Packet {

    /** A sync that acknowledges no action. */
    public InventorySyncS2C(String payload) {
        this(payload, 0);
    }

    /** ~4 KB hard cap: 36 slots + carried at ~10 chars each is ~400; generous headroom. */
    private static final int MAX_PAYLOAD = 4096;

    public InventorySyncS2C {
        payload = truncate(payload);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_PAYLOAD ? s.substring(0, MAX_PAYLOAD) : s;
    }

    public static final PacketCodec<InventorySyncS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, InventorySyncS2C p) {
            byte[] bytes = p.payload().getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.writeBytes(bytes);
            ByteBufIO.writeVarInt(out, p.ack());
        }

        @Override
        public InventorySyncS2C decode(ByteBuf in) {
            int len = in.readInt();
            if (len < 0 || len > MAX_PAYLOAD) {
                throw new IllegalArgumentException("InventorySyncS2C payload out of range: " + len);
            }
            byte[] bytes = new byte[len];
            in.readBytes(bytes);
            return new InventorySyncS2C(new String(bytes, StandardCharsets.UTF_8),
                    ByteBufIO.readVarInt(in));
        }
    };
}
