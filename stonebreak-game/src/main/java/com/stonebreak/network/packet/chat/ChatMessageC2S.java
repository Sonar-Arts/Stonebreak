package com.stonebreak.network.packet.chat;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Client → server chat submission. The server attaches the sender name and broadcasts. */
public record ChatMessageC2S(String text) implements Packet {

    public static final PacketCodec<ChatMessageC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ChatMessageC2S p) {
            ByteBufIO.writeString(out, p.text(), ByteBufIO.MAX_CHAT_CHARS);
        }

        @Override
        public ChatMessageC2S decode(ByteBuf in) {
            return new ChatMessageC2S(ByteBufIO.readString(in, ByteBufIO.MAX_CHAT_CHARS));
        }
    };
}
