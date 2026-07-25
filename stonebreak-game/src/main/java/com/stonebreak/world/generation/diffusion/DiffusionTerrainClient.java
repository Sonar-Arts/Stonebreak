package com.stonebreak.world.generation.diffusion;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
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
 *
 * <p>Two failure classes, two policies. A bridge that <em>answers</em> with 5xx gets the
 * {@code maxRetries} exponential ladder (well under two seconds total). A bridge that is not
 * accepting connections at all gets a separate wall-clock grace period instead, because that
 * state normally means {@code TerrainServiceProcessManager} is restarting the pair for a
 * different seed rather than that the bridge is gone — see {@link Deadline}.
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
        attemptFetch(worldX, worldZ, 0, result, new Deadline());
        return result;
    }

    /**
     * Wall-clock budget for "the port is not accepting connections", separate from the
     * {@code maxRetries} ladder. Started lazily on the first connect failure so a fetch that never
     * sees one costs nothing, and restarted per outage episode: a seed switch away and back
     * restarts the services twice in a row (exactly what the log of the reported failure shows),
     * and the second window should not inherit an already-spent budget. Worst case for one fetch
     * is therefore bounded by {@code maxRetries} episodes rather than a single grace period.
     *
     * <p>Exists because a connect failure is overwhelmingly not a dead bridge but a restarting
     * one: {@code TerrainServiceProcessManager} stops both processes whenever the requested seed
     * differs from the pinned one, and the upstream model server takes seconds to reload before it
     * binds again. Failing those requests after the normal sub-two-second ladder produced
     * {@code TerrainBridgeException}s for tiles that would have succeeded a moment later.
     *
     * <p>Unsynchronized on purpose: one fetch's attempts never overlap, and each hop between the
     * threads involved (HTTP completion → retry scheduler → next completion) goes through an
     * executor submission or a {@link CompletableFuture} stage, both of which carry the
     * happens-before edge these fields need.
     */
    private final class Deadline {
        private long expiresAtNanos;
        private boolean waiting;
        private int connectAttempts;

        /** True while connect failures should keep being retried rather than surfaced. */
        boolean tolerateUnreachable(int worldX, int worldZ) {
            long now = System.nanoTime();
            connectAttempts++;
            if (!waiting) {
                waiting = true;
                expiresAtNanos = now + config.unreachableGraceMs() * 1_000_000L;
                // One line per fetch that hits the window, not one per retry: a seed switch can put
                // dozens of in-flight tiles here at once and each would otherwise log every attempt.
                LOG.info(() -> "terrain bridge at " + config.baseUrl() + " is not accepting connections"
                        + " (likely restarting for a new seed); waiting up to "
                        + config.unreachableGraceMs() + "ms for tile (" + worldX + "," + worldZ + ")");
                return true;
            }
            return now < expiresAtNanos;
        }

        /** Notes that the bridge answered again, so the recovery is visible in the log. */
        void recovered(int worldX, int worldZ) {
            if (waiting) {
                waiting = false;
                LOG.info(() -> "terrain bridge reachable again; tile (" + worldX + "," + worldZ + ") resumed");
            }
        }

        boolean waited() {
            return waiting;
        }

        int connectAttempts() {
            return connectAttempts;
        }
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

    private void attemptFetch(int worldX, int worldZ, int attemptNumber,
                              CompletableFuture<TerrainTile> result, Deadline deadline) {
        HttpRequest request = requestBuilder("/generate_heightmap")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody(worldX, worldZ), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, err) -> {
                    if (err == null) {
                        deadline.recovered(worldX, worldZ);
                        try {
                            if (response.statusCode() == 200) {
                                result.complete(parseTile(response));
                                return;
                            }
                            if (!isRetryableStatus(response.statusCode())) {
                                String detail = "terrain bridge returned " + response.statusCode()
                                        + " for (" + worldX + "," + worldZ + "): " + bodyPreview(response);
                                result.completeExceptionally(isSeedMismatch(response.statusCode())
                                        ? new StaleSeedException(detail)
                                        : new TerrainBridgeException(detail));
                                return;
                            }
                        } catch (RuntimeException parseError) {
                            result.completeExceptionally(new TerrainBridgeException(
                                    "malformed tile response for (" + worldX + "," + worldZ + ")", parseError));
                            return;
                        }
                    }

                    // "Nothing listening on the port" gets its own patient budget and does NOT
                    // consume the retry ladder — see Deadline. Keeps a service restart from
                    // failing every tile that happens to be in flight.
                    boolean unreachable = isConnectFailure(err);
                    if (unreachable && deadline.tolerateUnreachable(worldX, worldZ)) {
                        retryScheduler.schedule(
                                () -> attemptFetch(worldX, worldZ, attemptNumber, result, deadline),
                                config.maxBackoffMs(), TimeUnit.MILLISECONDS);
                        return;
                    }

                    if (unreachable || attemptNumber >= config.maxRetries()) {
                        String detail = err != null ? err.toString() : "HTTP " + response.statusCode();
                        // The connect path retries on its own budget, so attemptNumber alone would
                        // report "1 attempt" for a fetch that spent a minute knocking on the port.
                        String waited = unreachable && deadline.waited()
                                ? " (" + deadline.connectAttempts() + " connect attempts over "
                                  + config.unreachableGraceMs() + "ms; is the terrain service"
                                  + " running? see Dev Working/terrain-diffusion-spike/logs)"
                                : "";
                        result.completeExceptionally(new TerrainBridgeException(
                                "terrain bridge unreachable for (" + worldX + "," + worldZ + ") after " +
                                (attemptNumber + 1) + " attempt(s): " + detail + waited, err));
                        return;
                    }

                    long backoffMs = Math.min(
                            config.initialBackoffMs() * (1L << attemptNumber),
                            config.maxBackoffMs());
                    retryScheduler.schedule(
                            () -> attemptFetch(worldX, worldZ, attemptNumber + 1, result, deadline),
                            backoffMs, TimeUnit.MILLISECONDS);
                });
    }

    /**
     * True when the failure is "could not open a connection" rather than a bridge that answered.
     * {@code HttpClient} reports this as a {@link ConnectException} — which on Linux commonly wraps
     * a {@link java.nio.channels.ClosedChannelException} with no message at all, so the cause chain
     * is walked rather than the top-level type inspected — or, when the TCP handshake itself hangs,
     * an {@link HttpConnectTimeoutException}.
     */
    private static boolean isConnectFailure(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static boolean isRetryableStatus(int statusCode) {
        // 400 (seed mismatch) is a state error, not transient — retrying won't help.
        // 5xx (including the bridge's 502 for an unreachable upstream) is worth retrying.
        return statusCode >= 500;
    }

    /**
     * True for the bridge's seed-pinning rejection. Keyed on the bare status code because
     * {@code _require_matching_seed} is the ONLY producer of 400 in
     * terrain-bridge/bridge/main.py — its other failures are 502 (upstream unreachable) and
     * FastAPI's own 422 (malformed body). Reading the {@code detail} text instead would couple
     * this to an English sentence for no extra certainty.
     */
    private static boolean isSeedMismatch(int statusCode) {
        return statusCode == 400;
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
