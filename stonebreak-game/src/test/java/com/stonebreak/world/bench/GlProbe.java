package com.stonebreak.world.bench;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsRegion.MmsArenaSim;
import com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.openmason.engine.vram.VramPlans;
import com.stonebreak.core.window.DisplayBackend;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;
import com.stonebreak.rendering.vram.CearlBootstrap;
import com.stonebreak.world.chunk.Chunk;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The lab's real-GL arm: opens a hidden GLFW window (the {@code GameWindow}
 * recipe minus {@code glfwShowWindow}), installs the CEARL plan against the
 * detected VRAM, uploads every measured mesh through the production region
 * arenas ({@link ChunkRegionRenderer#upload}), and reports what the engine
 * tracked ({@link GpuMemoryTracker}) next to what the driver says it lost
 * (NVX/ATI free-memory queries) and what {@link MmsArenaSim} predicted for the
 * same upload order, real growth mode and real page size. Planned vs tracked
 * must agree byte-for-byte — a mismatch is a harness bug, not a result.
 *
 * <p>Must run on the main thread (GLFW). When no display or GL context is
 * available the section records why and the headless numbers stand alone.
 */
final class GlProbe {

    private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    private static final int TEXTURE_FREE_MEMORY_ATI = 0x87FC;

    private GlProbe() {
    }

    static Map<String, Object> measure(List<ChunkFootprintLab.ChunkSample> samples,
                                       Map<Long, Chunk> chunks,
                                       Function<Chunk, ChunkMeshResult> mesher,
                                       ChunkFootprintLab.PlanInfo plan,
                                       List<String> notes) {
        Map<String, Object> out = new LinkedHashMap<>();
        long window = 0;
        boolean glfw = false;
        try {
            DisplayBackend.initialize();
            glfw = true;
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
            window = GLFW.glfwCreateWindow(64, 64, "chunk-lab", 0, 0);
            if (window == 0) {
                throw new IllegalStateException("glfwCreateWindow failed");
            }
            GLFW.glfwMakeContextCurrent(window);
            GLCapabilities caps = GL.createCapabilities();
            out.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            out.put("glVersion", GL11.glGetString(GL11.GL_VERSION));

            // Same plan the game would run with (detected VRAM), honouring -Dlab.cearl.
            if (System.getProperty("lab.cearl") != null && System.getProperty("stonebreak.cearl") == null) {
                System.setProperty("stonebreak.cearl", System.getProperty("lab.cearl"));
            }
            CearlBootstrap.install();
            out.put("regionsEnabled", ChunkRegionRenderer.isEnabled());
            out.put("gpuCullEnabled", ChunkRegionRenderer.isGpuCullEnabled());
            out.put("sparseExtension", caps.GL_ARB_sparse_buffer);
            out.put("stagingRing", caps.OpenGL44 || caps.GL_ARB_buffer_storage);

            GpuMemoryTracker tracker = GpuMemoryTracker.getInstance();
            long chunkMesh0 = tracker.getBytes(GpuMemoryTracker.Category.CHUNK_MESH);
            long other0 = tracker.getBytes(GpuMemoryTracker.Category.OTHER);
            long idle0 = tracker.getBytes(GpuMemoryTracker.Category.BUFFER_POOL_IDLE);
            GL11.glFinish();
            long driverFree0 = driverFreeBytes(caps);

            List<AutoCloseable> handles = new ArrayList<>();
            Map<Long, MmsChunkRegion> regionsSeen = new TreeMap<>();
            long uploadNs = 0;
            int regionUploads = 0, legacyUploads = 0;
            for (ChunkFootprintLab.ChunkSample s : samples) {
                Chunk chunk = chunks.get(ChunkFootprintLab.key(s.cx(), s.cz()));
                ChunkMeshResult result = mesher.apply(chunk);
                for (int layer = 0; layer < 3; layer++) {
                    MmsMeshData mesh = layer == 0 ? result.atlasMesh()
                        : layer == 1 ? result.waterMesh() : result.stampMesh();
                    if (mesh == null || mesh.isEmpty()) {
                        continue;
                    }
                    long t0 = System.nanoTime();
                    MmsRegionMeshHandle rh = ChunkRegionRenderer.isEnabled()
                        ? ChunkRegionRenderer.getInstance().upload(layer, s.cx(), s.cz(), mesh) : null;
                    if (rh != null) {
                        handles.add(rh::close);
                        regionsSeen.putIfAbsent(
                            ChunkFootprintLab.key(s.cx() >> MmsChunkRegion.REGION_SHIFT,
                                s.cz() >> MmsChunkRegion.REGION_SHIFT) * 3 + layer, rh.region());
                        regionUploads++;
                    } else {
                        MmsRenderableHandle h = MmsRenderableHandle.upload(mesh, false);
                        handles.add(h::close);
                        legacyUploads++;
                    }
                    uploadNs += System.nanoTime() - t0;
                }
            }
            GL11.glFinish();
            long driverFree1 = driverFreeBytes(caps);
            long chunkMeshDelta = tracker.getBytes(GpuMemoryTracker.Category.CHUNK_MESH) - chunkMesh0;
            long otherDelta = tracker.getBytes(GpuMemoryTracker.Category.OTHER) - other0;
            long idleDelta = tracker.getBytes(GpuMemoryTracker.Category.BUFFER_POOL_IDLE) - idle0;

            out.put("regionUploads", regionUploads);
            out.put("legacyUploads", legacyUploads);
            out.put("uploadNanosTotal", uploadNs);
            out.put("trackedChunkMeshBytes", chunkMeshDelta);
            out.put("trackedStagingRingBytes", otherDelta);
            out.put("trackedBufferPoolIdleBytes", idleDelta);
            out.put("driverFreeBeforeBytes", driverFree0);
            out.put("driverFreeAfterBytes", driverFree1);
            out.put("driverDeltaBytes", driverFree0 < 0 || driverFree1 < 0 ? null : driverFree0 - driverFree1);

            // Replay the sim with the real mode + page size and compare to the real regions.
            List<Map<String, Object>> regionRows = new ArrayList<>();
            long plannedTotal = 0, realTotal = 0;
            boolean allMatch = true;
            for (Map.Entry<Long, MmsChunkRegion> e : regionsSeen.entrySet()) {
                long rk = Math.floorDiv(e.getKey(), 3);
                int layer = (int) Math.floorMod(e.getKey(), 3);
                MmsChunkRegion region = e.getValue();
                MmsArenaSim sim = new MmsArenaSim(VramPlans.arena(layer == 0 ? VramPlans.POOL_CHUNK_MESH
                        : layer == 1 ? VramPlans.POOL_CHUNK_WATER : VramPlans.POOL_CHUNK_STAMP),
                    region.vertexStride(), region.format().indexStride(), region.isSparse(),
                    region.isSparse() ? region.pageSize() : 65536);
                for (ChunkFootprintLab.ChunkSample s : samples) {
                    if (ChunkFootprintLab.key(s.cx() >> MmsChunkRegion.REGION_SHIFT,
                            s.cz() >> MmsChunkRegion.REGION_SHIFT) != rk) {
                        continue;
                    }
                    ChunkFootprintLab.MeshStats m = layer == 0 ? s.atlas() : layer == 1 ? s.water() : s.stamp();
                    if (m.vertices() > 0) {
                        sim.upload(m.vertices(), m.indices());
                    }
                }
                long planned = sim.report().reservedBytes();
                long real = region.capacityBytes();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("regionX", (int) (rk >> 32));
                row.put("regionZ", (int) rk);
                row.put("layer", layer == 0 ? "atlas" : layer == 1 ? "water" : "stamp");
                row.put("sparse", region.isSparse());
                row.put("plannedBytes", planned);
                row.put("realBytes", real);
                row.put("match", planned == real);
                regionRows.add(row);
                plannedTotal += planned;
                realTotal += real;
                allMatch &= planned == real;
            }
            out.put("regions", regionRows);
            out.put("plannedRegionBytes", plannedTotal);
            out.put("realRegionBytes", realTotal);
            out.put("plannedMatchesReal", allMatch);
            if (!allMatch) {
                notes.add("MmsArenaSim disagrees with the real region arenas — fix the sim before iterating");
            }

            for (AutoCloseable h : handles) {
                try {
                    h.close();
                } catch (Exception ignored) {
                    // best effort teardown
                }
            }
            GL11.glFinish();
        } catch (Throwable t) {
            out.put("unavailable", t.toString());
            notes.add("GL arm unavailable: " + t);
        } finally {
            if (window != 0) {
                GLFW.glfwDestroyWindow(window);
            }
            if (glfw) {
                GLFW.glfwTerminate();
            }
        }
        return out;
    }

    /** Driver-reported free VRAM in bytes, or -1 when no vendor query exists. */
    private static long driverFreeBytes(GLCapabilities caps) {
        try {
            if (caps.GL_NVX_gpu_memory_info) {
                return GL11.glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) * 1024L;
            }
            if (caps.GL_ATI_meminfo) {
                return GL11.glGetInteger(TEXTURE_FREE_MEMORY_ATI) * 1024L;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return -1;
    }
}
