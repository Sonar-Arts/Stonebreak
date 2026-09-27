package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.TerrainTile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One session with a TGMPipe process process over its pipes: every tile request in the game,
 * multiplexed by request id and answered as tiles finish (no polling). Knows nothing about
 * processes — {@link TGMPipe} owns the child and decides what a closed connection means.
 *
 * <p>A request completed from outside (its cache closed, say) while still in flight is withdrawn
 * with a CANCEL, so the service drops it if the GPU has not started it yet.
 */
final class TGMPipeConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TGMPipeConnection.class.getName());

    /** A tile someone is waiting on; survives a service restart (it is re-sent on the new connection). */
    record TileRequest(long seed, int tileX, int tileZ, int lod, int priority,
                       CompletableFuture<TerrainTile> future) {
        @Override
        public String toString() {
            return "tile (" + tileX + "," + tileZ + ") lod " + lod + " seed " + seed;
        }
    }

    interface Listener {
        /** The stream ended or broke; {@code unfinished} were in flight and are not completed. */
        void closed(TGMPipeConnection connection, String reason, List<TileRequest> unfinished);
    }

    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Integer, TileRequest> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> statusReplies = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final CompletableFuture<String> ready = new CompletableFuture<>();
    private final Thread reader;
    private volatile boolean closed;
    private volatile boolean closedByClient;
    private volatile String fatal;
    private volatile long lastActivityNanos = System.nanoTime();

    TGMPipeConnection(InputStream in, OutputStream out, Listener listener) {
        this.in = in;
        this.out = out;
        this.listener = listener;
        this.reader = new Thread(this::readLoop, "tgmpipe-reader");
        this.reader.setDaemon(true);
    }

    /** Starts reading and sends the handshake; {@link #ready()} completes with the READY json. */
    void open(String worldConfigJson) {
        reader.start();
        write(TGMPipeProtocol.hello(worldConfigJson));
    }

    CompletableFuture<String> ready() {
        return ready;
    }

    void send(TileRequest request) {
        if (closed) {
            throw new TGMPipeException("TGMPipe connection is closed");
        }
        int id = ids.incrementAndGet();
        if (pending.isEmpty()) {
            lastActivityNanos = System.nanoTime(); // the stall clock starts when work does
        }
        pending.put(id, request);
        if (closed && pending.remove(id, request)) {
            // Lost the race with the reader's final drain: this one was never handed over.
            throw new TGMPipeException("TGMPipe connection is closed");
        }
        write(TGMPipeProtocol.tile(id, request.seed(), request.tileX(), request.tileZ(),
                request.lod(), request.priority()));
        // Registered after the TILE is written, so a CANCEL can never overtake it.
        request.future().whenComplete((tile, err) -> {
            // Only a request completed from OUTSIDE is still pending here: the reader removes
            // before it completes. Withdraw it so the GPU never builds a tile nobody wants.
            if (pending.remove(id, request) && !closed) {
                write(TGMPipeProtocol.cancel(id));
            }
        });
    }

    /** The service's status json (queue depth, cache, timings). */
    CompletableFuture<String> status() {
        int token = ids.incrementAndGet();
        CompletableFuture<String> reply = new CompletableFuture<>();
        statusReplies.put(token, reply);
        write(TGMPipeProtocol.status(token));
        return reply;
    }

    int pendingCount() {
        return pending.size();
    }

    /** When the service last sent a frame, or work last arrived at an idle connection. */
    long lastActivityNanos() {
        return lastActivityNanos;
    }

    boolean isClosed() {
        return closed;
    }

    /** True when {@link #close()} ended it (a shutdown), not the service going away on its own. */
    boolean closedByClient() {
        return closedByClient;
    }

    private void write(byte[] frame) {
        try {
            synchronized (writeLock) {
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            // The reader sees the same broken pipe as end of stream and reports the closure;
            // everything pending, this request included, is handed over there.
            LOG.log(Level.FINE, "TGMPipe write failed", e);
        }
    }

    private void readLoop() {
        String reason = "TGMPipe closed its output";
        try {
            TGMPipeProtocol.Frame frame;
            while ((frame = TGMPipeProtocol.read(in)) != null) {
                lastActivityNanos = System.nanoTime();
                handle(frame);
            }
        } catch (IOException | RuntimeException e) {
            reason = "TGMPipe stream failed: " + e;
        } finally {
            closed = true;
            if (fatal != null) {
                reason = fatal;
            }
            TGMPipeException ended = new TGMPipeException(reason);
            ready.completeExceptionally(ended);
            statusReplies.values().forEach(f -> f.completeExceptionally(ended));
            List<TileRequest> unfinished = new ArrayList<>();
            for (Integer id : List.copyOf(pending.keySet())) {
                TileRequest request = pending.remove(id);
                if (request != null && !request.future().isDone()) {
                    unfinished.add(request);
                }
            }
            listener.closed(this, reason, unfinished);
        }
    }

    private void handle(TGMPipeProtocol.Frame frame) {
        switch (frame.type()) {
            case TGMPipeProtocol.READY -> ready.complete(frame.text());
            case TGMPipeProtocol.TILE_DATA -> {
                TileRequest request = pending.remove(TGMPipeProtocol.requestId(frame));
                if (request == null) {
                    return; // withdrawn after the service had already sent it
                }
                try {
                    request.future().complete(TGMPipeProtocol.decodeTile(frame, request.tileX(), request.tileZ()));
                } catch (RuntimeException e) {
                    request.future().completeExceptionally(
                            new TGMPipeException("malformed " + request + " from TGMPipe", e));
                }
            }
            case TGMPipeProtocol.TILE_ERROR -> {
                TileRequest request = pending.remove(TGMPipeProtocol.requestId(frame));
                if (request != null) {
                    request.future().completeExceptionally(new TGMPipeException(
                            "TGMPipe failed " + request + ": " + TGMPipeProtocol.textAfterId(frame)));
                }
            }
            case TGMPipeProtocol.STATUS_REPLY -> {
                CompletableFuture<String> reply = statusReplies.remove(TGMPipeProtocol.requestId(frame));
                if (reply != null) {
                    reply.complete(TGMPipeProtocol.textAfterId(frame));
                }
            }
            case TGMPipeProtocol.FATAL -> fatal = "TGMPipe failed: " + frame.text();
            default -> throw new IllegalStateException(
                    "unknown TGMPipe frame 0x" + Integer.toHexString(frame.type()));
        }
    }

    /** Closes the service's stdin, which is the service's signal to exit. */
    @Override
    public void close() {
        closedByClient = true;
        closed = true;
        try {
            out.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
