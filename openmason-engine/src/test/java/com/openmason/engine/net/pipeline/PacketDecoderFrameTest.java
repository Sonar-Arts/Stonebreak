package com.openmason.engine.net.pipeline;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Issue #336: one frame is exactly one packet; leftover bytes reject the frame. */
class PacketDecoderFrameTest {

    private record Num(int n) implements Packet { }

    private static final PacketCodec<Num> NUM = new PacketCodec<>() {
        @Override public void encode(ByteBuf out, Num p) { out.writeInt(p.n()); }
        @Override public Num decode(ByteBuf in) { return new Num(in.readInt()); }
    };

    private static PacketRegistry registry() {
        PacketRegistry reg = new PacketRegistry();
        reg.register(ProtocolPhase.PLAY, PacketDirection.SERVERBOUND, 1, Num.class, NUM);
        return reg;
    }

    private static ByteBuf frame(int n, int trailing) {
        ByteBuf buf = Unpooled.buffer();
        ByteBufIO.writeVarInt(buf, 1);
        buf.writeInt(n);
        buf.writeZero(trailing);
        return buf;
    }

    private static EmbeddedChannel channel() {
        EmbeddedChannel ch = new EmbeddedChannel(new PacketDecoder(registry(), PacketDirection.SERVERBOUND));
        ch.attr(PipelineAttributes.PHASE).set(ProtocolPhase.PLAY);
        return ch;
    }

    @Test
    void wholeFrameDecodesToOnePacket() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(frame(42, 0));
        assertEquals(new Num(42), ch.readInbound());
        assertNull(ch.readInbound());
    }

    @Test
    void leftoverBytesAreRejectedNotDecodedAsAnotherPacket() {
        EmbeddedChannel ch = channel();
        // The trailing bytes spell a second valid packet (id 1, n = 7): it must not be decoded.
        ByteBuf buf = frame(42, 0);
        ByteBufIO.writeVarInt(buf, 1);
        buf.writeInt(7);

        assertThrows(DecoderException.class, () -> ch.writeInbound(buf));
        assertNull(ch.readInbound());
    }

    @Test
    void underReadingCodecIsAMalformedFrame() {
        assertThrows(DecoderException.class, () -> PacketDecoder.decodeFrame(registry(),
                ProtocolPhase.PLAY, PacketDirection.SERVERBOUND, frame(1, 2)));
    }
}
