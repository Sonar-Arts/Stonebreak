package com.stonebreak.world.generation.diffusion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin async HTTP client for terrain-bridge's {@code POST /generate_heightmap}
 * and {@code POST /prefetch} (contract: terrain-bridge/README.md, verified
 * against terrain-bridge/bridge/main.py). Retries transient failures with
 * exponential backoff; once retries are exhausted the returned future
 * completes exceptionally with {@link TerrainBridgeException} — callers must
 * not substitute a fallback (plan.md Phase 2).
 */
public class DiffusionTerrainClient {

    private static final Logger LOG = Logger.getLogger(DiffusionTerrainClient.class.getName());

    private final DiffusionBridgeConfig config;
    private final long seed;
    private final HttpClient httpClient;
    private final ScheduledExecutorService retryScheduler;

    public DiffusionTerrainClient(DiffusionBridgeConfig config, long seed) {
        this.config = config;
        this.seed = seed;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                // The bridge (uvicorn) only speaks HTTP/1.1. Left at the default HTTP/2-preferred
                // negotiation, HttpClient's h2c upgrade attempt against a plaintext HTTP/1.1-only
                // server silently drops the POST body — uvicorn sees an empty body and FastAPI
                // rejects it with 422 "Field required" even though the request "succeeds". Confirmed
                // by reproducing against the live bridge; forcing HTTP/1.1 fixes it.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "diffusion-terrain-retry");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<TerrainTile> fetchTile(int worldX, int worldZ) {
        CompletableFuture<TerrainTile> result = new CompletableFuture<>();
        attemptFetch(worldX, worldZ, 0, result);
        return result;
    }

    /** Fire-and-forget warm; failures are logged, never thrown — this is an optimization hint. */
    public void prefetch(int worldX, int worldZ) {
        HttpRequest request = requestBuilder("/prefetch")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody(worldX, worldZ), StandardCharsets.UTF_8))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        LOG.log(Level.FINE, "prefetch(" + worldX + "," + worldZ + ") failed (non-fatal)", err);
                    } else if (resp.statusCode() != 200) {
                        LOG.fine("prefetch(" + worldX + "," + worldZ + ") returned " + resp.statusCode());
                    }
                });
    }

    private void attemptFetch(int worldX, int worldZ, int attemptNumber, CompletableFuture<TerrainTile> result) {
        HttpRequest request = requestBuilder("/generate_heightmap")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody(worldX, worldZ), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, err) -> {
                    if (err == null) {
                        try {
                            if (response.statusCode() == 200) {
                                result.complete(parseTile(response));
                                return;
                            }
                            if (!isRetryableStatus(response.statusCode())) {
                                result.completeExceptionally(new TerrainBridgeException(
                                        "terrain bridge returned " + response.statusCode() + " for (" +
                                        worldX + "," + worldZ + "): " + bodyPreview(response)));
                                return;
                            }
                        } catch (RuntimeException parseError) {
                            result.completeExceptionally(new TerrainBridgeException(
                                    "malformed tile response for (" + worldX + "," + worldZ + ")", parseError));
                            return;
                        }
                    }

                    if (attemptNumber >= config.maxRetries()) {
                        String detail = err != null ? err.toString() : "HTTP " + response.statusCode();
                        result.completeExceptionally(new TerrainBridgeException(
                                "terrain bridge unreachable for (" + worldX + "," + worldZ + ") after " +
                                (attemptNumber + 1) + " attempt(s): " + detail, err));
                        return;
                    }

                    long backoffMs = Math.min(
                            config.initialBackoffMs() * (1L << attemptNumber),
                            config.maxBackoffMs());
                    retryScheduler.schedule(
                            () -> attemptFetch(worldX, worldZ, attemptNumber + 1, result),
                            backoffMs, TimeUnit.MILLISECONDS);
                });
    }

    private static boolean isRetryableStatus(int statusCode) {
        // 400 (seed mismatch) is a config error, not transient — retrying won't help.
        // 5xx (including the bridge's 502 for an unreachable upstream) is worth retrying.
        return statusCode >= 500;
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .header("Content-Type", "application/json");
    }

    private String jsonBody(int worldX, int worldZ) {
        return "{\"world_x\":" + worldX + ",\"world_z\":" + worldZ + ",\"seed\":" + seed + "}";
    }

    private static String bodyPreview(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        if (body == null || body.length == 0) return "";
        int len = Math.min(body.length, 200);
        return new String(body, 0, len, StandardCharsets.UTF_8);
    }

    private TerrainTile parseTile(HttpResponse<byte[]> response) {
        int height = requireHeader(response, "X-Height");
        int width = requireHeader(response, "X-Width");
        int tileX = requireHeader(response, "X-Tile-X");
        int tileZ = requireHeader(response, "X-Tile-Z");
        int i1 = requireHeader(response, "X-World-I1");
        int j1 = requireHeader(response, "X-World-J1");
        int i2 = requireHeader(response, "X-World-I2");
        int j2 = requireHeader(response, "X-World-J2");

        byte[] body = response.body();
        int cells = height * width;
        long expected = (long) cells * 2L * 2L; // block-height int16 + biome int16
        if (body.length != expected) {
            throw new IllegalStateException("unexpected payload size for " + height + "x" + width +
                    ": got " + body.length + ", expected " + expected);
        }

        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        short[] blockHeights = new short[cells];
        for (int i = 0; i < cells; i++) {
            blockHeights[i] = buf.getShort();
        }
        short[] biomeIds = new short[cells];
        for (int i = 0; i < cells; i++) {
            biomeIds[i] = buf.getShort();
        }

        return new TerrainTile(tileX, tileZ, i1, j1, i2, j2, width, height, blockHeights, biomeIds);
    }

    private static int requireHeader(HttpResponse<byte[]> response, String name) {
        return response.headers().firstValue(name)
                .map(Integer::parseInt)
                .orElseThrow(() -> new IllegalStateException("missing response header " + name));
    }

    public void close() {
        retryScheduler.shutdownNow();
        httpClient.close();
    }
}
