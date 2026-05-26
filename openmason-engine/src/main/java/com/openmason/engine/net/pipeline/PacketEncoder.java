package com.openmason.engine.net.pipeline;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Outbound encoder for remote (TCP) channels: writes the packet's varint id followed by
 * its body, resolving the id + {@link PacketCodec} from the registry by the channel's
 * current phase and this side's outbound direction. Local channels skip this handler.
 */
public final class PacketEncoder extends MessageToByteEncoder<Packet> {

    private final PacketRegistry registry;
    private final PacketDirection outboundDirection;

    public PacketEncoder(PacketRegistry registry, PacketDirection outboundDirection) {
        this.registry = registry;
        this.outboundDirection = outboundDirection;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet msg, ByteBuf out) {
        ProtocolPhase phase = phaseOf(ctx);
        int id = registry.idForClass(phase, outboundDirection, msg.getClass());
        ByteBufIO.writeVarInt(out, id);
        @SuppressWarnings("unchecked")
        PacketCodec<Packet> codec =
            (PacketCodec<Packet>) registry.codecForClass(phase, outboundDirection, msg.getClass());
        codec.encode(out, msg);
    }

    private static ProtocolPhase phaseOf(ChannelHandlerContext ctx) {
        ProtocolPhase p = ctx.channel().attr(PipelineAttributes.PHASE).get();
        return p == null ? ProtocolPhase.HANDSHAKE : p;
    }
}
