package com.openmason.engine.net.transport;

import com.openmason.engine.net.pipeline.InboundHandler;
import com.openmason.engine.net.pipeline.InboundSink;
import com.openmason.engine.net.pipeline.PacketDecoder;
import com.openmason.engine.net.pipeline.PacketEncoder;
import com.openmason.engine.net.pipeline.PipelineAttributes;
import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

/**
 * Builds the per-channel pipeline. REMOTE (TCP) channels get length framing plus the
 * packet codec; LOCAL channels get only the {@link InboundHandler} (packet objects pass
 * through unserialized). Both terminate in one handler feeding one {@link InboundSink}, so
 * a single code path serves every deployment.
 *
 * <p>Outbound handler order matters: {@link PacketEncoder} is added after the
 * {@link LengthFieldPrepender} so, in tail→head outbound traversal, the packet is encoded
 * to bytes first and the length is prepended second.
 */
final class NetChannelInitializer extends ChannelInitializer<Channel> {

    private final TransportType transport;
    private final PacketRegistry registry;
    private final PacketDirection inboundDirection;
    private final PacketDirection outboundDirection;
    private final InboundSink sink;

    NetChannelInitializer(TransportType transport, PacketRegistry registry,
                          PacketDirection inboundDirection, PacketDirection outboundDirection,
                          InboundSink sink) {
        this.transport = transport;
        this.registry = registry;
        this.inboundDirection = inboundDirection;
        this.outboundDirection = outboundDirection;
        this.sink = sink;
    }

    @Override
    protected void initChannel(Channel ch) {
        // Connections start in PLAY: the current protocol uses a single active phase
        // (a cross-thread HANDSHAKE→PLAY decode transition is racy and deferred). The
        // HANDSHAKE phase value is reserved for a future netty-thread phase transition.
        ch.attr(PipelineAttributes.PHASE).set(ProtocolPhase.PLAY);
        ChannelPipeline p = ch.pipeline();
        if (transport == TransportType.TCP) {
            // maxFrame, lengthFieldOffset=0, lengthFieldLength=4, lengthAdjustment=0, stripBytes=4
            p.addLast("frameDecoder",
                new LengthFieldBasedFrameDecoder(ByteBufIO.MAX_FRAME_BYTES, 0, 4, 0, 4));
            p.addLast("frameEncoder", new LengthFieldPrepender(4));
            p.addLast("packetDecoder", new PacketDecoder(registry, inboundDirection));
            p.addLast("packetEncoder", new PacketEncoder(registry, outboundDirection));
        }
        p.addLast("handler", new InboundHandler(sink));
    }
}
