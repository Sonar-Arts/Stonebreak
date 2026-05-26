package com.openmason.engine.net.server;

import com.openmason.engine.net.pipeline.InboundSink;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.transport.NetAddress;
import com.openmason.engine.net.transport.NetServer;
import io.netty.channel.Channel;

import java.net.SocketAddress;

/**
 * Game-agnostic server facade. Bridges Netty's inbound callbacks (via {@link NetServer})
 * into a single {@link ServerInboundQueue} and a {@link ConnectionRegistry}: the owning
 * game server drains one queue on its tick thread and broadcasts through one registry,
 * regardless of how many Local/TCP listeners are bound.
 *
 * <p>The server <b>decodes SERVERBOUND</b> and <b>encodes CLIENTBOUND</b>.
 */
public final class NetworkServer {

    private final ServerInboundQueue inboundQueue = new ServerInboundQueue();
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final NetServer netServer;

    private volatile SocketAddress tcpBoundAddress;

    public NetworkServer(PacketRegistry registry) {
        InboundSink sink = new InboundSink() {
            @Override
            public void onConnect(Channel ch) {
                ServerConnection conn = new ServerConnection(ch);
                ch.attr(ServerConnection.ATTR).set(conn);
                connections.add(conn);
                inboundQueue.postConnect(conn);
            }

            @Override
            public void onPacket(Channel ch, Packet packet) {
                ServerConnection conn = ch.attr(ServerConnection.ATTR).get();
                if (conn != null) {
                    inboundQueue.postPacket(conn, packet);
                }
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
        this.netServer = new NetServer(
            registry, PacketDirection.SERVERBOUND, PacketDirection.CLIENTBOUND, sink);
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
