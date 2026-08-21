package com.stonebreak.world.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.engine.cearl.CearlCompiler;
import com.openmason.engine.cearl.CearlProgram;
import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsGeometry.MmsGreedyMesher;
import com.openmason.engine.voxel.mms.mmsRegion.MmsArenaSim;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.openmason.engine.vram.VramArenaPolicy;
import com.openmason.engine.vram.VramPlan;
import com.openmason.engine.vram.VramPlans;
import com.openmason.engine.vram.VramPool;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.CendaMesher;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter;
import com.stonebreak.world.generation.TerrainGenStats;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The chunk footprint lab: generates and meshes a {@code tier × tier} square of
 * real terrain chunks headlessly, and reports everything that costs memory —
 * per-chunk heap (palette tiers, height map, water layer), mesh bytes (atlas +
 * water, vertices + indices, greedy merge ratio, quad conformance), and the
 * VRAM a region arena would actually reserve for those meshes under the active
 * CEARL plan ({@link MmsArenaSim}, copy and sparse growth) — alongside gen and
 * mesh timings so a memory win that costs time is visible.
 *
 * <p>Every number is written to a JSON ledger so iterations can be diffed:
 * {@code Testing/chunk-lab.sh <tier> <label>} runs it and diffs against the
 * tier's {@code baseline.json}.
 *
 * <p>Measured chunks are {@code [0,tier)²}, so tier 8 is exactly one 8×8
 * region and tier 16 is four regions (one FastLOD region). A one-chunk ring
 * around the square is generated (not measured) so border faces cull against
 * real neighbours exactly as in-game.
 */
public final class ChunkFootprintLab {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final String PLAN_RESOURCE = "/cearl/stonebreak.CEARL";

    /** One measured chunk's results, in upload order. */
    record ChunkSample(int cx, int cz, long genNanos, long genAllocBytes, long meshNanos,
                       long meshAllocBytes, ChunkRamProbe.Result ram,
                       MeshStats atlas, MeshStats water, MeshStats stamp, int sboEntries,
                       long kernelQuads, long greedyQuads) {
    }

    /** Byte-level stats of one packed mesh. */
    record MeshStats(int vertices, int indices, long vertexBytes, long indexBytes,
                     boolean shortIndices, int quads, int conformantQuads) {
        static final MeshStats EMPTY = new MeshStats(0, 0, 0, 0, true, 0, 0);

        long bytes() {
            return vertexBytes + indexBytes;
        }
    }

    private final LabConfig config;
    private final ChunkRamProbe ramProbe = new ChunkRamProbe();
    private final AllocMeter threads = new AllocMeter();

    private TerrainGenerationSystem terrain;
    private TestWorld world;
    private MmsCcoAdapter adapter;
    private final List<String> notes = new ArrayList<>();

    public ChunkFootprintLab(LabConfig config) {
        this.config = config;
    }

    /** Runs the whole lab and returns the report tree (also written to the ledger). */
    public Map<String, Object> run() throws IOException {
        long t0 = System.nanoTime();
        // Same engine bounds the game installs in GameBootstrap.configureEngine (feature
        // population and CCO coordinate helpers read them).
        com.openmason.engine.voxel.cco.coordinates.CcoBounds.configure(
            new com.openmason.engine.voxel.VoxelWorldConfig(CHUNK, WORLD_HEIGHT,
                WorldConfiguration.SEA_LEVEL));
        terrain = new TerrainGenerationSystem(config.seed());
        world = new TestWorld(new WorldConfiguration(Math.max(8, config.tier() + 2), 4),
            config.seed(), true);
        adapter = new MmsCcoAdapter(new StubTextureMapper(), world);

        List<int[]> measured = measuredOrder(config.tier());
        List<int[]> ring = ringAround(config.tier());

        // ── Generation (ring first so it never pollutes the measured timings) ──
        Map<Long, Chunk> chunks = new LinkedHashMap<>();
        for (int[] c : ring) {
            chunks.put(key(c[0], c[1]), generate(c[0], c[1]));
        }
        long[] genNanos = new long[measured.size()];
        long[] genAlloc = new long[measured.size()];
        for (int i = 0; i < measured.size(); i++) {
            int[] c = measured.get(i);
            long a0 = threads.getCurrentThreadAllocatedBytes();
            long n0 = System.nanoTime();
            Chunk chunk = generate(c[0], c[1]);
            genNanos[i] = System.nanoTime() - n0;
            genAlloc[i] = threads.getCurrentThreadAllocatedBytes() - a0;
            chunks.put(key(c[0], c[1]), chunk);
        }
        boolean featuresRan = false;
        if (config.features()) {
            featuresRan = populateFeatures(chunks, measured, ring);
        }

        // ── Meshing ──
        // JIT warm-up on ring chunks (not measured) so the tier pass reflects steady state.
        for (int i = 0; i < Math.min(ring.size(), 12); i++) {
            int[] c = ring.get(i);
            mesh(chunks.get(key(c[0], c[1])));
        }
        List<ChunkSample> samples = new ArrayList<>();
        long quadsIn0 = MmsGreedyMesher.quadsIn();
        long quadsOut0 = MmsGreedyMesher.quadsOut();
        for (int i = 0; i < measured.size(); i++) {
            int[] c = measured.get(i);
            Chunk chunk = chunks.get(key(c[0], c[1]));
            long qi0 = MmsGreedyMesher.quadsIn();
            long qo0 = MmsGreedyMesher.quadsOut();
            long a0 = threads.getCurrentThreadAllocatedBytes();
            long n0 = System.nanoTime();
            ChunkMeshResult result = mesh(chunk);
            long meshNs = System.nanoTime() - n0;
            long meshAlloc = threads.getCurrentThreadAllocatedBytes() - a0;
            samples.add(new ChunkSample(c[0], c[1], genNanos[i], genAlloc[i], meshNs, meshAlloc,
                ramProbe.probe(chunk),
                stats(result.atlasMesh()), stats(result.waterMesh()), stats(result.stampMesh()),
                result.sboEntries() == null ? 0 : result.sboEntries().size(),
                MmsGreedyMesher.quadsIn() - qi0, MmsGreedyMesher.quadsOut() - qo0));
        }
        long tierQuadsIn = MmsGreedyMesher.quadsIn() - quadsIn0;
        long tierQuadsOut = MmsGreedyMesher.quadsOut() - quadsOut0;

        // ── Best-of timings on the centre chunk (steady state, no GC noise) ──
        int[] centre = measured.getFirst();
        Chunk centreChunk = chunks.get(key(centre[0], centre[1]));
        long bestGen = best(5, config.reps(), () -> terrain.generateTerrainOnly(centre[0], centre[1]));
        long bestMesh = best(5, config.reps(), () -> mesh(centreChunk));

        // ── Planned VRAM ──
        PlanInfo plan = loadPlan();
        Map<String, Object> vram = simulateRegions(samples, plan);

        // ── Report ──
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("label", config.label());
        report.put("tier", config.tier());
        report.put("seed", config.seed());
        report.put("timestamp", Instant.now().toString());
        report.put("environment", environment(featuresRan, plan));
        report.put("generation", generation(samples, bestGen));
        report.put("chunkRam", chunkRam(samples));
        report.put("mesh", mesh(samples, bestMesh, tierQuadsIn, tierQuadsOut));
        report.put("vram", vram);
        if (config.gl()) {
            report.put("gl", GlProbe.measure(samples, chunks, this::mesh, plan, notes));
        }
        report.put("perChunk", perChunk(samples));
        report.put("notes", List.copyOf(notes));
        report.put("wallSeconds", (System.nanoTime() - t0) / 1e9);

        Path file = config.ledgerFile();
        Files.createDirectories(file.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), report);
        System.out.println("[chunk-lab] wrote " + file);
        return report;
    }

    // ─── Generation / meshing primitives ──────────────────────────────────

    private Chunk generate(int cx, int cz) {
        Chunk chunk = terrain.generateTerrainOnly(cx, cz).chunk();
        world.setChunk(cx, cz, chunk);
        return chunk;
    }

    private boolean populateFeatures(Map<Long, Chunk> chunks, List<int[]> measured, List<int[]> ring) {
        // Feature population needs (+1,0), (0,+1), (+1,+1) neighbours — the ring supplies them
        // for every measured chunk. Ring chunks themselves stay terrain-only.
        try {
            for (int[] c : measured) {
                Chunk chunk = chunks.get(key(c[0], c[1]));
                terrain.populateChunkWithFeatures(world, chunk, world.getSnowLayerManager(), null);
            }
            return true;
        } catch (RuntimeException e) {
            notes.add("feature population failed headlessly (" + e + ") — chunks are terrain-only");
            return false;
        }
    }

    ChunkMeshResult mesh(Chunk chunk) {
        return adapter.generateChunkMesh(new ChunkDataView(chunk), chunk.getCcoStateManager(),
            chunk.getCcoDirtyTracker());
    }

    private interface Op {
        Object run();
    }

    private static long best(int warmup, int reps, Op op) {
        for (int i = 0; i < warmup; i++) {
            op.run();
        }
        long bestNs = Long.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                op.run();
            }
            bestNs = Math.min(bestNs, (System.nanoTime() - t0) / reps);
        }
        return bestNs;
    }

    // ─── Mesh byte stats ──────────────────────────────────────────────────

    static MeshStats stats(MmsMeshData mesh) {
        if (mesh == null || mesh.isEmpty()) {
            return MeshStats.EMPTY;
        }
        int vertices = mesh.getVertexCount();
        int indices = mesh.getIndexCount();
        long vertexBytes;
        long indexBytes;
        int conformant = 0;
        int quads = indices / 6;
        if (mesh.getFormat().pulled()) {
            vertexBytes = mesh.getPackedVertexData().length; // 16 B per quad
            indexBytes = 0;                                    // shared quad EBO
            conformant = quads;
        } else if (mesh.isPacked()) {
            vertexBytes = mesh.getPackedVertexData().length;
            indexBytes = mesh.getPackedIndexData().length;
            conformant = countConformantQuads(mesh.getPackedIndexData(), mesh.hasShortIndices(), indices);
        } else {
            vertexBytes = (long) vertices * MmsVertexFormat.active().stride();
            indexBytes = (long) indices * Integer.BYTES;
        }
        return new MeshStats(vertices, indices, vertexBytes, indexBytes, mesh.hasShortIndices(),
            quads, conformant);
    }

    /**
     * Counts quads whose six indices follow the builder's fixed pattern
     * {@code (b, b+2, b+1, b, b+3, b+2)} — the ones a shared quad EBO could
     * serve. Cross blocks and SBO stamp triangles break the pattern.
     */
    private static int countConformantQuads(byte[] indexBytes, boolean shortIndices, int indexCount) {
        ByteBuffer buf = ByteBuffer.wrap(indexBytes).order(ByteOrder.nativeOrder());
        int conformant = 0;
        for (int q = 0; q + 6 <= indexCount; q += 6) {
            int[] i = new int[6];
            for (int k = 0; k < 6; k++) {
                i[k] = shortIndices ? buf.getShort((q + k) * 2) & 0xFFFF : buf.getInt((q + k) * 4);
            }
            int b = i[0];
            if (i[1] == b + 2 && i[2] == b + 1 && i[3] == b && i[4] == b + 3 && i[5] == b + 2
                    && b == (q / 6) * 4) {
                conformant++;
            }
        }
        return conformant;
    }

    // ─── Planned VRAM ─────────────────────────────────────────────────────

    record PlanInfo(String source, VramPlan plan, VramArenaPolicy chunkArena,
                    VramArenaPolicy lodTerrainArena, VramArenaPolicy lodWaterArena) {
        VramArenaPolicy waterArena() {
            return VramPlans.arena(VramPlans.POOL_CHUNK_WATER);
        }

        VramArenaPolicy stampArena() {
            return VramPlans.arena(VramPlans.POOL_CHUNK_STAMP);
        }
    }

    private PlanInfo loadPlan() {
        String source;
        String name;
        try {
            if (config.cearlPath() != null) {
                source = Files.readString(Path.of(config.cearlPath()));
                name = config.cearlPath();
            } else {
                try (InputStream in = ChunkFootprintLab.class.getResourceAsStream(PLAN_RESOURCE)) {
                    if (in == null) {
                        notes.add("shipped CEARL plan not on classpath — builtin arena policy used");
                        return new PlanInfo("builtin", VramPlans.builtin(), VramPlans.defaultArena(),
                            VramPlans.arena(VramPlans.POOL_LOD_TERRAIN),
                            VramPlans.arena(VramPlans.POOL_LOD_WATER));
                    }
                    source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    name = "cearl/stonebreak.CEARL";
                }
            }
            CearlProgram program = CearlCompiler.compile(source, name, Map.of("vram", 0L));
            VramPlan plan = program.plan();
            if (plan != null) {
                VramPlans.install(plan); // merge over builtin exactly like CearlBootstrap
            }
            return new PlanInfo(name, VramPlans.active(),
                VramPlans.arena(VramPlans.POOL_CHUNK_MESH),
                VramPlans.arena(VramPlans.POOL_LOD_TERRAIN),
                VramPlans.arena(VramPlans.POOL_LOD_WATER));
        } catch (IOException | RuntimeException e) {
            notes.add("CEARL plan load failed (" + e.getMessage() + ") — builtin arena policy used");
            VramPlans.reset();
            return new PlanInfo("builtin", VramPlans.builtin(), VramPlans.defaultArena(),
                VramPlans.arena(VramPlans.POOL_LOD_TERRAIN), VramPlans.arena(VramPlans.POOL_LOD_WATER));
        }
    }

    private Map<String, Object> simulateRegions(List<ChunkSample> samples, PlanInfo plan) {
        MmsVertexFormat atlasFmt = MmsVertexFormat.active();
        MmsVertexFormat stampFmt = atlasFmt.stampFormat();
        int vertexStride = atlasFmt.stride();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planSource", plan.source());
        out.put("atlasFormat", atlasFmt.name());
        out.put("vertexStrideBytes", vertexStride);
        out.put("indexStrideBytes", atlasFmt.indexStride());
        out.put("stampFormat", stampFmt.name());
        out.put("waterFormat", atlasFmt.waterFormat().name());
        out.put("chunkArenaPolicy", policy(plan.chunkArena()));
        out.put("waterArenaPolicy", policy(plan.waterArena()));
        out.put("stampArenaPolicy", policy(plan.stampArena()));

        // Group by 8×8 region, keep upload order within each region.
        Map<Long, List<ChunkSample>> regions = new TreeMap<>();
        for (ChunkSample s : samples) {
            long rk = key(s.cx() >> MmsChunkRegion.REGION_SHIFT, s.cz() >> MmsChunkRegion.REGION_SHIFT);
            regions.computeIfAbsent(rk, k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> regionReports = new ArrayList<>();
        long[] totals = new long[8]; // copy reserved/used/slack, sparse reserved/used/slack, atlas raw, water raw
        for (Map.Entry<Long, List<ChunkSample>> e : regions.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("regionX", (int) (e.getKey() >> 32));
            r.put("regionZ", (int) e.getKey().longValue());
            r.put("chunks", e.getValue().size());
            long rawAtlas = 0, rawWater = 0, rawStamp = 0;
            for (ChunkSample s : e.getValue()) {
                rawAtlas += s.atlas().bytes();
                rawWater += s.water().bytes();
                rawStamp += s.stamp().bytes();
            }
            r.put("rawAtlasBytes", rawAtlas);
            r.put("rawWaterBytes", rawWater);
            r.put("rawStampBytes", rawStamp);
            totals[6] += rawAtlas + rawStamp;
            totals[7] += rawWater;
            for (boolean sparse : new boolean[]{false, true}) {
                // Up to three layer regions per 8×8 block of columns (atlas, water,
                // stamp), exactly like ChunkRegionRenderer.
                MmsArenaSim atlas = new MmsArenaSim(plan.chunkArena(), vertexStride, atlasFmt.indexStride(), sparse, 65536);
                MmsVertexFormat waterFmt = atlasFmt.waterFormat();
                MmsArenaSim water = new MmsArenaSim(plan.waterArena(), waterFmt.stride(), waterFmt.indexStride(), sparse, 65536);
                MmsArenaSim stampSim = new MmsArenaSim(plan.stampArena(), stampFmt.stride(), Short.BYTES, sparse, 65536);
                boolean anyWater = false, anyStamp = false;
                for (ChunkSample s : e.getValue()) {
                    if (s.atlas().vertices() > 0) {
                        atlas.upload(s.atlas().vertices(), s.atlas().indices());
                    }
                    if (s.water().vertices() > 0) {
                        water.upload(s.water().vertices(), s.water().indices());
                        anyWater = true;
                    }
                    if (s.stamp().vertices() > 0) {
                        stampSim.upload(s.stamp().vertices(), s.stamp().indices());
                        anyStamp = true;
                    }
                }
                MmsArenaSim.Report ar = atlas.report();
                MmsArenaSim.Report wr = anyWater ? water.report() : null;
                MmsArenaSim.Report sr = anyStamp ? stampSim.report() : null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("atlas", arena(ar));
                m.put("water", wr == null ? null : arena(wr));
                m.put("stamp", sr == null ? null : arena(sr));
                long reserved = ar.reservedBytes() + (wr == null ? 0 : wr.reservedBytes())
                    + (sr == null ? 0 : sr.reservedBytes());
                long used = ar.usedBytes() + (wr == null ? 0 : wr.usedBytes()) + (sr == null ? 0 : sr.usedBytes());
                m.put("reservedBytes", reserved);
                m.put("usedBytes", used);
                m.put("slackBytes", reserved - used);
                m.put("utilization", reserved == 0 ? 1.0 : (double) used / reserved);
                // After the per-frame trim pass settles (only matters when the plan enables trim).
                atlas.trimToRest();
                if (anyWater) {
                    water.trimToRest();
                }
                if (anyStamp) {
                    stampSim.trimToRest();
                }
                long reservedTrimmed = atlas.report().reservedBytes()
                    + (anyWater ? water.report().reservedBytes() : 0)
                    + (anyStamp ? stampSim.report().reservedBytes() : 0);
                m.put("reservedAfterTrimBytes", reservedTrimmed);
                r.put(sparse ? "sparse" : "copy", m);
                int base = sparse ? 3 : 0;
                totals[base] += reserved;
                totals[base + 1] += used;
                totals[base + 2] += reserved - used;
            }
            regionReports.add(r);
        }
        out.put("regions", regionReports);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("regionCount", regionReports.size());
        t.put("rawAtlasBytes", totals[6]);
        t.put("rawWaterBytes", totals[7]);
        t.put("rawMeshBytes", totals[6] + totals[7]);
        t.put("copyReservedBytes", totals[0]);
        t.put("copyUsedBytes", totals[1]);
        t.put("copySlackBytes", totals[2]);
        t.put("sparseReservedBytes", totals[3]);
        t.put("sparseUsedBytes", totals[4]);
        t.put("sparseSlackBytes", totals[5]);
        t.put("planModeReservedBytes", plan.chunkArena().sparseGrowth() ? totals[3] : totals[0]);
        t.put("planMode", plan.chunkArena().sparseGrowth() ? "sparse" : "copy");
        out.put("totals", t);
        return out;
    }

    private static Map<String, Object> arena(MmsArenaSim.Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("liveMeshes", r.liveMeshes());
        m.put("reservedBytes", r.reservedBytes());
        m.put("usedBytes", r.usedBytes());
        m.put("slackBytes", r.slackBytes());
        m.put("utilization", r.utilization());
        m.put("vertexCapacityBytes", r.vertexCapacityBytes());
        m.put("indexCapacityBytes", r.indexCapacityBytes());
        m.put("vertexUsedBytes", r.vertexUsedBytes());
        m.put("indexUsedBytes", r.indexUsedBytes());
        m.put("growEvents", r.growEvents());
        m.put("bytesCopied", r.bytesCopied());
        if (r.sparse()) {
            m.put("committedBytes", r.committedBytes());
            m.put("virtualBytes", r.vertexVirtualBytes() + r.indexVirtualBytes());
        }
        List<String> ev = new ArrayList<>();
        for (MmsArenaSim.Event e : r.events()) {
            ev.add(e.toString());
        }
        m.put("events", ev);
        return m;
    }

    private static Map<String, Object> policy(VramArenaPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vertexInitialBytes", p.vertexInitialBytes());
        m.put("indexInitialBytes", p.indexInitialBytes());
        m.put("growthFactor", p.growthFactor());
        m.put("growthReserve", p.growthReserve());
        m.put("alignElements", p.alignElements());
        m.put("trimFraction", p.trimFraction());
        m.put("sparseGrowth", p.sparseGrowth());
        return m;
    }

    // ─── Report sections ──────────────────────────────────────────────────

    private Map<String, Object> environment(boolean featuresRan, PlanInfo plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        m.put("cendaAvailable", CendaKernels.isAvailable());
        m.put("cendaSimd", CendaKernels.isAvailable() ? CendaKernels.simdLevel() : null);
        m.put("noiseBackend", TerrainNoise.backend().name());
        m.put("terrainGenMode", TerrainGenStats.modeSummary());
        m.put("mesherBackend", CendaMesher.enabled() ? "native" : "java");
        m.put("greedyMeshing", System.getProperty("stonebreak.mesher.greedy", "on"));
        m.put("vertexFormat", MmsVertexFormat.active().name().toLowerCase());
        m.put("vertexStrideBytes", MmsVertexFormat.active().stride());
        m.put("featuresPopulated", featuresRan);
        m.put("cearlPlan", plan.source());
        VramPool pool = plan.plan().pool(VramPlans.POOL_CHUNK_MESH);
        m.put("chunkMeshPoolBudgetBytes", pool == null ? null : pool.budgetBytes());
        m.put("systemProperties", systemProps());
        return m;
    }

    private static Map<String, String> systemProps() {
        Map<String, String> m = new TreeMap<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("stonebreak.") || k.startsWith("lab.") || k.startsWith("cenda.")) {
                m.put(k, System.getProperty(k));
            }
        }
        return m;
    }

    private static Map<String, Object> generation(List<ChunkSample> s, long bestNs) {
        long totalNs = 0, alloc = 0;
        for (ChunkSample c : s) {
            totalNs += c.genNanos();
            alloc += c.genAllocBytes();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunks", s.size());
        m.put("nanosPerChunk", totalNs / s.size());
        m.put("bestNanosCentre", bestNs);
        m.put("heapAllocBytesPerChunk", alloc / s.size());
        return m;
    }

    private static Map<String, Object> chunkRam(List<ChunkSample> s) {
        long block = 0, height = 0, water = 0;
        int uni = 0, byt = 0, sho = 0, nib = 0, maxPal = 0, cells = 0;
        for (ChunkSample c : s) {
            ChunkRamProbe.Result r = c.ram();
            block += r.blockStorageBytes();
            height += r.heightMapBytes();
            water += r.waterLayerBytes();
            uni += r.uniformSections();
            byt += r.byteSections();
            sho += r.shortSections();
            nib += r.nibbleSections();
            maxPal = Math.max(maxPal, r.maxPaletteSize());
            cells += r.waterCells();
        }
        int n = s.size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bytesPerChunk", (block + height + water) / n);
        m.put("blockStorageBytesPerChunk", block / n);
        m.put("heightMapBytesPerChunk", height / n);
        m.put("waterLayerBytesPerChunk", water / n);
        m.put("totalBytes", block + height + water);
        m.put("sectionsUniform", uni);
        m.put("sectionsByteTier", byt);
        m.put("sectionsShortTier", sho);
        m.put("sectionsNibbleTier", nib);
        m.put("maxPaletteSize", maxPal);
        m.put("waterCells", cells);
        return m;
    }

    private static Map<String, Object> mesh(List<ChunkSample> s, long bestNs, long quadsIn, long quadsOut) {
        long totalNs = 0, alloc = 0, av = 0, ai = 0, avb = 0, aib = 0, wv = 0, wi = 0, wvb = 0, wib = 0;
        long sv = 0, sb = 0;
        int aq = 0, aqc = 0, wq = 0, wqc = 0, sq = 0, sbo = 0, maxVerts = 0;
        for (ChunkSample c : s) {
            sv += c.stamp().vertices();
            sb += c.stamp().bytes();
            sq += c.stamp().quads();
            totalNs += c.meshNanos();
            alloc += c.meshAllocBytes();
            av += c.atlas().vertices();
            ai += c.atlas().indices();
            avb += c.atlas().vertexBytes();
            aib += c.atlas().indexBytes();
            aq += c.atlas().quads();
            aqc += c.atlas().conformantQuads();
            wv += c.water().vertices();
            wi += c.water().indices();
            wvb += c.water().vertexBytes();
            wib += c.water().indexBytes();
            wq += c.water().quads();
            wqc += c.water().conformantQuads();
            sbo += c.sboEntries();
            maxVerts = Math.max(maxVerts, c.atlas().vertices());
        }
        int n = s.size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunks", n);
        m.put("nanosPerChunk", totalNs / n);
        m.put("bestNanosCentre", bestNs);
        m.put("heapAllocBytesPerChunk", alloc / n);
        m.put("kernelQuads", quadsIn);
        m.put("greedyQuads", quadsOut);
        m.put("greedyRatio", quadsIn == 0 ? 1.0 : (double) quadsOut / quadsIn);
        Map<String, Object> atlas = new LinkedHashMap<>();
        atlas.put("vertices", av);
        atlas.put("indices", ai);
        atlas.put("vertexBytes", avb);
        atlas.put("indexBytes", aib);
        atlas.put("bytes", avb + aib);
        atlas.put("quads", aq);
        atlas.put("conformantQuads", aqc);
        atlas.put("maxVerticesPerChunk", maxVerts);
        m.put("atlas", atlas);
        Map<String, Object> water = new LinkedHashMap<>();
        water.put("vertices", wv);
        water.put("indices", wi);
        water.put("vertexBytes", wvb);
        water.put("indexBytes", wib);
        water.put("bytes", wvb + wib);
        water.put("quads", wq);
        water.put("conformantQuads", wqc);
        m.put("water", water);
        Map<String, Object> stamp = new LinkedHashMap<>();
        stamp.put("vertices", sv);
        stamp.put("bytes", sb);
        stamp.put("quads", sq);
        m.put("stamp", stamp);
        long total = avb + aib + wvb + wib + sb;
        m.put("totalBytes", total);
        m.put("bytesPerChunk", total / n);
        m.put("bytesPerQuad", (aq + wq + sq) == 0 ? 0 : (double) total / (aq + wq + sq));
        m.put("atlasBytesPerQuad", aq == 0 ? 0 : (double) (avb + aib) / aq);
        m.put("sboEntries", sbo);
        return m;
    }

    private static List<Map<String, Object>> perChunk(List<ChunkSample> s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChunkSample c : s) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cx", c.cx());
            m.put("cz", c.cz());
            m.put("genNanos", c.genNanos());
            m.put("meshNanos", c.meshNanos());
            m.put("ramBytes", c.ram().totalBytes());
            m.put("sections", c.ram().uniformSections() + "u/" + c.ram().nibbleSections() + "n/"
                + c.ram().byteSections() + "b/" + c.ram().shortSections() + "s");
            m.put("atlasVertices", c.atlas().vertices());
            m.put("atlasBytes", c.atlas().bytes());
            m.put("waterVertices", c.water().vertices());
            m.put("waterBytes", c.water().bytes());
            m.put("stampBytes", c.stamp().bytes());
            m.put("kernelQuads", c.kernelQuads());
            m.put("greedyQuads", c.greedyQuads());
            out.add(m);
        }
        return out;
    }

    // ─── Geometry of the tier ─────────────────────────────────────────────

    /** Measured chunks {@code [0,tier)²}, nearest-to-centre first (player-style load order). */
    static List<int[]> measuredOrder(int tier) {
        List<int[]> list = new ArrayList<>();
        for (int x = 0; x < tier; x++) {
            for (int z = 0; z < tier; z++) {
                list.add(new int[]{x, z});
            }
        }
        double c = (tier - 1) / 2.0;
        list.sort(Comparator.<int[]>comparingDouble(p -> (p[0] - c) * (p[0] - c) + (p[1] - c) * (p[1] - c))
            .thenComparingInt(p -> p[0]).thenComparingInt(p -> p[1]));
        return list;
    }

    static List<int[]> ringAround(int tier) {
        List<int[]> ring = new ArrayList<>();
        for (int x = -1; x <= tier; x++) {
            for (int z = -1; z <= tier; z++) {
                if (x < 0 || z < 0 || x >= tier || z >= tier) {
                    ring.add(new int[]{x, z});
                }
            }
        }
        return ring;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // ─── Stubs ────────────────────────────────────────────────────────────

    /**
     * Per-thread allocation counter via {@code com.sun.management.ThreadMXBean}
     * — reached reflectively because the test module doesn't read
     * {@code jdk.management}. Returns 0 deltas when unsupported.
     */
    static final class AllocMeter {
        private final Object bean = ManagementFactory.getThreadMXBean();
        private final java.lang.reflect.Method method;

        AllocMeter() {
            java.lang.reflect.Method m = null;
            try {
                m = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
            } catch (ReflectiveOperationException ignored) {
                // unsupported JVM — alloc deltas report 0
            }
            method = m;
        }

        long getCurrentThreadAllocatedBytes() {
            if (method == null) {
                return 0;
            }
            try {
                return (Long) method.invoke(bean);
            } catch (ReflectiveOperationException e) {
                return 0;
            }
        }
    }

    /**
     * Texture data with the same SHAPE the game's mapper produces — a unit-square
     * UV frame per face (identity orientation) — so pulled-quad encoding sees a
     * representable frame; layers/UV values themselves don't change byte counts.
     */
    static final class StubTextureMapper implements MmsTextureMapper {
        // Per-thread scratch, like MmsArrayTextureMapper — the harness must not
        // add allocation the production mapper doesn't have.
        private static final ThreadLocal<float[][]> SCRATCH = ThreadLocal.withInitial(() -> new float[][]{
            new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD],
            new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS],
            new float[MmsBufferLayout.VERTICES_PER_QUAD],
            new float[MmsBufferLayout.VERTICES_PER_CROSS],
            new float[MmsBufferLayout.VERTICES_PER_QUAD]});

        @Override
        public float[] generateFaceTextureCoordinates(IBlockType blockType, int face) {
            float[] uv = SCRATCH.get()[0];
            int ua = com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator.uAxis(face);
            int va = com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator.vAxis(face);
            for (int c = 0; c < 4; c++) {
                uv[c * 2] = com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator.cornerOffset(face, c, ua);
                uv[c * 2 + 1] = com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator.cornerOffset(face, c, va);
            }
            return uv;
        }

        @Override
        public float[] generateCrossTextureCoordinates(IBlockType blockType) {
            return SCRATCH.get()[1];
        }

        @Override
        public float[] generateFaceLayers(IBlockType blockType, int face) {
            return SCRATCH.get()[2];
        }

        @Override
        public float[] generateCrossLayers(IBlockType blockType) {
            return SCRATCH.get()[3];
        }

        @Override
        public float[] generateAlphaFlags(IBlockType blockType) {
            return SCRATCH.get()[4];
        }

        @Override
        public boolean requiresAlphaTesting(IBlockType blockType) {
            return false;
        }
    }

    /** CcoChunkData over a Chunk (the production wrapper is private to MmsAPI). */
    static final class ChunkDataView implements CcoChunkData {
        private final Chunk chunk;

        ChunkDataView(Chunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public IBlockType getBlock(int x, int y, int z) {
            return chunk.getBlock(x, y, z);
        }

        @Override
        public CcoBlockStorage backingStorage() {
            return chunk.getBlockStorageView();
        }

        @Override
        public boolean isInBounds(int x, int y, int z) {
            return x >= 0 && x < CHUNK && y >= 0 && y < WORLD_HEIGHT && z >= 0 && z < CHUNK;
        }

        @Override
        public CcoChunkMetadata getMetadata() {
            return chunk.getCcoMetadata();
        }

        @Override
        public int getChunkX() {
            return chunk.getChunkX();
        }

        @Override
        public int getChunkZ() {
            return chunk.getChunkZ();
        }

        @Override
        public String getBlockState(int x, int y, int z) {
            return chunk.getBlockState(x, y, z);
        }

        @Override
        public int getHighestNonAirY() {
            return chunk.getHighestNonAirY();
        }
    }
}
