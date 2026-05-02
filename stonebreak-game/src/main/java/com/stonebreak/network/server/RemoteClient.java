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
    private final LinkedBlockingQueue<Packet> outbound = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BiConsumer<RemoteClient, Packet> onPacket;
    private final Runnable onDisconnect;
    private final Thread readerThread;
    private final Thread writerThread;

    // Last known position (server-side cache, updated on PlayerStateC2S)
    private volatile float x, y, z, yaw, pitch;

    private static final Packet POISON = new Packet.DisconnectC2S("__poison__");

    public RemoteClient(int playerId, Socket socket,
                        BiConsumer<RemoteClient, Packet> onPacket,
                        Runnable onDisconnect) throws IOException {
        this.playerId = playerId;
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
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
    }

    public void send(Packet packet) {
        if (running.get()) {
            outbound.add(packet);
        }
    }

    public boolean isConnected() {
        return running.get() && !socket.isClosed();
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        outbound.add(POISON);
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
                Packet p = outbound.take();
                if (p == POISON) break;
                PacketCodec.write(out, p);
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
