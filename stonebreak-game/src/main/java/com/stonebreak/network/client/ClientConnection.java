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
    private final LinkedBlockingQueue<Packet> outbound = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread readerThread;
    private final Thread writerThread;

    private static final Packet POISON = new Packet.DisconnectC2S("__poison__");

    public ClientConnection(String host, int port, NetworkEventBus eventBus) throws IOException {
        this.eventBus = eventBus;
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port), 5000);
        this.socket.setTcpNoDelay(true);
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
                Packet p = outbound.take();
                if (p == POISON) break;
                PacketCodec.write(out, p);
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
