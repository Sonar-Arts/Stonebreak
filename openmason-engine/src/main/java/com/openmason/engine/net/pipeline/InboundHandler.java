package com.openmason.engine.net.pipeline;

import com.openmason.engine.net.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terminal inbound handler. It decodes nothing — it only hands fully-formed {@link Packet}
 * objects to an {@link InboundSink}. On remote channels the packets are produced upstream
 * by {@link PacketDecoder}; on local channels they arrive directly (zero serialization).
 *
 * <p>This runs on a Netty event-loop thread and upholds the core invariant: enqueue only,
 * never mutate game state here. Connection lifecycle is surfaced as synthetic
 * connect/disconnect so join/leave runs on the owning (tick/game) thread.
 */
public final class InboundHandler extends SimpleChannelInboundHandler<Packet> {

    private static final Logger log = LoggerFactory.getLogger(InboundHandler.class);

    private final InboundSink sink;

    public InboundHandler(InboundSink sink) {
        this.sink = sink;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        sink.onConnect(ctx.channel());
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet msg) {
        sink.onPacket(ctx.channel(), msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Throwable cause = ctx.channel().attr(PipelineAttributes.CAUSE).get();
        sink.onDisconnect(ctx.channel(), cause);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Stash the cause for channelInactive, then close — the disconnect is reported once.
        log.debug("Network channel error ({}): {}", ctx.channel(), cause.toString());
        ctx.channel().attr(PipelineAttributes.CAUSE).set(cause);
        ctx.close();
    }
}
