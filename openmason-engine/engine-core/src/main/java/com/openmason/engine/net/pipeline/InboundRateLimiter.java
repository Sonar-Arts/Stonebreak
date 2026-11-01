package com.openmason.engine.net.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.function.BiConsumer;

/**
 * Per-connection inbound rate limit (issue #336). Sits right behind the frame decoder, so
 * every message is one framed packet: each costs one packet token and its size in byte
 * tokens. A connection that runs either bucket dry is reported once to {@code onAbuse} (the
 * server kicks it) and stops being read; frames already buffered are discarded.
 */
public final class InboundRateLimiter extends ChannelInboundHandlerAdapter {

    private final TokenBucket packets;
    private final TokenBucket bytes;
    private final BiConsumer<Channel, String> onAbuse;
    private boolean tripped;

    public InboundRateLimiter(double packetsPerSecond, double packetBurst,
                              double bytesPerSecond, double byteBurst,
                              BiConsumer<Channel, String> onAbuse) {
        this.packets = new TokenBucket(packetBurst, packetsPerSecond);
        this.bytes = new TokenBucket(byteBurst, bytesPerSecond);
        this.onAbuse = onAbuse;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (tripped) {
            ReferenceCountUtil.release(msg);
            return;
        }
        long now = System.nanoTime();
        int size = msg instanceof ByteBuf buf ? buf.readableBytes() : 0;
        if (!packets.tryTake(now) || !bytes.tryTake(size, now)) {
            tripped = true;
            ReferenceCountUtil.release(msg);
            ctx.channel().config().setAutoRead(false);
            onAbuse.accept(ctx.channel(), "Sending packets too fast.");
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
