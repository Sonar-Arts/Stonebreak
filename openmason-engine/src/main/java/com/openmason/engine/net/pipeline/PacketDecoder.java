package com.openmason.engine.net.pipeline;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;

import java.util.List;

/**
 * Inbound decoder for remote (TCP) channels. Sits behind a length-field frame decoder, so
 * each invocation holds exactly one framed packet: it reads the varint id, resolves the
 * {@link PacketCodec} by the channel's phase + this side's inbound direction, and decodes
 * the body into a {@link Packet}. Local channels skip this handler.
 */
public final class PacketDecoder extends ByteToMessageDecoder {

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
        int id = ByteBufIO.readVarInt(in);
        PacketCodec<?> codec = registry.codecForId(phase, inboundDirection, id);
        if (codec == null) {
            throw new DecoderException(
                "Unknown packet id " + id + " in phase " + phase + "/" + inboundDirection);
        }
        out.add(codec.decode(in));
    }
}
