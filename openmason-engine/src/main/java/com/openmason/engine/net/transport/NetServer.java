package com.openmason.engine.net.transport;

import com.openmason.engine.net.pipeline.InboundSink;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-level Netty server. Owns the event-loop groups and binds a Local listener (always,
 * for the co-located player) and/or a TCP listener (for remote clients). Both listeners
 * funnel their child channels through the same {@link InboundSink}, so one inbound queue
 * and one tick consumer serve singleplayer, LAN host, and a future dedicated server alike.
 */
public final class NetServer {

    private static final int WRITE_LOW_WATER  = 32 * 1024;
    private static final int WRITE_HIGH_WATER = 256 * 1024;

    private final PacketRegistry registry;
    private final PacketDirection inboundDirection;
    private final PacketDirection outboundDirection;
    private final InboundSink sink;

    private EventLoopGroup bossGroup;    // TCP accept
    private EventLoopGroup workerGroup;  // TCP child IO
    private EventLoopGroup localGroup;   // Local (boss + worker)
    private final List<Channel> listenChannels = new ArrayList<>();

    public NetServer(PacketRegistry registry, PacketDirection inboundDirection,
                     PacketDirection outboundDirection, InboundSink sink) {
        this.registry = registry;
        this.inboundDirection = inboundDirection;
        this.outboundDirection = outboundDirection;
        this.sink = sink;
    }

    /** Bind the in-JVM Local listener. Returns the bound address. */
    public synchronized SocketAddress bindLocal(SocketAddress address) throws InterruptedException {
        if (localGroup == null) {
            localGroup = new DefaultEventLoopGroup();
        }
        ServerBootstrap b = new ServerBootstrap()
            .group(localGroup, localGroup)
            .channel(LocalServerChannel.class)
            .childHandler(initializer(TransportType.LOCAL));
        Channel ch = b.bind(address).sync().channel();
        listenChannels.add(ch);
        return ch.localAddress();
    }

    /** Bind the TCP listener. Returns the bound address (resolves an ephemeral port). */
    public synchronized SocketAddress bindTcp(SocketAddress address) throws InterruptedException {
        if (bossGroup == null) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
        }
        ServerBootstrap b = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                new WriteBufferWaterMark(WRITE_LOW_WATER, WRITE_HIGH_WATER))
            .childHandler(initializer(TransportType.TCP));
        Channel ch = b.bind(address).sync().channel();
        listenChannels.add(ch);
        return ch.localAddress();
    }

    private NetChannelInitializer initializer(TransportType transport) {
        return new NetChannelInitializer(transport, registry, inboundDirection, outboundDirection, sink);
    }

    public synchronized void close() {
        for (Channel ch : listenChannels) {
            ch.close();
        }
        listenChannels.clear();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (localGroup != null) localGroup.shutdownGracefully();
        bossGroup = null;
        workerGroup = null;
        localGroup = null;
    }
}
