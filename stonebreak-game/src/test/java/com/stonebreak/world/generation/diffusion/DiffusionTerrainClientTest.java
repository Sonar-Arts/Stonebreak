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
        DiffusionBridgeConfig config = new DiffusionBridgeConfig(
                "http://localhost:" + port,
                256, 2000, 5000, maxRetries, 10, 50, 64, unreachableGraceMs);
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
        try {
            ByteBuffer buf = ByteBuffer.allocate((blockHeights.length + biomeIds.length) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (short v : blockHeights) buf.putShort(v);
            for (short v : biomeIds) buf.putShort(v);
            byte[] payload = buf.array();

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
