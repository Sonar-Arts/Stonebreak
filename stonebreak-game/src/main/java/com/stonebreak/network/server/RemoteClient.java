package com.stonebreak.network.server;

import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.protocol.PacketCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * One server-side connection to a remote client.
 * Owns a reader thread (handlers run on it) and a writer thread (drains outbound queue).
 * Inbound packets are forwarded to the IntegratedServer's onPacket callback,
 * which is responsible for thread safety with the main game.
 */
public final class RemoteClient {

    private final int playerId;
    private volatile String username = "Player";
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    /** Hard cap on queued outbound packets; over this we drop the slow client. */
    private static final int OUTBOUND_CAPACITY = 4096;
    private final LinkedBlockingQueue<Object> outbound = new LinkedBlockingQueue<>(OUTBOUND_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BiConsumer<RemoteClient, Packet> onPacket;
    private final Runnable onDisconnect;
    private final Thread readerThread;
    private final Thread writerThread;

    // Last known position (server-side cache, updated on PlayerStateC2S)
    private volatile float x, y, z, yaw, pitch;
    private volatile long lastStateNs = 0L;
    /** Last reported held block/item id (PlayerHeldItemC2S). 0 = empty/air. */
    private volatile int heldItemId = 0;

    /** Dedicated poison marker. Cannot collide with any wire-deliverable Packet. */
    private static final Object POISON = new Object();

    public RemoteClient(int playerId, Socket socket,
                        BiConsumer<RemoteClient, Packet> onPacket,
                        Runnable onDisconnect) throws IOException {
        this.playerId = playerId;
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.socket.setKeepAlive(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.onPacket = onPacket;
        this.onDisconnect = onDisconnect;

        this.readerThread = new Thread(this::readLoop, "Net-Server-Reader-" + playerId);
        this.writerThread = new Thread(this::writeLoop, "Net-Server-Writer-" + playerId);
        this.readerThread.setDaemon(true);
        this.writerThread.setDaemon(true);
        this.readerThread.start();
        this.writerThread.start();
    }

    public int getPlayerId() { return playerId; }
    public String getUsername() { return username; }
    public void setUsername(String name) { this.username = name; }

    public float getX() { return x; }
    public float getY() { return y; }
    public float getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }

    public void updateState(float x, float y, float z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        this.lastStateNs = System.nanoTime();
    }

    public long getLastStateNs() { return lastStateNs; }
    public int getHeldItemId() { return heldItemId; }
    public void setHeldItemId(int id) { this.heldItemId = id; }

    /**
     * Queue a packet for the writer thread. If the queue is full the client is
     * stuck — disconnect rather than block the caller (block edits, broadcasts,
     * chunk pushes all run on the host's main thread).
     */
    public void send(Packet packet) {
        if (!running.get()) return;
        if (!outbound.offer(packet)) {
            System.err.println("[SERVER] Outbound queue full for client " + playerId
                    + " (cap=" + OUTBOUND_CAPACITY + "); disconnecting.");
            close();
        }
    }

    public boolean isConnected() {
        return running.get() && !socket.isClosed();
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        // Wake the writer thread; offer (not add) so we never block on a full queue.
        outbound.offer(POISON);
        try { socket.close(); } catch (IOException ignored) {}
        if (onDisconnect != null) onDisconnect.run();
    }

    private void readLoop() {
        try {
            while (running.get()) {
                Packet p = PacketCodec.read(in);
                onPacket.accept(this, p);
            }
        } catch (IOException e) {
            if (running.get()) {
                System.out.println("[SERVER] Client " + playerId + " disconnected: " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void writeLoop() {
        try {
            while (running.get()) {
                Object item = outbound.take();
                if (item == POISON) break;
                PacketCodec.write(out, (Packet) item);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (running.get()) {
                System.err.println("[SERVER] Write error to client " + playerId + ": " + e.getMessage());
            }
        } finally {
            close();
        }
    }
}
