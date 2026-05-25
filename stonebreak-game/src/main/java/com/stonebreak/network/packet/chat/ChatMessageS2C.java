package com.stonebreak.network.packet.chat;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/** Server → all clients chat broadcast. */
public record ChatMessageS2C(int senderId, String senderName, String text) implements Packet {

    public static final PacketCodec<ChatMessageS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ChatMessageS2C p) {
            out.writeInt(p.senderId());
            ByteBufIO.writeString(out, p.senderName(), ByteBufIO.MAX_USERNAME_CHARS);
            ByteBufIO.writeString(out, p.text(), ByteBufIO.MAX_CHAT_CHARS);
        }

        @Override
        public ChatMessageS2C decode(ByteBuf in) {
            return new ChatMessageS2C(
                in.readInt(),
                ByteBufIO.readString(in, ByteBufIO.MAX_USERNAME_CHARS),
                ByteBufIO.readString(in, ByteBufIO.MAX_CHAT_CHARS));
        }
    };
}
