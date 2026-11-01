package com.openmason.engine.net.pipeline;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Inbound decoder for remote (TCP) channels. Sits behind a length-field frame decoder, so
 * each invocation holds exactly one framed packet: it reads the varint id, resolves the
 * {@link PacketCodec} by the channel's phase + this side's inbound direction, and decodes
 * the body into a {@link Packet}. Local channels skip this handler.
 *
 * <p>A frame is exactly one packet: bytes the codec leaves unread are a malformed frame and
 * close the connection (issue #336) — they used to be decoded as further packets, so a codec
 * that under-read desynced the stream instead of being rejected.
 */
public final class PacketDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final PacketRegistry registry;
    private final PacketDirection inboundDirection;

    public PacketDecoder(PacketRegistry registry, PacketDirection inboundDirection) {
        this.registry = registry;
        this.inboundDirection = inboundDirection;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        ProtocolPhase phase = ctx.channel().attr(PipelineAttributes.PHASE).get();
        if (phase == null) {
            phase = ProtocolPhase.HANDSHAKE;
        }
        out.add(decodeFrame(registry, phase, inboundDirection, in));
    }

    /** Decodes one whole frame into one packet; a frame with bytes left over is rejected. */
    static Packet decodeFrame(PacketRegistry registry, ProtocolPhase phase,
                              PacketDirection direction, ByteBuf in) {
        int id = ByteBufIO.readVarInt(in);
        PacketCodec<?> codec = registry.codecForId(phase, direction, id);
        if (codec == null) {
            throw new DecoderException(
                "Unknown packet id " + id + " in phase " + phase + "/" + direction);
        }
        Packet packet = codec.decode(in);
        if (in.isReadable()) {
            throw new DecoderException(in.readableBytes() + " unread byte(s) after packet "
                + packet.getClass().getSimpleName() + " (id " + id + ")");
        }
        return packet;
    }
}
