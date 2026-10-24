package com.openmason.engine.net.pipeline;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps open connections, in total and per remote IP (issue #336). One instance is shared by
 * every child channel of a listener. A channel over either cap is closed before its
 * {@code channelActive} reaches the rest of the pipeline, so the server never sees it as a
 * connection at all.
 */
@ChannelHandler.Sharable
public final class ConnectionLimiter extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionLimiter.class);

    /** The address a counted channel was counted under; absent = not counted. */
    private static final AttributeKey<Object> COUNTED_AS = AttributeKey.valueOf("openmason.net.countedAs");
    /** Key for a channel whose remote address is not an IP socket address. */
    private static final Object UNKNOWN_ADDRESS = new Object();

    private final int maxTotal;
    private final int maxPerAddress;
    private final AtomicInteger total = new AtomicInteger();
    private final ConcurrentHashMap<Object, AtomicInteger> perAddress = new ConcurrentHashMap<>();

    public ConnectionLimiter(int maxTotal, int maxPerAddress) {
        this.maxTotal = maxTotal;
        this.maxPerAddress = maxPerAddress;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Object key = addressKey(ctx.channel().remoteAddress());
        int all = total.incrementAndGet();
        int fromAddress = perAddress.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (all > maxTotal || fromAddress > maxPerAddress) {
            release(key);
            log.info("Refusing connection from {}: {} open ({} from that address)",
                ctx.channel().remoteAddress(), all - 1, fromAddress - 1);
            ctx.close();
            return;
        }
        ctx.channel().attr(COUNTED_AS).set(key);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Object key = ctx.channel().attr(COUNTED_AS).getAndSet(null);
        if (key != null) {
            release(key);
        }
        ctx.fireChannelInactive();
    }

    /** Connections currently counted (for tests and diagnostics). */
    public int openConnections() {
        return total.get();
    }

    private void release(Object key) {
        total.decrementAndGet();
        perAddress.computeIfPresent(key, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
    }

    private static Object addressKey(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            InetAddress ip = inet.getAddress();
            return ip != null ? ip : inet.getHostString();
        }
        return UNKNOWN_ADDRESS;
    }
}
