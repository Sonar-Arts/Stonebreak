package com.stonebreak.network.server;

import com.stonebreak.network.client.NetworkEventBus;
import com.stonebreak.network.protocol.Packet;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integrated TCP server. Lives inside the host's JVM.
 *
 * Inbound packets from clients are deposited into {@link #inboundEvents}, which
 * is drained by the host's main game thread each tick. Outbound broadcasts can
 * be issued from any thread (writes are queued per-client).
 */
public final class IntegratedServer {

    private final int port;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);
    private final Map<Integer, RemoteClient> clients = new ConcurrentHashMap<>();

    /** Inbound events from all clients; main thread drains. */
    private final NetworkEventBus inboundEvents = new NetworkEventBus();

    /** Map from packet -> originating client id, populated on post (best-effort). */
    private final Map<Packet, Integer> packetOrigin = new ConcurrentHashMap<>();

    public IntegratedServer(int port) throws IOException {
        this.port = port;
        this.serverSocket = new ServerSocket(port);
        this.acceptThread = new Thread(this::acceptLoop, "Net-Server-Accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        System.out.println("[SERVER] Listening on TCP port " + port);
    }

    public int getPort() { return port; }

    public NetworkEventBus getInboundEvents() { return inboundEvents; }

    public Integer getOrigin(Packet p) { return packetOrigin.remove(p); }

    public Map<Integer, RemoteClient> getClients() { return clients; }

    /** Send a packet to every connected client. */
    public void broadcast(Packet packet) {
        for (RemoteClient c : clients.values()) {
            c.send(packet);
        }
    }

    /** Send a packet to all clients except the given one. */
    public void broadcastExcept(int excludePlayerId, Packet packet) {
        for (RemoteClient c : clients.values()) {
            if (c.getPlayerId() != excludePlayerId) {
                c.send(packet);
            }
        }
    }

    public RemoteClient getClient(int playerId) {
        return clients.get(playerId);
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        try { serverSocket.close(); } catch (IOException ignored) {}
        for (RemoteClient c : clients.values()) {
            c.close();
        }
        clients.clear();
        System.out.println("[SERVER] Shut down.");
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket sock = serverSocket.accept();
                int id = nextPlayerId.getAndIncrement();
                RemoteClient[] holder = new RemoteClient[1];
                RemoteClient client = new RemoteClient(
                        id, sock,
                        (rc, pkt) -> {
                            packetOrigin.put(pkt, rc.getPlayerId());
                            inboundEvents.post(pkt);
                        },
                        () -> {
                            RemoteClient removed = clients.remove(id);
                            if (removed != null) {
                                broadcast(new Packet.PlayerLeaveS2C(id));
                                System.out.println("[SERVER] Player " + id + " (" + removed.getUsername() + ") left.");
                            }
                        });
                holder[0] = client;
                clients.put(id, client);
                System.out.println("[SERVER] Client connected, assigned id " + id);
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[SERVER] Accept error: " + e.getMessage());
                }
            }
        }
    }
}
