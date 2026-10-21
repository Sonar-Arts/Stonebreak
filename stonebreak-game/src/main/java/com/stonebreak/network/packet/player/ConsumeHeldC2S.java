package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the player consumed (ate) one {@code itemId} from the selected hotbar
 * slot (issue #355). The server debits its own replica and applies the same depletion
 * auto-switch the client predicted, so the next {@code InventorySyncS2C} neither restores
 * the eaten item nor reverts the switched-in stack. {@code seq} is a {@code SlotIntentSeq}
 * number, acknowledged by the reply sync like a container action.
 */
public record ConsumeHeldC2S(int itemId, int seq) implements Packet {

    public static final PacketCodec<ConsumeHeldC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ConsumeHeldC2S p) {
            ByteBufIO.writeVarInt(out, p.itemId());
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public ConsumeHeldC2S decode(ByteBuf in) {
            return new ConsumeHeldC2S(ByteBufIO.readVarInt(in), ByteBufIO.readVarInt(in));
        }
    };
}
