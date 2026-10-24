package com.openmason.engine.net.transport;

import com.openmason.engine.net.pipeline.ConnectionLimiter;
import com.openmason.engine.net.pipeline.InboundHandler;
import com.openmason.engine.net.pipeline.InboundRateLimiter;
import com.openmason.engine.net.pipeline.InboundSink;
import com.openmason.engine.net.pipeline.PacketDecoder;
import com.openmason.engine.net.pipeline.PacketEncoder;
import com.openmason.engine.net.pipeline.PipelineAttributes;
import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.ServerLimits;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.function.BiConsumer;

/**
 * Builds the per-channel pipeline. REMOTE (TCP) channels get length framing plus the
 * packet codec; LOCAL channels get only the {@link InboundHandler} (packet objects pass
 * through unserialized). Both terminate in one handler feeding one {@link InboundSink}, so
 * a single code path serves every deployment.
 *
 * <p>Outbound handler order matters: {@link PacketEncoder} is added after the
 * {@link LengthFieldPrepender} so, in tail→head outbound traversal, the packet is encoded
 * to bytes first and the length is prepended second.
 *
 * <p>A server's TCP channels also get the {@link ServerGuards} (issue #336): the connection
 * caps, an idle read timeout, a smaller inbound frame cap and the inbound rate limit.
 */
final class NetChannelInitializer extends ChannelInitializer<Channel> {

    private final TransportType transport;
    private final PacketRegistry registry;
    private final PacketDirection inboundDirection;
    private final PacketDirection outboundDirection;
    private final InboundSink sink;
    /** Abuse limits for a server's remote channels; null for clients and Local channels. */
    private final ServerGuards guards;

    /** What a server applies to each remote channel: shared connection caps + per-channel limits. */
    record ServerGuards(ServerLimits limits, ConnectionLimiter connections,
                        BiConsumer<Channel, String> onAbuse) {}

    NetChannelInitializer(TransportType transport, PacketRegistry registry,
                          PacketDirection inboundDirection, PacketDirection outboundDirection,
                          InboundSink sink) {
        this(transport, registry, inboundDirection, outboundDirection, sink, null);
    }

    NetChannelInitializer(TransportType transport, PacketRegistry registry,
                          PacketDirection inboundDirection, PacketDirection outboundDirection,
                          InboundSink sink, ServerGuards guards) {
        this.transport = transport;
        this.registry = registry;
        this.inboundDirection = inboundDirection;
        this.outboundDirection = outboundDirection;
        this.sink = sink;
        this.guards = guards;
    }

    @Override
    protected void initChannel(Channel ch) {
        // Connections start in PLAY: the current protocol uses a single active phase
        // (a cross-thread HANDSHAKE→PLAY decode transition is racy and deferred). The
        // HANDSHAKE phase value is reserved for a future netty-thread phase transition.
        ch.attr(PipelineAttributes.PHASE).set(ProtocolPhase.PLAY);
        ChannelPipeline p = ch.pipeline();
        if (transport == TransportType.TCP) {
            int maxFrame = ByteBufIO.MAX_FRAME_BYTES;
            if (guards != null) {
                ServerLimits limits = guards.limits();
                p.addLast("connectionLimit", guards.connections());
                p.addLast("readTimeout", new ReadTimeoutHandler(limits.readIdleTimeoutSeconds()));
                maxFrame = limits.maxInboundFrameBytes();
            }
            // maxFrame, lengthFieldOffset=0, lengthFieldLength=4, lengthAdjustment=0, stripBytes=4
            p.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(maxFrame, 0, 4, 0, 4));
            if (guards != null) {
                ServerLimits limits = guards.limits();
                p.addLast("rateLimit", new InboundRateLimiter(limits.packetsPerSecond(),
                    limits.packetBurst(), limits.bytesPerSecond(), limits.byteBurst(), guards.onAbuse()));
            }
            p.addLast("frameEncoder", new LengthFieldPrepender(4));
            p.addLast("packetDecoder", new PacketDecoder(registry, inboundDirection));
            p.addLast("packetEncoder", new PacketEncoder(registry, outboundDirection));
        }
        p.addLast("handler", new InboundHandler(sink));
    }
}
