package com.openmason.engine.net.transport;

import com.openmason.engine.net.pipeline.InboundSink;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Low-level Netty client. Connects to a {@link NetAddress}, selecting a LocalChannel + a
 * local group (in-JVM, zero serialization) or a NioSocketChannel + an NIO group (TCP) from
 * the address type, and wiring the matching pipeline.
 */
public final class NetClient {

    private static final int WRITE_LOW_WATER  = 32 * 1024;
    private static final int WRITE_HIGH_WATER = 256 * 1024;

    private final PacketRegistry registry;
    private final PacketDirection inboundDirection;
    private final PacketDirection outboundDirection;
    private final InboundSink sink;

    private EventLoopGroup group;

    public NetClient(PacketRegistry registry, PacketDirection inboundDirection,
                     PacketDirection outboundDirection, InboundSink sink) {
        this.registry = registry;
        this.inboundDirection = inboundDirection;
        this.outboundDirection = outboundDirection;
        this.sink = sink;
    }

    /** Connect synchronously and return the connected channel. */
    public synchronized Channel connect(NetAddress address) throws InterruptedException {
        Bootstrap b = new Bootstrap();
        if (address.type() == TransportType.LOCAL) {
            group = new DefaultEventLoopGroup();
            b.group(group).channel(LocalChannel.class);
        } else {
            group = new NioEventLoopGroup();
            b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                    new WriteBufferWaterMark(WRITE_LOW_WATER, WRITE_HIGH_WATER));
        }
        b.handler(new NetChannelInitializer(address.type(), registry, inboundDirection, outboundDirection, sink));
        return b.connect(address.socketAddress()).sync().channel();
    }

    public synchronized void close() {
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
    }
}
