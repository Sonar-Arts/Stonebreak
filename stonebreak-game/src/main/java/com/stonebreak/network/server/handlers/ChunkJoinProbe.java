package com.stonebreak.network.server.handlers;

import com.stonebreak.network.server.ServerPlayer;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Measures each player's join (issue #345): from the first tick the server can stream to them
 * until their view is first complete — chunks sent, elapsed time, and the longest server tick
 * (and longest chunk-streaming step) in between. One {@code [SERVER-CHUNK] Join view complete}
 * line per player session; inert otherwise. Server tick thread only.
 */
final class ChunkJoinProbe {

    private static final class Probe {
        final long startNs;
        int chunks;
        long longestTickNs;
        long longestChunkStepNs;
        int viewDistance;
        boolean complete;

        Probe(long startNs) {
            this.startNs = startNs;
        }
    }

    /** Players being measured; a player is measured once per session (its ServerPlayer). */
    private final Map<ServerPlayer, Probe> active = new IdentityHashMap<>();
    private final Map<ServerPlayer, Boolean> measured = new IdentityHashMap<>();

    /** The player can be streamed to this tick; starts its probe the first time. */
    void streamable(ServerPlayer sp) {
        if (measured.putIfAbsent(sp, Boolean.TRUE) == null) {
            active.put(sp, new Probe(System.nanoTime()));
        }
    }

    void streamed(ServerPlayer sp) {
        Probe p = active.get(sp);
        if (p != null) {
            p.chunks++;
        }
    }

    void viewComplete(ServerPlayer sp) {
        Probe p = active.get(sp);
        if (p != null && !p.complete) {
            p.complete = true;
            p.viewDistance = sp.viewDistanceChunks();
        }
    }

    /**
     * End of a server tick: folds its duration into every running probe and reports the ones
     * whose view completed during it. Players no longer connected are dropped.
     */
    void tickEnded(long tickNs, long chunkStepNs, Collection<ServerPlayer> connected) {
        if (active.isEmpty()) {
            measured.keySet().retainAll(connected);
            return;
        }
        var it = active.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ServerPlayer sp = e.getKey(); // IdentityHashMap entries are dead after remove()
            Probe p = e.getValue();
            if (!connected.contains(sp)) {
                it.remove();
                continue;
            }
            p.longestTickNs = Math.max(p.longestTickNs, tickNs);
            p.longestChunkStepNs = Math.max(p.longestChunkStepNs, chunkStepNs);
            if (p.complete) {
                it.remove();
                System.out.println(String.format(
                    "[SERVER-CHUNK] Join view complete for player %d: view %d, %d chunks in %.0f ms,"
                        + " longest tick %.1f ms (chunk step %.1f ms)",
                    sp.playerId(), p.viewDistance, p.chunks,
                    (System.nanoTime() - p.startNs) / 1e6, p.longestTickNs / 1e6, p.longestChunkStepNs / 1e6));
            }
        }
        measured.keySet().retainAll(connected);
    }
}
