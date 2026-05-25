package com.openmason.engine.net.client;

import com.openmason.engine.net.pipeline.InboundSink;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.transport.NetAddress;
import com.openmason.engine.net.transport.NetClient;
import io.netty.channel.Channel;

/**
 * Game-agnostic client facade. Connects to a server (Local or TCP) and surfaces inbound
 * traffic through a single {@link ClientInboundQueue}, drained on the game thread.
 *
 * <p>The client <b>decodes CLIENTBOUND</b> and <b>encodes SERVERBOUND</b>.
 */
public final class NetworkClient {

    private final ClientInboundQueue inboundQueue = new ClientInboundQueue();
    private final NetClient netClient;
    private volatile ClientConnection connection;

    public NetworkClient(PacketRegistry registry) {
        InboundSink sink = new InboundSink() {
            @Override
            public void onConnect(Channel ch) {
                inboundQueue.postConnect();
            }

            @Override
            public void onPacket(Channel ch, Packet packet) {
                inboundQueue.postPacket(packet);
            }

            @Override
            public void onDisconnect(Channel ch, Throwable cause) {
                inboundQueue.postDisconnect(cause);
            }
        };
        this.netClient = new NetClient(
            registry, PacketDirection.CLIENTBOUND, PacketDirection.SERVERBOUND, sink);
    }

    /** Connect synchronously; the returned connection is also available via {@link #connection()}. */
    public ClientConnection connect(NetAddress address) throws InterruptedException {
        Channel ch = netClient.connect(address);
        ClientConnection c = new ClientConnection(ch);
        this.connection = c;
        return c;
    }

    public ClientConnection connection() {
        return connection;
    }

    public ClientInboundQueue inboundQueue() {
        return inboundQueue;
    }

    public void shutdown() {
        netClient.close();
    }
}
