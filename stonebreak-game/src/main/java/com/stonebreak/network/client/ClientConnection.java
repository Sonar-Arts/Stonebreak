package com.stonebreak.network.client;

import com.stonebreak.network.protocol.Packet;
import com.stonebreak.network.protocol.PacketCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single TCP connection from this client to a remote server.
 * Spawns a reader thread (deposits inbound packets in NetworkEventBus)
 * and a writer thread (drains outbound queue).
 */
public final class ClientConnection {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final NetworkEventBus eventBus;
    private static final int OUTBOUND_CAPACITY = 4096;
    private final LinkedBlockingQueue<Object> outbound = new LinkedBlockingQueue<>(OUTBOUND_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread readerThread;
    private final Thread writerThread;

    private static final Object POISON = new Object();

    public ClientConnection(String host, int port, NetworkEventBus eventBus) throws IOException {
        this.eventBus = eventBus;
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), 5000);
        this.socket.setTcpNoDelay(true);
        this.socket.setKeepAlive(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        this.readerThread = new Thread(this::readLoop, "Net-Client-Reader");
        this.writerThread = new Thread(this::writeLoop, "Net-Client-Writer");
        this.readerThread.setDaemon(true);
        this.writerThread.setDaemon(true);
        this.readerThread.start();
        this.writerThread.start();
    }

    public void send(Packet packet) {
        if (!running.get()) return;
        if (!outbound.offer(packet)) {
            System.err.println("[NETWORK] Outbound queue full (cap=" + OUTBOUND_CAPACITY
                    + "); closing connection.");
            close();
        }
    }

    public boolean isConnected() {
        return running.get() && !socket.isClosed();
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        outbound.offer(POISON);
        try { socket.close(); } catch (IOException ignored) {}
    }

    private void readLoop() {
        try {
            while (running.get()) {
                Packet p = PacketCodec.read(in);
                eventBus.post(p);
            }
        } catch (IOException e) {
            if (running.get()) {
                System.err.println("[NETWORK] Client read error: " + e.getMessage());
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
                System.err.println("[NETWORK] Client write error: " + e.getMessage());
            }
        } finally {
            close();
        }
    }
}
