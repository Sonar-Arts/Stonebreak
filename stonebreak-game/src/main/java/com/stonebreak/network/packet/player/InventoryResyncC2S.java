package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: this client skipped an {@code InventorySyncS2C} because it was built before
 * one of the client's own numbered intents arrived — please send the current inventory again.
 *
 * <p>The skipped sync may have carried changes the client never predicted (a pickup, a refund,
 * a refusal), and an accepted placement or toss is not always answered with a sync of its own.
 * Packets on one connection are processed in order, so the answer is built after every intent
 * sent before this request and acknowledges all of them. If the client sent another intent in
 * the meantime it skips that answer too and asks again; the exchange ends as soon as the
 * player pauses. The server answers at most once per inbound drain.
 */
public record InventoryResyncC2S() implements Packet {

    public static final PacketCodec<InventoryResyncC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, InventoryResyncC2S p) {
            // no payload
        }

        @Override
        public InventoryResyncC2S decode(ByteBuf in) {
            return new InventoryResyncC2S();
        }
    };
}
