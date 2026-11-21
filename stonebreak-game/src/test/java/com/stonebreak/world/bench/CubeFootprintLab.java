package com.stonebreak.world.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsRegion.MmsArenaSim;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionKey;
import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.openmason.engine.voxel.world.StreamCenter;
import com.openmason.engine.voxel.world.StreamMesh;
import com.openmason.engine.voxel.world.StreamStats;
import com.openmason.engine.vram.VramArenaPolicy;
import com.openmason.engine.vram.VramPlans;
import com.stonebreak.rendering.vram.CearlBootstrap;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.operations.WorldConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cube footprint lab (rebuilt for the cubic runtime, 3B-R): streams a square of cube columns around a viewer
 * through the REAL authoritative pipeline - native generator, population, mesher ({@code cv_gpu_mesh}) -
 * headless, and measures what one cube costs:
 * <ul>
 *   <li>stream throughput: wall time until every column of the square is READY, cubes generated / meshed per
 *       second (workers run in parallel, so these are pipeline numbers, not single-cube latencies);</li>
 *   <li>RAM per cube: {@code cv_cube_encode} bytes (the paletted record; a lower bound of the resident
 *       cost) - avg / p50 / p95 / max over installed cubes, uniform cubes counted;</li>
 *   <li>mesh bytes per cube and quads per layer (QUAD16 opaque/cutout/translucent, WATERQUAD16, COMPACT20
 *       vertices + u32 indices);</li>
 *   <li>planned VRAM: every mesh replayed into {@link MmsArenaSim}s keyed like the renderer's 3D regions
 *       ({@link MmsRegionKey}, 8^3 cubes) under the shipped CEARL plan - reserved vs used bytes per layer.</li>
 * </ul>
 * Ledger: {@code <lab.out>/cubes/r<radius>/<label>.json}; {@code Testing/chunk-lab.sh} runs it and diffs it
 * against the radius' baseline. Properties: {@code lab.tier} = radius in cubes (1, 3, 8; default 3),
 * {@code lab.label}, {@code lab.seed}, {@code lab.out}, {@code lab.scale} (ONE_TO_FOUR | ONE_TO_TWO),
 * {@code lab.timeoutSeconds} (default 180).
 */
public final class CubeFootprintLab {

    private final int radiusCubes;
    private final String label;
    private final long seed;
    private final Path outDir;
    private final TerrainScalePreset scale;
    private final long timeoutNanos;

    /** Per-cube mesh record (latest mesh of each cube). */
    private record MeshRecord(int opaque, int cutout, int translucent, int water, int stampVertices,
                              int stampIndices, long bytes) {
    }

    /** Planned VRAM per region and layer: an arena sim + the handle of each cube living in it. */
    private static final class RegionSims {
        final Map<MmsRegionKey, MmsArenaSim> sims = new HashMap<>();
        final Map<CubePos, MmsArenaSim.Handle> handles = new HashMap<>();
    }

    private final Map<CubePos, MeshRecord> meshes = new HashMap<>();
    private final RegionSims[] sims = {new RegionSims(), new RegionSims(), new RegionSims(), new RegionSims()};
    private final String[] layerNames = {"solid", "translucent", "stamp", "water"};
    private VramArenaPolicy[] policies;
    private int emptyMeshes;
    private int meshEvents;

    public CubeFootprintLab(int radiusCubes, String label, long seed, Path outDir, TerrainScalePreset scale,
                            long timeoutSeconds) {
        this.radiusCubes = radiusCubes;
        this.label = label;
        this.seed = seed;
        this.outDir = outDir;
        this.scale = scale;
        this.timeoutNanos = timeoutSeconds * 1_000_000_000L;
    }

    public static CubeFootprintLab fromSystemProperties() {
        int tier = Integer.getInteger("lab.tier", 3);
        String out = System.getProperty("lab.out", "");
        Path outDir = out.isEmpty()
            ? LabConfig.repoRoot().resolve("Dev Working").resolve("bench").resolve("chunk-lab")
            : Path.of(out);
        return new CubeFootprintLab(tier, System.getProperty("lab.label", "run"),
            Long.getLong("lab.seed", LabConfig.DEFAULT_SEED), outDir,
            TerrainScalePreset.valueOf(System.getProperty("lab.scale", "ONE_TO_FOUR")),
            Long.getLong("lab.timeoutSeconds", 180L));
    }

    /** Runs the lab and returns the ledger path. */
    public Path run() throws Exception {
        CearlBootstrap.installHeadless();
        policies = new VramArenaPolicy[] {
            VramPlans.arena(VramPlans.POOL_CHUNK_MESH), VramPlans.arena(VramPlans.POOL_CHUNK_MESH),
            VramPlans.arena(VramPlans.POOL_CHUNK_STAMP), VramPlans.arena(VramPlans.POOL_CHUNK_WATER)};

        int renderDistanceChunks = Math.max(1, radiusCubes * 2);
        WorldConfiguration config = new WorldConfiguration(renderDistanceChunks,
            Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        World world = World.createHeadless(config, seed, TerrainGeneratorType.STANDARD, scale, null, true);
        Map<String, Object> ledger = new LinkedHashMap<>();
        try {
            world.addMeshConsumer((w, mesh) -> onMesh(mesh));
            long x = 16, z = 16;
            long top = world.topY(x, z);
            int radiusBlocks = radiusCubes * 32;
            world.setStreamCenters(List.of(new StreamCenter(x, top, z, radiusBlocks,
                config.getVerticalRenderDistance(), World.BAND_BELOW, World.BAND_ABOVE)));

            long t0 = System.nanoTime();
            long deadline = t0 + timeoutNanos;
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                world.tickStream();
                if (allReady(world, x, z)) {
                    StreamStats s = world.streamStats();
                    if (s.workIdle()) {
                        ready = true;
                        break;
                    }
                }
                Thread.sleep(1);
            }
            long wallNanos = System.nanoTime() - t0;
            StreamStats stats = world.streamStats();

            ledger.put("label", label);
            ledger.put("tier", radiusCubes);
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("seed", seed);
            env.put("scale", scale.name());
            env.put("radiusCubes", radiusCubes);
            env.put("workers", config.getChunkBuildThreads());
            env.put("cearlPlan", VramPlans.active().name());
            env.put("allColumnsReady", ready);
            ledger.put("environment", env);

            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("wallNanos", wallNanos);
            stream.put("cubesLoaded", stats.cubesLoaded());
            stream.put("generatedTotal", stats.generatedTotal());
            stream.put("populatedTotal", stats.populatedTotal());
            stream.put("meshedTotal", stats.meshedTotal());
            stream.put("columnsReady", stats.columnsReady());
            stream.put("generatedPerSecond", stats.generatedTotal() * 1e9 / Math.max(1, wallNanos));
            stream.put("meshedPerSecond", stats.meshedTotal() * 1e9 / Math.max(1, wallNanos));
            stream.put("tickUsMax", stats.tickUsMax());
            ledger.put("stream", stream);

            ledger.put("cubeRam", cubeRam(world));
            ledger.put("mesh", meshSummary());
            ledger.put("vram", vramSummary());
        } finally {
            world.cleanup();
        }

        Path dir = outDir.resolve("cubes").resolve("r" + radiusCubes);
        Files.createDirectories(dir);
        Path file = dir.resolve(label + ".json");
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), ledger);
        System.out.println("[chunk-lab] ledger " + file);
        return file;
    }

    private boolean allReady(World world, long x, long z) {
        long cx = x >> 5, cz = z >> 5;
        for (long dx = -radiusCubes + 1; dx < radiusCubes; dx++) {
            for (long dz = -radiusCubes + 1; dz < radiusCubes; dz++) {
                if (dx * dx + dz * dz >= (long) radiusCubes * radiusCubes) {
                    continue; // the stream's disc, not the square's corners
                }
                if (!world.isColumnReady(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void onMesh(StreamMesh mesh) {
        meshEvents++;
        CubePos pos = new CubePos(mesh.cx(), mesh.cy(), mesh.cz());
        for (int layer = 0; layer < sims.length; layer++) {
            MmsArenaSim.Handle old = sims[layer].handles.remove(pos);
            if (old != null) {
                sims[layer].sims.get(MmsRegionKey.ofCube(pos.x(), pos.y(), pos.z())).free(old);
            }
        }
        if (mesh.isEmpty()) {
            emptyMeshes++;
            meshes.remove(pos);
            return;
        }
        MeshRecord r = new MeshRecord(mesh.quadCount(StreamMesh.OPAQUE), mesh.quadCount(StreamMesh.CUTOUT),
            mesh.quadCount(StreamMesh.TRANSLUCENT), mesh.waterCount(), mesh.stampVertexCount(),
            mesh.stampIndexCount(), mesh.byteSize());
        meshes.put(pos, r);
        MmsRegionKey key = MmsRegionKey.ofCube(pos.x(), pos.y(), pos.z());
        // Pulled formats: 4 elements of 4 bytes per 16-byte record, no index arena (shared quad EBO).
        simUpload(0, key, pos, (r.opaque + r.cutout) * 4, 0);
        simUpload(1, key, pos, r.translucent * 4, 0);
        simUpload(2, key, pos, r.stampVertices, r.stampIndices);
        simUpload(3, key, pos, r.water * 4, 0);
    }

    private void simUpload(int layer, MmsRegionKey key, CubePos pos, int vertices, int indices) {
        if (vertices <= 0) {
            return;
        }
        RegionSims s = sims[layer];
        MmsArenaSim sim = s.sims.computeIfAbsent(key, k -> layer == 2
            ? new MmsArenaSim(policies[layer], MmsVertexFormat.COMPACT20.stride(), Integer.BYTES,
                policies[layer].sparseGrowth(), 65536)
            : new MmsArenaSim(policies[layer], MmsVertexFormat.QUAD16.stride(), 0,
                policies[layer].sparseGrowth(), 65536));
        s.handles.put(pos, sim.upload(vertices, indices));
    }

    private static Map<String, Object> cubeRam(World world) {
        CubeWorld voxels = world.voxels();
        List<Long> sizes = new ArrayList<>();
        int uniform = 0;
        for (var cube : world.getLoadedCubes()) {
            byte[] bytes = voxels.encode(cube.cx(), cube.cy(), cube.cz(), CubeWorld.PART_BLOCKS);
            if (bytes == null) {
                continue;
            }
            sizes.add((long) bytes.length);
            if (bytes.length <= 64) {
                uniform++;
            }
        }
        Map<String, Object> m = stats(sizes, "encodeBytes");
        m.put("cubes", sizes.size());
        m.put("nearUniformCubes", uniform);
        m.put("worldCubeCount", voxels.cubeCount());
        return m;
    }

    private Map<String, Object> meshSummary() {
        List<Long> bytes = new ArrayList<>();
        long opaque = 0, cutout = 0, translucent = 0, water = 0, stampV = 0, stampI = 0, total = 0;
        for (MeshRecord r : meshes.values()) {
            bytes.add(r.bytes);
            opaque += r.opaque;
            cutout += r.cutout;
            translucent += r.translucent;
            water += r.water;
            stampV += r.stampVertices;
            stampI += r.stampIndices;
            total += r.bytes;
        }
        Map<String, Object> m = stats(bytes, "bytesPerCube");
        m.put("meshedCubes", meshes.size());
        m.put("meshEvents", meshEvents);
        m.put("emptyMeshes", emptyMeshes);
        m.put("totalBytes", total);
        m.put("opaqueQuads", opaque);
        m.put("cutoutQuads", cutout);
        m.put("translucentQuads", translucent);
        m.put("waterQuads", water);
        m.put("stampVertices", stampV);
        m.put("stampIndices", stampI);
        return m;
    }

    private Map<String, Object> vramSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        long reserved = 0, used = 0;
        int regions = 0;
        for (int layer = 0; layer < sims.length; layer++) {
            long r = 0, u = 0;
            for (MmsArenaSim sim : sims[layer].sims.values()) {
                MmsArenaSim.Report rep = sim.report();
                r += rep.reservedBytes();
                u += rep.usedBytes();
            }
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("regions", sims[layer].sims.size());
            l.put("reservedBytes", r);
            l.put("usedBytes", u);
            l.put("utilization", r == 0 ? 1.0 : (double) u / r);
            m.put(layerNames[layer], l);
            reserved += r;
            used += u;
            regions += sims[layer].sims.size();
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("regions", regions);
        totals.put("reservedBytes", reserved);
        totals.put("usedBytes", used);
        totals.put("slackBytes", reserved - used);
        totals.put("reservedBytesPerMeshedCube", meshes.isEmpty() ? 0 : reserved / meshes.size());
        m.put("totals", totals);
        return m;
    }

    private static Map<String, Object> stats(List<Long> values, String prefix) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (values.isEmpty()) {
            m.put(prefix + "Avg", 0);
            return m;
        }
        values.sort(Long::compare);
        long sum = 0;
        for (long v : values) sum += v;
        m.put(prefix + "Avg", sum / values.size());
        m.put(prefix + "P50", values.get(values.size() / 2));
        m.put(prefix + "P95", values.get(Math.min(values.size() - 1, (int) (values.size() * 0.95))));
        m.put(prefix + "Max", values.getLast());
        return m;
    }

    public static void main(String[] args) throws Exception {
        fromSystemProperties().run();
    }
}
