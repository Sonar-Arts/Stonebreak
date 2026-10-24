package com.openmason.engine.net.server;

import com.openmason.engine.net.pipeline.InboundSink;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.transport.NetAddress;
import com.openmason.engine.net.transport.NetServer;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Game-agnostic server facade. Bridges Netty's inbound callbacks (via {@link NetServer})
 * into a single {@link ServerInboundQueue} and a {@link ConnectionRegistry}: the owning
 * game server drains one queue on its tick thread and broadcasts through one registry,
 * regardless of how many Local/TCP listeners are bound.
 *
 * <p>The server <b>decodes SERVERBOUND</b> and <b>encodes CLIENTBOUND</b>.
 *
 * <p>Remote (TCP) connections are held to {@link ServerLimits} (issue #336). Each must be
 * <em>admitted</em> — send a packet the {@link #setAdmissionFilter admission filter} accepts,
 * the game's handshake — within {@link ServerLimits#admissionTimeoutMillis}, or it is closed;
 * until then its other packets are dropped. Local connections are admitted on connect.
 */
public final class NetworkServer {

    private static final Logger log = LoggerFactory.getLogger(NetworkServer.class);

    private final ServerLimits limits;
    private final ServerInboundQueue inboundQueue;
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final NetServer netServer;

    private volatile Predicate<Packet> admissionFilter = p -> true;
    private volatile Function<String, Packet> kickPacketFactory;
    private volatile SocketAddress tcpBoundAddress;

    public NetworkServer(PacketRegistry registry) {
        this(registry, ServerLimits.DEFAULTS);
    }

    public NetworkServer(PacketRegistry registry, ServerLimits limits) {
        this.limits = limits;
        this.inboundQueue = new ServerInboundQueue(limits);
        InboundSink sink = new InboundSink() {
            @Override
            public void onConnect(Channel ch) {
                boolean local = ch instanceof LocalChannel;
                ServerConnection conn = new ServerConnection(ch, local ? 0L : limits.maxOutboundBytes());
                conn.setKickPacket(kickPacketFactory);
                ch.attr(ServerConnection.ATTR).set(conn);
                if (local) {
                    conn.markAdmitted(); // the host's own in-JVM client
                } else {
                    ch.eventLoop().schedule(() -> {
                        if (!conn.isAdmitted() && ch.isActive()) {
                            log.info("Closing {} ({}): no handshake within {} ms",
                                conn, ch.remoteAddress(), limits.admissionTimeoutMillis());
                            ch.close();
                        }
                    }, limits.admissionTimeoutMillis(), TimeUnit.MILLISECONDS);
                }
                connections.add(conn);
                inboundQueue.postConnect(conn);
            }

            @Override
            public void onPacket(Channel ch, Packet packet) {
                ServerConnection conn = ch.attr(ServerConnection.ATTR).get();
                if (conn == null) {
                    return;
                }
                if (!conn.isAdmitted()) {
                    if (!admissionFilter.test(packet)) {
                        return; // nothing but the handshake is taken from a stranger
                    }
                    conn.markAdmitted();
                }
                inboundQueue.postPacket(conn, packet);
            }

            @Override
            public void onDisconnect(Channel ch, Throwable cause) {
                ServerConnection conn = ch.attr(ServerConnection.ATTR).get();
                if (conn != null) {
                    connections.remove(conn);
                    inboundQueue.postDisconnect(conn, cause);
                }
            }
        };
        this.netServer = new NetServer(registry, PacketDirection.SERVERBOUND,
            PacketDirection.CLIENTBOUND, sink, limits, this::onAbuse);
    }

    /** A remote channel broke a rate limit (event loop): kick it with the reason. */
    private void onAbuse(Channel ch, String reason) {
        ServerConnection conn = ch.attr(ServerConnection.ATTR).get();
        log.info("Kicking {} ({}): {}", conn, ch.remoteAddress(), reason);
        if (conn != null) {
            conn.kick(reason);
        } else {
            ch.close();
        }
    }

    /**
     * Which packet admits a remote connection (the game's handshake). Before it arrives,
     * every other packet from that connection is dropped, and a connection that sends none
     * within {@link ServerLimits#admissionTimeoutMillis} is closed. Default: any packet.
     */
    public void setAdmissionFilter(Predicate<Packet> filter) {
        this.admissionFilter = filter == null ? p -> true : filter;
    }

    /**
     * Builds the packet a kick carries its reason in (the game's kick packet), used when the
     * server drops a connection for abuse. Without one, kicks just close. Set before
     * {@link #start}.
     */
    public void setKickPacketFactory(Function<String, Packet> factory) {
        this.kickPacketFactory = factory;
    }

    /**
     * Start listening. {@code localAddress} (in-JVM) is bound when non-null;
     * {@code tcpAddress} when non-null. Singleplayer passes only a local address; a host
     * passes both; a future dedicated server would pass only TCP.
     */
    public void start(NetAddress localAddress, NetAddress tcpAddress) throws InterruptedException {
        if (localAddress != null) {
            netServer.bindLocal(localAddress.socketAddress());
        }
        if (tcpAddress != null) {
            tcpBoundAddress = netServer.bindTcp(tcpAddress.socketAddress());
        }
    }

    public ServerLimits limits() {
        return limits;
    }

    public ServerInboundQueue inboundQueue() {
        return inboundQueue;
    }

    public ConnectionRegistry connections() {
        return connections;
    }

    /** Actual bound TCP address (resolves the ephemeral port after a {@code bind(0)}). */
    public SocketAddress tcpBoundAddress() {
        return tcpBoundAddress;
    }

    public void shutdown() {
        netServer.close();
    }
}
