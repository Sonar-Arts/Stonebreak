package com.stonebreak.world.generation.diffusion;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DiffusionTerrainClient} against a real local HTTP server
 * (JDK's built-in {@link HttpServer}, no live bridge or network dependency)
 * for the exact wire contract documented in terrain-bridge/bridge/main.py:
 * binary body layout, header-driven tile bounds, and retry/no-retry status
 * handling.
 */
class DiffusionTerrainClientTest {

    /** Must track DiffusionTerrainClient's own constant, and terrain-bridge's. */
    private static final int PROTOCOL_VERSION = 2;

    private HttpServer server;
    private DiffusionTerrainClient client;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test
    void parsesTileFromCannedResponse() throws IOException {
        // row0 (x=10): z=20,21 -> 10,20   row1 (x=11): z=20,21 -> 30,40
        server = startServer(exchange -> respondTile(exchange, 200, 2, 2, 0, 0, 10, 20, 12, 22,
                new short[]{10, 20, 30, 40}, new short[]{1, 2, 3, 4}));

        client = newClient(3);
        TerrainTile tile = client.fetchTile(10, 20).join();

        assertEquals(10, tile.heightAt(10, 20));
        assertEquals(20, tile.heightAt(10, 21));
        assertEquals(30, tile.heightAt(11, 20));
        assertEquals(40, tile.heightAt(11, 21));
        assertEquals(4, tile.biomeIdAt(11, 21));
    }

    @Test
    void parsesTheWaterPlaneAsTheThirdPlane() throws IOException {
        // Distinct values in all three planes: a slice-offset bug reads the biome plane
        // as water, and a plane count of two reads half a plane of each.
        server = startServer(exchange -> respondTile(exchange, 200, 2, 2, 0, 0, 10, 20, 12, 22,
                new short[]{10, 20, 30, 40}, new short[]{1, 2, 3, 4},
                new short[]{320, -1, 460, -1}, PROTOCOL_VERSION));

        client = newClient(3);
        TerrainTile tile = client.fetchTile(10, 20).join();

        assertEquals(320, tile.waterLevelAt(10, 20));
        assertEquals(460, tile.waterLevelAt(11, 20));
        // The "no water" sentinel has to survive as a negative short, not as 65535.
        assertEquals(TerrainTile.NO_WATER, tile.waterLevelAt(10, 21));
    }

    @Test
    void rejectsABridgeSpeakingAnOlderProtocol() throws IOException {
        // The body is bare concatenated planes with no header bytes of its own, so a v1
        // bridge hands back something this build would slice into plausible garbage
        // terrain rather than fail on. The version header is the only thing that is loud.
        server = startServer(exchange -> respondTile(exchange, 200, 2, 2, 0, 0, 0, 0, 2, 2,
                new short[]{1, 2, 3, 4}, new short[]{0, 0, 0, 0},
                new short[]{-1, -1, -1, -1}, 1));

        client = newClient(0);
        CompletionException ex = assertThrows(CompletionException.class,
                () -> client.fetchTile(0, 0).join());
        assertInstanceOf(TerrainBridgeException.class, ex.getCause());
        assertTrue(ex.getCause().getCause().getMessage().contains("protocol v1"),
                "unexpected message: " + ex.getCause().getCause().getMessage());
    }

    @Test
    void rejectsAResponseWithNoProtocolHeaderAtAll() throws IOException {
        // Absent means "a bridge from before the header existed", which is by definition
        // a version this build cannot read — not a reason to guess.
        server = startServer(exchange -> respondTile(exchange, 200, 2, 2, 0, 0, 0, 0, 2, 2,
                new short[]{1, 2, 3, 4}, new short[]{0, 0, 0, 0},
                new short[]{-1, -1, -1, -1}, null));

        client = newClient(0);
        CompletionException ex = assertThrows(CompletionException.class,
                () -> client.fetchTile(0, 0).join());
        assertInstanceOf(TerrainBridgeException.class, ex.getCause());
    }

    @Test
    void retriesOn5xxThenSucceeds() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        server = startServer(exchange -> {
            if (attempts.incrementAndGet() < 3) {
                respondError(exchange, 502);
                return;
            }
            respondTile(exchange, 200, 1, 1, 0, 0, 0, 0, 1, 1, new short[]{5}, new short[]{1});
        });

        client = newClient(3);
        TerrainTile tile = client.fetchTile(0, 0).join();

        assertEquals(5, tile.heightAt(0, 0));
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetrySeedMismatch400() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        server = startServer(exchange -> {
            attempts.incrementAndGet();
            respondError(exchange, 400);
        });

        client = newClient(3);
        CompletionException ex = assertThrows(CompletionException.class, () -> client.fetchTile(0, 0).join());
        // Reported as the seed-specific subtype: the terrain mapper keys on it to tell a pass that
        // outlived its seed (normal hand-off) from a bridge that is actually broken. Chunk
        // generation still sees a TerrainBridgeException and still fails loudly.
        assertInstanceOf(StaleSeedException.class, ex.getCause());
        assertEquals(1, attempts.get());
    }

    @Test
    void exhaustsRetriesAndFailsLoudly() throws IOException {
        server = startServer(exchange -> respondError(exchange, 503));

        client = newClient(2);
        CompletionException ex = assertThrows(CompletionException.class, () -> client.fetchTile(0, 0).join());
        assertInstanceOf(TerrainBridgeException.class, ex.getCause());
    }

    @Test
    void pollsThroughASolvingResponseInsteadOfExhaustingTheRetryLadder() throws Exception {
        // The cold-start scenario (Rivers and lakes plan.md section 19): a cold hydrology solve
        // can run for minutes, far longer than the fast maxRetries ladder was ever meant to
        // cover. maxRetries is 0 here -- surviving repeated 503s must come from the dedicated
        // solving budget, not from the normal retry count.
        AtomicInteger attempts = new AtomicInteger();
        server = startServer(exchange -> {
            if (attempts.incrementAndGet() < 3) {
                respondSolving(exchange, 1); // 1s is the smallest whole-second Retry-After
                return;
            }
            respondTile(exchange, 200, 1, 1, 0, 0, 0, 0, 1, 1, new short[]{9}, new short[]{1});
        });

        client = newClient(server.getAddress().getPort(), 0, 5_000L, 5_000L, 50L);
        TerrainTile tile = client.fetchTile(0, 0).join();

        assertEquals(9, tile.heightAt(0, 0));
        assertEquals(3, attempts.get());
    }

    @Test
    void failsOnceTheSolvingGraceExpires() throws IOException {
        // Never finishes: the wait is bounded even for a bridge that keeps answering "still
        // working on it," so a pathological solve can never hang a chunk worker forever.
        server = startServer(exchange -> respondSolving(exchange, 1));

        client = newClient(server.getAddress().getPort(), 0, 5_000L, 150L, 20L);
        CompletionException ex = assertThrows(CompletionException.class, () -> client.fetchTile(0, 0).join());
        TerrainBridgeException failure = assertInstanceOf(TerrainBridgeException.class, ex.getCause());
        assertTrue(failure.getMessage().contains("still solving hydrology"),
                "unexpected message: " + failure.getMessage());
    }

    @Test
    void a503WithNoRetryAfterStillUsesTheOrdinaryFastLadder() throws IOException {
        // exhaustsRetriesAndFailsLoudly below covers this too, but states the reason
        // explicitly: without Retry-After this must NOT be mistaken for "solving" and parked
        // on the multi-minute grace budget -- it has to fail fast, on maxRetries, like any
        // other unrecognized 5xx.
        AtomicInteger attempts = new AtomicInteger();
        server = startServer(exchange -> {
            attempts.incrementAndGet();
            respondError(exchange, 503);
        });

        client = newClient(server.getAddress().getPort(), 2, 5_000L, 60_000L, 50L);
        assertThrows(CompletionException.class, () -> client.fetchTile(0, 0).join());
        assertEquals(3, attempts.get()); // maxRetries=2 -> 3 attempts total, not parked for 60s
    }

    @Test
    void waitsOutServiceRestartInsteadOfFailing() throws Exception {
        // The real scenario: TerrainServiceProcessManager stopped both processes to re-pin a seed,
        // so the port refuses connections for a few seconds. maxRetries is 0 here — surviving this
        // must come from the unreachable grace budget, not from the retry ladder.
        int port = closedPort();
        client = newClient(port, 0, 5_000L);

        CompletableFuture<TerrainTile> tile = client.fetchTile(7, 9);
        Thread.sleep(200);
        assertFalse(tile.isDone(), "must still be waiting while the bridge is down");

        server = startServerOnPort(port, exchange ->
                respondTile(exchange, 200, 1, 1, 0, 0, 7, 9, 8, 10, new short[]{77}, new short[]{3}));

        assertEquals(77, tile.get(10, TimeUnit.SECONDS).heightAt(7, 9));
    }

    @Test
    void failsOnceUnreachableGraceExpires() throws IOException {
        // Nothing ever comes up: the wait is bounded, so a fetch can never hang a chunk worker
        // forever, and the message says what to check.
        client = newClient(closedPort(), 0, 250L);

        CompletionException ex = assertThrows(CompletionException.class, () -> client.fetchTile(0, 0).join());
        TerrainBridgeException failure = assertInstanceOf(TerrainBridgeException.class, ex.getCause());
        assertTrue(failure.getMessage().contains("connect attempts over"),
                "unexpected message: " + failure.getMessage());
    }

    private DiffusionTerrainClient newClient(int maxRetries) {
        return newClient(server.getAddress().getPort(), maxRetries, 5_000L);
    }

    private static DiffusionTerrainClient newClient(int port, int maxRetries, long unreachableGraceMs) {
        return newClient(port, maxRetries, unreachableGraceMs, 5_000L, 50L);
    }

    private static DiffusionTerrainClient newClient(int port, int maxRetries, long unreachableGraceMs,
                                                      long hydrologySolveGraceMs, long solvePollIntervalMs) {
        DiffusionBridgeConfig config = new DiffusionBridgeConfig(
                "http://localhost:" + port,
                256, 2000, 5000, maxRetries, 10, 50, 64, unreachableGraceMs,
                hydrologySolveGraceMs, solvePollIntervalMs);
        return new DiffusionTerrainClient(config, 42L);
    }

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        return startServerOnPort(0, handler);
    }

    private static HttpServer startServerOnPort(int port, HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        s.createContext("/", handler);
        s.start();
        return s;
    }

    /** A port nothing is listening on: bound to learn a free one, then released. */
    private static int closedPort() throws IOException {
        HttpServer probe = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = probe.getAddress().getPort();
        probe.stop(0);
        return port;
    }

    /** The bridge's "still generating this tile" response: 503 + Retry-After (whole seconds). */
    private static void respondSolving(com.sun.net.httpserver.HttpExchange exchange, int retryAfterSeconds) {
        try {
            byte[] body = "tile is still being generated, retry shortly".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void respondError(com.sun.net.httpserver.HttpExchange exchange, int status) {
        try {
            byte[] body = "error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void respondTile(com.sun.net.httpserver.HttpExchange exchange, int status,
                                     int width, int height, int tileX, int tileZ,
                                     int i1, int j1, int i2, int j2,
                                     short[] blockHeights, short[] biomeIds) {
        short[] water = new short[blockHeights.length];
        java.util.Arrays.fill(water, TerrainTile.NO_WATER);
        respondTile(exchange, status, width, height, tileX, tileZ, i1, j1, i2, j2,
                blockHeights, biomeIds, water, PROTOCOL_VERSION);
    }

    private static void respondTile(com.sun.net.httpserver.HttpExchange exchange, int status,
                                     int width, int height, int tileX, int tileZ,
                                     int i1, int j1, int i2, int j2,
                                     short[] blockHeights, short[] biomeIds, short[] waterLevels,
                                     Integer protocolVersion) {
        try {
            ByteBuffer buf = ByteBuffer
                    .allocate((blockHeights.length + biomeIds.length + waterLevels.length) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (short v : blockHeights) buf.putShort(v);
            for (short v : biomeIds) buf.putShort(v);
            for (short v : waterLevels) buf.putShort(v);
            byte[] payload = buf.array();

            if (protocolVersion != null) {
                exchange.getResponseHeaders().add("X-Protocol-Version", protocolVersion.toString());
            }
            exchange.getResponseHeaders().add("X-Height", String.valueOf(height));
            exchange.getResponseHeaders().add("X-Width", String.valueOf(width));
            exchange.getResponseHeaders().add("X-Tile-X", String.valueOf(tileX));
            exchange.getResponseHeaders().add("X-Tile-Z", String.valueOf(tileZ));
            exchange.getResponseHeaders().add("X-World-I1", String.valueOf(i1));
            exchange.getResponseHeaders().add("X-World-J1", String.valueOf(j1));
            exchange.getResponseHeaders().add("X-World-I2", String.valueOf(i2));
            exchange.getResponseHeaders().add("X-World-J2", String.valueOf(j2));
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
