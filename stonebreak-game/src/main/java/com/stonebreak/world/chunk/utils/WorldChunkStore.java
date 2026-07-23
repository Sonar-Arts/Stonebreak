package com.stonebreak.world.chunk.utils;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.openmason.engine.diagnostics.MemoryProfiler;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.ChunkStatus;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.generation.ColumnProfile;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.features.FeatureQueue;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages chunk storage and lifecycle with CCO-integrated serialization.
 * Implements Minecraft-style multi-stage chunk generation to prevent feature "pop-in".
 * Follows SOLID, KISS, YAGNI, and DRY principles.
 */
public class WorldChunkStore {
    private final TerrainGenerationSystem terrainSystem;
    private final WorldConfiguration config;
    private final MmsMeshPipeline meshPipeline;
    private final Map<ChunkPosition, Chunk> chunks;
    private final PositionCache positionCache;
    private final com.stonebreak.world.World world;
    private final FeatureQueue featureQueue;

    // Deferred feature population queue to break recursive generation cycles
    private final Queue<ChunkPosition> pendingFeaturePopulation = new ConcurrentLinkedQueue<>();

    // Column profiles (heights + biomes) computed during terrain generation,
    // consumed by deferred feature population so the noise stack isn't
    // resampled. Entries are removed when consumed, when the position is
    // permanently dequeued, or when the chunk unloads.
    private final Map<ChunkPosition, ColumnProfile> pendingColumnProfiles = new ConcurrentHashMap<>();

    // Async chunk loading support - tracks chunks being loaded to prevent duplicate requests
    private final Map<ChunkPosition, CompletableFuture<Chunk>> pendingChunkLoads = new ConcurrentHashMap<>();

    // Terrain generation worker pool. Generation used to ride the disk-load future's
    // continuation onto SaveService's single io thread, serializing every chunk's noise
    // sampling and carving behind disk traffic. The generation stack is stateless per
    // chunk (audited), so disk misses hop here and generate in parallel; disk I/O keeps
    // its own single ordered thread.
    private final ExecutorService generationExecutor;

    private Consumer<Chunk> loadListener;
    private Consumer<Chunk> unloadListener;

    // Client render-view worlds generate NO terrain — every chunk arrives from the server
    // (installNetworkChunk). When disabled, generate() yields an empty all-air chunk that the
    // network layer fills in. See the two-world separation plan, decision #1 (server streams
    // all chunks). Default true: the authoritative/singleplayer world generates terrain.
    private volatile boolean terrainGenerationEnabled = true;

    public WorldChunkStore(TerrainGenerationSystem terrainSystem,
                          WorldConfiguration config,
                          MmsMeshPipeline meshPipeline,
                          com.stonebreak.world.World world,
                          FeatureQueue featureQueue) {
        this.terrainSystem = terrainSystem;
        this.config = config;
        this.meshPipeline = meshPipeline;
        this.world = world;
        this.featureQueue = featureQueue;

        // OPTIMIZATION: Pre-size ConcurrentHashMap to avoid resizing overhead
        // Calculate expected capacity: (renderDistance * 2 + 3)² chunks
        // +3 accounts for border chunks (renderDistance + 1 on each side + center)
        // Multiply by 2 for HashMap load factor (default 0.75, use 0.5 for safety)
        int renderDist = config.getRenderDistance();
        int expectedChunks = (renderDist * 2 + 3) * (renderDist * 2 + 3);
        int initialCapacity = expectedChunks * 2; // Account for load factor
        this.chunks = new ConcurrentHashMap<>(initialCapacity);

        this.positionCache = new PositionCache();

        // Same sizing as the mesh pipeline's pool: max(1, cores/2). Daemon threads —
        // mostly parked on the render-only client world (empty placeholders only).
        AtomicInteger threadCounter = new AtomicInteger(1);
        this.generationExecutor = Executors.newFixedThreadPool(
            config.getChunkBuildThreads(),
            r -> {
                Thread t = new Thread(r, "ChunkGeneration-" + threadCounter.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
    }

    // ========== Public API ==========

    public void setChunkListeners(Consumer<Chunk> loadListener, Consumer<Chunk> unloadListener) {
        this.loadListener = loadListener;
        this.unloadListener = unloadListener;
    }

    /**
     * Enable/disable local terrain generation. A client render-view world disables it so
     * {@link #generate} returns empty chunks waiting to be filled by streamed network data
     * instead of running {@code TerrainGenerationSystem}. Authoritative/singleplayer worlds
     * leave it enabled.
     */
    public void setTerrainGenerationEnabled(boolean enabled) {
        this.terrainGenerationEnabled = enabled;
    }

    public boolean isTerrainGenerationEnabled() {
        return terrainGenerationEnabled;
    }

    public Chunk getChunk(int x, int z) {
        return chunks.get(positionCache.get(x, z));
    }

    /**
     * Synchronously create (or return existing) chunk slot for a streamed network chunk.
     *
     * <p>Render-only clients ({@code terrainGenerationEnabled = false}) deliberately bypass
     * the async {@link #getOrCreateChunk} path here: the async machinery is meant for disk
     * load + terrain generation, neither of which a render-only client does — and its
     * sync-completion path is racy (the inline {@code .thenApply} puts the chunk into the
     * map BEFORE the outer call returns, so {@code getOrCreateChunk} returns {@code null}
     * even when the chunk is already resident). For a freshly-arriving network chunk we
     * just want an empty resident slot, NOW, with no futures.
     *
     * <p>Fires the load listener exactly once on first creation (mesh-pipeline marks
     * neighbor borders dirty so they re-mesh against the new chunk).
     */
    public Chunk createOrGetNetworkChunkSlot(int x, int z) {
        ChunkPosition pos = positionCache.get(x, z);
        Chunk existing = chunks.get(pos);
        if (existing != null) {
            return existing;
        }
        Chunk chunk = generateEmptyChunk(x, z);
        Chunk prior = chunks.putIfAbsent(pos, chunk);
        if (prior != null) {
            // Lost a race against another thread that just installed the same chunk; use theirs.
            return prior;
        }
        if (loadListener != null) {
            notify(loadListener, chunk);
        }
        return chunk;
    }

    public Chunk getOrCreateChunk(int x, int z) {
        ChunkPosition pos = positionCache.get(x, z);

        // Fast path: chunk already loaded
        Chunk chunk = chunks.get(pos);
        if (chunk != null) {
            return chunk;
        }

        // Check if chunk is currently being loaded
        CompletableFuture<Chunk> pendingLoad = pendingChunkLoads.get(pos);
        if (pendingLoad != null) {
            // Chunk is loading asynchronously
            // Check if completed
            if (pendingLoad.isDone()) {
                if (pendingLoad.isCompletedExceptionally()) {
                    // Load failed with exception - propagate it immediately to crash the game
                    try {
                        pendingLoad.getNow(null); // This will throw the exception
                    } catch (Exception e) {
                        // Re-throw to crash the game with full stack trace
                        throw new RuntimeException("Chunk load failed for (" + x + ", " + z + ")", e);
                    }
                }
                // Normal completion - get the chunk
                chunk = pendingLoad.getNow(null);
                if (chunk != null) {
                    // Load completed, finalize chunk
                    finalizeChunkLoad(pos, chunk);
                    return chunk;
                }
            }
            // Still loading, return null (caller will retry next frame)
            return null;
        }

        // Start async chunk load
        CompletableFuture<Chunk> loadFuture = loadOrGenerateAsync(x, z)
            .thenApply(loadedChunk -> {
                if (loadedChunk != null) {
                    // Store chunk and finalize on completion
                    chunks.put(pos, loadedChunk);
                    finalizeChunkLoad(pos, loadedChunk);
                }
                return loadedChunk;
            });
            // NO exception handling - let it crash immediately with full stack trace
            // (whenComplete below does not swallow: the stored future still completes
            // exceptionally and the fast path above rethrows on next poll)

        // Track this pending load BEFORE attaching the removal — if the removal lived
        // inside the chain above, a fast completion could remove the entry before this
        // put ran, stranding a stale completed future in the map.
        pendingChunkLoads.put(pos, loadFuture);
        loadFuture.whenComplete((c, t) -> pendingChunkLoads.remove(pos));

        // Return null - chunk will be available on next frame
        return null;
    }

    /**
     * Finalizes chunk loading by setting up feature population and notifying listeners.
     * Called when async chunk load completes.
     */
    private void finalizeChunkLoad(ChunkPosition pos, Chunk chunk) {
        // Queue for deferred feature population if not already populated
        if (!chunk.areFeaturesPopulated()) {
            pendingFeaturePopulation.offer(pos);
        }

        // Process queued features that were waiting for this chunk
        featureQueue.processChunk(world, pos);

        // Notify load listener
        if (loadListener != null) {
            notify(loadListener, chunk);
        }
    }

    public boolean hasChunk(int x, int z) {
        return chunks.containsKey(positionCache.get(x, z));
    }

    public CompletableFuture<Void> awaitPendingLoads() {
        Collection<CompletableFuture<Chunk>> pending = new ArrayList<>(pendingChunkLoads.values());
        if (pending.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
    }

    public void ensureChunkExists(int x, int z) {
        getOrCreateChunk(x, z);
    }

    public Collection<Chunk> getAllChunks() {
        return new ArrayList<>(chunks.values());
    }

    public Set<ChunkPosition> getAllChunkPositions() {
        return new HashSet<>(chunks.keySet());
    }

    public List<Chunk> getDirtyChunks() {
        List<Chunk> dirty = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk != null && chunk.isDirty()) {
                dirty.add(chunk);
            }
        }
        return dirty;
    }

    public int getLoadedChunkCount() {
        return chunks.size();
    }

    /**
     * Visits every resident chunk within render distance of the given chunk position.
     * Replaces the old {@code getChunksInRenderDistance}, which built a fresh ~(2r+1)²-entry
     * HashMap per call — pure per-frame garbage at high render distances since the render
     * path only ever iterated the values.
     */
    public void forEachChunkInRenderDistance(int playerChunkX, int playerChunkZ, Consumer<Chunk> action) {
        int renderDist = config.getRenderDistance();

        for (int x = playerChunkX - renderDist; x <= playerChunkX + renderDist; x++) {
            for (int z = playerChunkZ - renderDist; z <= playerChunkZ + renderDist; z++) {
                // Render-only (client) worlds must NEVER generate here — that would create empty
                // all-air placeholder chunks the server never streams, whose empty meshes are
                // treated as failed builds. Only visit chunks the server has already installed.
                Chunk chunk = terrainGenerationEnabled ? getOrCreateChunk(x, z) : getChunk(x, z);
                if (chunk != null) {
                    action.accept(chunk);
                }
            }
        }
    }

    /**
     * Unloads every resident chunk whose Chebyshev distance from the given center exceeds
     * {@code keepRadius}. Iterates the live key set directly — ConcurrentHashMap's view is
     * weakly consistent and tolerates concurrent removal, so no defensive copy is needed.
     */
    public void unloadChunksOutside(int centerChunkX, int centerChunkZ, int keepRadius) {
        for (ChunkPosition cp : chunks.keySet()) {
            int dist = Math.max(Math.abs(cp.getX() - centerChunkX), Math.abs(cp.getZ() - centerChunkZ));
            if (dist > keepRadius) {
                unloadChunk(cp.getX(), cp.getZ());
            }
        }
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        ChunkPosition pos = positionCache.get(chunkX, chunkZ);
        pendingColumnProfiles.remove(pos);
        Chunk chunk = chunks.remove(pos);
        if (chunk == null) {
            // Chunk doesn't exist, but still clean up position cache entry
            positionCache.remove(chunkX, chunkZ);
            return;
        }

        // Save chunk BEFORE notifying the unload listener so the snapshot captures
        // final state (water layer travels with the chunk; the listener only drops
        // the sim's pending queue entries and other per-chunk registrations).

        // OPTIMIZATION: Async save - don't block main thread
        // Save dirty chunks asynchronously, cleanup after save completes.
        // A render-only client world has no save service — skip the save entirely (its chunks
        // are authoritative on the server) so unloading doesn't error on every dirty chunk.
        if (chunk.isDirty() && getSaveService() != null) {
            saveIfDirtyAsync(chunk).thenRun(() -> {
                // AFTER save completes, notify unload listener to clean up water cells
                notify(unloadListener, chunk);
                cleanup(chunk, chunkX, chunkZ);
                positionCache.remove(chunkX, chunkZ);
            }).exceptionally(ex -> {
                System.err.println("CRITICAL: Async save failed for chunk (" + chunkX + ", " + chunkZ + "): " + ex.getMessage());
                // Still cleanup to prevent memory leak
                notify(unloadListener, chunk);
                cleanup(chunk, chunkX, chunkZ);
                positionCache.remove(chunkX, chunkZ);
                return null;
            });
        } else {
            // Clean chunks can be unloaded immediately
            notify(unloadListener, chunk);
            cleanup(chunk, chunkX, chunkZ);
            positionCache.remove(chunkX, chunkZ);
        }

        if ((chunkX + chunkZ) % WorldConfiguration.MEMORY_LOG_INTERVAL == 0) {
            Game.logDetailedMemoryInfo("After unloading chunk (" + chunkX + ", " + chunkZ + ")");
        }
    }

    public void setChunk(int x, int z, Chunk chunk) {
        chunks.put(positionCache.get(x, z), chunk);
    }

    public void cleanup() {
        // Cancel any pending chunk loads
        pendingChunkLoads.values().forEach(future -> future.cancel(true));
        pendingChunkLoads.clear();

        // Stop the generation workers (same pattern as ChunkManager.shutdown):
        // graceful drain, then force after a timeout.
        generationExecutor.shutdown();
        try {
            if (!generationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                generationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            generationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Clean up loaded chunks. GL deletion MUST happen on the main OpenGL thread —
        // cleanup() can be reached from the "ClientWorld-Build" thread (world swap on
        // reconnect), where an inline glDeleteVertexArrays aborts the JVM ("No context is
        // current"). runOnMainThread runs the loop inline when we're already on the main
        // thread (shutdown / quit-to-menu), so those paths keep their exact old behavior.
        final List<Chunk> chunksToRelease = new ArrayList<>(chunks.values());
        Game.getInstance().runOnMainThread(() -> {
            for (Chunk chunk : chunksToRelease) {
                if (chunk != null) chunk.cleanupGpuResources();
            }
        });
        chunks.clear();
        positionCache.clear();
        featureQueue.clear();
    }

    public ChunkPosition getCachedChunkPosition(int x, int z) {
        return positionCache.get(x, z);
    }

    public void cleanupPositionCacheIfNeeded(int loadedChunks) {
        if (positionCache.size() > loadedChunks * 2) {
            positionCache.cleanup(chunks.keySet());
            System.out.println("Position cache cleaned: " + loadedChunks + " chunks loaded");
        }
    }

    public void clearPositionCacheIfLarge(int threshold) {
        if (positionCache.size() > threshold) {
            positionCache.clear();
        }
    }

    // Feature population is TIME-budgeted, not count-budgeted. On the server
    // tick the caller passes a DEADLINE anchored at the tick's own start
    // (World.updateSimulation), so the drain consumes whatever real headroom
    // this tick has left instead of a fixed private slice — a light tick
    // populates for ~30 ms, a heavy one backs off automatically. The legacy
    // render-thread path (single-world World.update) keeps a small fixed
    // slice to protect the frame.
    private static final long FEATURE_FRAME_SLICE_NANOS = 8_000_000L; // render-thread path only
    private static final int FEATURE_HARD_CAP = 512;                 // runaway guard per drain

    /**
     * Render-thread variant: drains with a small fixed slice so the frame
     * stays protected. Server ticks use {@link #processPendingFeaturePopulation(long)}.
     */
    public void processPendingFeaturePopulation() {
        processPendingFeaturePopulation(System.nanoTime() + FEATURE_FRAME_SLICE_NANOS);
    }

    /**
     * Processes chunks waiting for feature population until {@code deadline}
     * (nanoTime) passes or the queue empties. Only populates chunks whose
     * neighbors exist to prevent recursion.
     */
    public void processPendingFeaturePopulation(long deadline) {

        ChunkPosition pos;
        int processed = 0;

        while (processed < FEATURE_HARD_CAP && System.nanoTime() < deadline
                && (pos = pendingFeaturePopulation.poll()) != null) {
            Chunk chunk = chunks.get(pos);

            // Skip if chunk was unloaded or already has features
            if (chunk == null || chunk.areFeaturesPopulated()) {
                pendingColumnProfiles.remove(pos);
                continue;
            }

            // Check if all required neighbors exist (east, south, southeast)
            int x = pos.getX();
            int z = pos.getZ();
            boolean neighborsReady = hasChunk(x + 1, z) &&
                                   hasChunk(x, z + 1) &&
                                   hasChunk(x + 1, z + 1);

            if (neighborsReady) {
                // Neighbors exist - safe to populate features
                try {
                    // Profile from terrain generation (null for disk-loaded chunks
                    // that somehow still need features — populate recomputes then).
                    ColumnProfile profile = pendingColumnProfiles.remove(pos);
                    terrainSystem.populateChunkWithFeatures(world, chunk, world.getSnowLayerManager(), profile);
                    chunk.setFeaturesPopulated(true);
                    // Features write via chunk.setBlock(), which only flips the CCO dirty flag
                    // and does not schedule a remesh. If the mesh was already built from
                    // terrain-only data (common when flying fast), flowers/wildgrass would
                    // stay invisible until another write forced a rebuild. Schedule it here
                    // so every populated chunk is guaranteed to remesh once features land.
                    if (meshPipeline != null) {
                        meshPipeline.scheduleConditionalMeshBuild(chunk);
                    }
                    // Passive mobs are no longer spawned at chunk generation. Population is owned
                    // entirely by the server's continuous, visibility-capped spawner (EntitySpawner),
                    // which fills the loaded area toward a dynamic cap and lets depopulated regions
                    // refill. We still mark the chunk entity-processed so its persisted state stays
                    // consistent and re-loaded chunks restore their own saved entities.
                    if (!chunk.getCcoMetadata().hasEntities()) {
                        chunk.setEntitiesGenerated(true);
                    }
                    ChunkPipelineStats.POPULATED.increment();
                    processed++;
                } catch (Exception e) {
                    System.err.println("Exception populating features for chunk (" + x + ", " + z + "): " + e.getMessage());
                    // Mark as populated anyway to prevent infinite retry
                    chunk.setFeaturesPopulated(true);
                }
            } else {
                // Re-queue if neighbors not ready yet
                pendingFeaturePopulation.offer(pos);
                break; // Stop processing to prevent spinning on same chunk
            }
        }
    }

    // ========== Private Implementation ==========

    /**
     * Loads chunk from save or generates new one asynchronously. Uses CCO snapshots via SaveService.
     * Features are deferred via queue to prevent recursive chunk generation.
     * Returns CompletableFuture that completes when chunk is ready.
     */
    private CompletableFuture<Chunk> loadOrGenerateAsync(int x, int z) {
        SaveService saveService = getSaveService();

        // Try loading from disk first (async)
        if (saveService != null) {
            return saveService.loadChunk(x, z)
                .thenCompose(loaded -> {
                    if (loaded != null) {
                        // Disk hit: prepare on the io thread, as before.
                        prepareLoadedChunk(loaded);
                        return CompletableFuture.completedFuture(loaded);
                    }
                    // Disk miss: hop to the generation pool. This frees the single io
                    // thread immediately and lets N chunks generate in parallel —
                    // previously generation ran serialized on the io thread itself.
                    return CompletableFuture.supplyAsync(() -> generate(x, z), generationExecutor);
                });
                // NO exception handling - let corruption errors crash immediately with full stack trace
        }

        // No save service (render-only client world): still a cheap empty chunk,
        // but keep it off the caller's thread for a uniform async contract.
        return CompletableFuture.supplyAsync(() -> generate(x, z), generationExecutor);
    }

    private Chunk generate(int x, int z) {
        // Client render-view: no local terrain. Hand back an empty chunk for the network
        // layer to fill (installNetworkChunk). No features, no mob spawn, no save.
        if (!terrainGenerationEnabled) {
            return generateEmptyChunk(x, z);
        }
        try {
            MemoryProfiler.getInstance().incrementAllocation("Chunk");

            // Generate terrain ONLY - features will be populated later via queue
            // This prevents recursive chunk generation during mesh building
            TerrainGenerationSystem.TerrainResult result = terrainSystem.generateTerrainOnly(x, z);
            if (result == null || result.chunk() == null) {
                System.err.println("CRITICAL: Failed to generate chunk at (" + x + ", " + z + ")");
                return null;
            }
            Chunk chunk = result.chunk();
            ChunkPipelineStats.GENERATED.increment();

            // Keep the column profile so deferred feature population reuses it
            // instead of resampling the noise stack (~3 KB per pending chunk).
            pendingColumnProfiles.put(positionCache.get(x, z), result.profile());

            // DO NOT populate features here - they will be populated by processPendingFeaturePopulation()
            // when neighbors exist and it's safe to do so without triggering recursion

            // Populate the sky-shadow heightmap now that terrain is final.
            // Avoids paying the lazy one-shot scan on the first mesh-build sample
            // (which would otherwise block the mesh thread for ~1 ms/chunk).
            // The fused native generator already returned it (heightMap.populate);
            // only the legacy path still needs the column rescan.
            if (!chunk.getHeightMap().isPopulated()) {
                chunk.getHeightMap().recomputeAll(chunk.getOpacityProbe());
            }

            // Initial mob spawning is DEFERRED to processPendingFeaturePopulation(): at this
            // point the chunk isn't in the chunk store yet, features haven't landed, and
            // neighbors may not exist — all of which would spawn mobs onto unstable terrain
            // and let them fall through the world.

            // CRITICAL FIX: Mark newly generated chunks as clean UNLESS they contain flowing water
            // setBlock() calls during generation marked them dirty, but they don't
            // need saving until the PLAYER modifies them. This prevents all 3000+
            // generated chunks from staying dirty forever and never being unloaded.
            // EXCEPTION: Chunks with flowing water MUST be saved to persist water metadata.
            // (Initial mob spawning now happens later, in processPendingFeaturePopulation,
            // and dirties the chunk itself via setEntitiesGenerated.)
            boolean hasFlowingWater = chunkHasFlowingWater(chunk);

            if (!hasFlowingWater) {
                chunk.markClean();
            } else {
                System.out.println("[WATER-GEN] Chunk (" + x + ", " + z + ") has flowing water - keeping dirty for save");
            }

            return chunk;
        } catch (Exception e) {
            System.err.println("Exception generating chunk (" + x + ", " + z + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds an empty, all-air chunk for a client render-view world (terrain generation
     * disabled). The chunk's real contents arrive later via {@code installNetworkChunk}; this
     * is just the resident placeholder so streamed data has somewhere to land. Marked as
     * features-populated and clean — a client never generates features and never persists.
     */
    private Chunk generateEmptyChunk(int x, int z) {
        MemoryProfiler.getInstance().incrementAllocation("Chunk");
        Chunk chunk = new Chunk(x, z);
        chunk.setFeaturesPopulated(true);
        // Heightmap must be valid before the mesh-build samples it (sky shadows).
        chunk.getHeightMap().recomputeAll(chunk.getOpacityProbe());
        chunk.markClean();
        return chunk;
    }

    /**
     * Checks if a chunk contains any flowing (non-source) water blocks.
     * Used to determine if a newly generated chunk needs to be saved to persist water metadata.
     * Reads the chunk-owned water layer directly — no world scan.
     */
    private boolean chunkHasFlowingWater(Chunk chunk) {
        return !chunk.getWaterLayer().isEmpty();
    }

    private void prepareLoadedChunk(Chunk chunk) {
        if (!chunk.areFeaturesPopulated()) {
            chunk.setFeaturesPopulated(true);
        }
        // Heightmap is not serialized — rebuild from deserialized blocks before
        // the mesh-build samples it.
        chunk.getHeightMap().recomputeAll(chunk.getOpacityProbe());
        chunk.cleanupCpuResources();
        if (meshPipeline != null) {
            meshPipeline.scheduleConditionalMeshBuild(chunk);
        }
    }

    private boolean shouldMesh(int chunkX, int chunkZ) {
        Player player = Game.getPlayer();
        if (player == null) return true;

        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        int distance = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));

        return distance <= config.getBorderChunkDistance();
    }

    /**
     * Saves dirty chunk asynchronously using CCO integration.
     * SaveService internally uses chunk.createSnapshot() via StateConverter.
     * OPTIMIZATION: Returns CompletableFuture to avoid blocking main thread.
     */
    private CompletableFuture<Void> saveIfDirtyAsync(Chunk chunk) {
        if (!chunk.isDirty()) {
            return CompletableFuture.completedFuture(null);
        }

        SaveService saveService = getSaveService();
        if (saveService == null) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Cannot save dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ") - save system unavailable")
            );
        }

        return saveService.saveChunk(chunk)
            .thenRun(() -> {
                // Logging removed for performance - chunk saves happen frequently
            })
            .exceptionally(ex -> {
                System.err.println("CRITICAL: Failed to save chunk (" + chunk.getX() + ", " + chunk.getZ() + "): " + getErrorMessage((Exception) ex));
                return null;
            });
    }

    private void cleanup(Chunk chunk, int chunkX, int chunkZ) {
        if (meshPipeline != null) {
            meshPipeline.removeChunkFromQueues(chunk);
        }
        chunk.cleanupCpuResources();
        if (meshPipeline != null) {
            meshPipeline.addChunkForGpuCleanup(chunk);
        }
        // Remove entities owned by THIS world's manager (two-world model): on the client render
        // world this culls local shadows (the manager guards against removing network shadows /
        // remote players); on a headless server world it would target the server's manager. Using
        // the world's own manager rather than the Game singleton (which always resolves to the
        // CLIENT) keeps the removal authority unambiguous. The server world never unloads chunks.
        com.stonebreak.mobs.entities.EntityManager entityManager =
            (world != null) ? world.getEntityManager() : null;
        if (entityManager != null) {
            entityManager.removeEntitiesInChunk(chunkX, chunkZ);
        }
    }

    private void notify(Consumer<Chunk> listener, Chunk chunk) {
        if (listener != null) listener.accept(chunk);
    }

    private SaveService getSaveService() {
        // Per-world persistence (set via SaveService.initialize). A client render world has
        // none — its chunks are authoritative on the server and never saved locally.
        return (world != null) ? world.getSaveService() : null;
    }

    private String getErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName();
        }
        return msg != null ? msg : "Unknown error";
    }

    // ========== Position Cache ==========

    private static class PositionCache {
        private static final int MAX_SIZE = WorldConfiguration.MAX_CHUNK_POSITION_CACHE_SIZE;
        private final Map<Long, ChunkPosition> cache = new ConcurrentHashMap<>();

        ChunkPosition get(int x, int z) {
            if (cache.size() > MAX_SIZE) {
                System.out.println("WARNING: Position cache exceeded max size, clearing");
                cache.clear();
            }
            return cache.computeIfAbsent(key(x, z), k -> new ChunkPosition(x, z));
        }

        void remove(int x, int z) {
            cache.remove(key(x, z));
        }

        void clear() {
            cache.clear();
        }

        int size() {
            return cache.size();
        }

        void cleanup(Set<ChunkPosition> loadedPositions) {
            Set<Long> loadedKeys = new HashSet<>();
            loadedPositions.forEach(pos -> loadedKeys.add(key(pos.getX(), pos.getZ())));
            cache.keySet().removeIf(key -> !loadedKeys.contains(key));
            MemoryProfiler.getInstance().takeSnapshot("chunk_position_cache_cleanup");
        }

        private long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
