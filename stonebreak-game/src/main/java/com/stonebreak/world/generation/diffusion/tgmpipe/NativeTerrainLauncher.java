package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.TerrainScale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Starts the terrain pipeline in-process: the native engine ({@code libstonebreak_terrain} over the Cenda inference
 * runtime) on the model's release export ({@code <model>/native}: manifest.json + .cim networks). The export is part
 * of the model release (written by {@code python -m terrain_slm.export.native_release} when the release is made and
 * stored with Git LFS); the game never runs Python. Kernels compile with the pinned NVRTC ({@link NvrtcTool}) into the
 * content-addressed kernel cache. There is no other backend: a start that fails says why.
 */
final class NativeTerrainLauncher {

    private static final Logger LOG = Logger.getLogger(NativeTerrainLauncher.class.getName());

    private NativeTerrainLauncher() {
    }

    /** The model's release export directory ({@code -Dstonebreak.terrain.nativeDir} overrides). */
    static Path nativeDir() {
        String set = System.getProperty("stonebreak.terrain.nativeDir");
        if (set != null && !set.isBlank()) {
            return Path.of(set);
        }
        Path model = Path.of(TGMPipe.modelPath());
        Path dir = model.isAbsolute() ? model : TGMPipe.slmDir().resolve(model);
        return dir.resolve("native");
    }

    /** The engine opened on the model's export (before lanes and warm-up); the caller closes {@code terrain}. */
    record Opened(NativeTerrain terrain, String exportKey, int device) {
    }

    /**
     * Opens the engine on the current model's release export with the pinned CUDA compiler: loads the networks onto
     * the GPU, nothing compiled yet. Throws {@link TGMPipeException} with the reason when it cannot.
     */
    static Opened open() {
        String why = NativeTerrain.unavailableReason();
        if (why != null) {
            throw new TGMPipeException("native terrain unavailable: " + why);
        }
        TerrainModels.requireInstalled(TGMPipe.modelPath());   // missing export / Git LFS pointers: a clear error
        Path cenda = cendaLibrary();
        Path dir = nativeDir();
        String exportKey;
        try {
            exportKey = new ObjectMapper().readTree(dir.resolve("manifest.json").toFile()).path("export_key").asText("");
        } catch (IOException e) {
            throw new TGMPipeException("unreadable native manifest in " + dir, e);
        }
        Path nvrtc = NvrtcTool.locate(TGMPipe.toolsDir(), null, (text, fraction) -> { });
        int device = device();
        long hostCache = hostCacheBytes();
        long poolKeep = poolHostKeepBytes();
        LOG.info(() -> String.format("native terrain host memory: window caches %d MiB, pinned pool reserve %d MiB per lane",
                hostCache >> 20, poolKeep >> 20));
        try {
            return new Opened(NativeTerrain.open(cenda, dir, device, TGMPipe.kernelCacheDir(), nvrtc, hostCache, poolKeep),
                    exportKey, device);
        } catch (RuntimeException e) {
            throw new TGMPipeException("native terrain failed to open: " + e.getMessage(), e);
        }
    }

    /**
     * Starts the in-process pipeline: opens and warms the engine, registers the handshake's worlds. Throws
     * {@link TGMPipeException} with the reason when it cannot.
     */
    static NativeTerrainService start() {
        long t0 = System.nanoTime();
        Opened o = open();
        NativeTerrain terrain = o.terrain();
        try {
            terrain.setLanes(NativeTerrainService.configuredLanes());   // before the warm-up (models: a context per lane)
            terrain.warmup();
            List<String> worlds = worlds();
            Path cacheRoot = TGMPipe.modelsDir().resolve("tile_cache");
            long bytes = parseBytes(System.getProperty("stonebreak.tgmpipe.diskCache", "8G"));
            NativeTerrainService s = new NativeTerrainService(terrain, worlds, cacheRoot, bytes, o.exportKey());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.info(() -> "native terrain ready in " + ms + " ms on cuda:" + o.device() + ": " + terrain.info());
            return s;
        } catch (RuntimeException e) {
            terrain.close();
            throw e instanceof TGMPipeException t ? t : new TGMPipeException("native terrain failed to start: " + e.getMessage(), e);
        }
    }

    /**
     * Host-memory budget of the pipeline's window caches (pinned spills + host-resident windows, every lane and climate
     * layout): {@code -Dstonebreak.terrain.hostCacheMb} (the old {@code stonebreak.tgmpipe.pinnedMb} is read too), else
     * {@link #defaultHostCacheBytes} of this machine's RAM. Before 2026-11-20 there was no limit (about 1.7 GiB per
     * climate layout, never given back). Evicted windows are recomputed bit for bit when needed again.
     */
    static long hostCacheBytes() {
        long mb = megabytes("stonebreak.terrain.hostCacheMb", megabytes("stonebreak.tgmpipe.pinnedMb", -1));
        if (mb >= 0) {
            return mb << 20;
        }
        DaedalusRequirements.SystemSpecs specs = DaedalusRequirements.check().specs();
        return defaultHostCacheBytes(specs == null ? -1 : specs.ramMiB());
    }

    /** RAM / 32, between 512 MiB and 2 GiB (512 MiB when RAM is unknown): 1 GiB on a 32 GB machine. */
    static long defaultHostCacheBytes(long ramMiB) {
        long min = 512L << 20, max = 2048L << 20;
        if (ramMiB <= 0) {
            return min;
        }
        return Math.max(min, Math.min(max, (ramMiB << 20) / 32));
    }

    /** Pinned blocks each lane's pool keeps for reuse: {@code -Dstonebreak.terrain.poolHostKeepMb}, default 128 MiB. */
    static long poolHostKeepBytes() {
        return Math.max(1, megabytes("stonebreak.terrain.poolHostKeepMb", 128)) << 20;
    }

    private static long megabytes(String property, long fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            LOG.warning(() -> "ignoring -D" + property + "=" + v + " (not a number of MiB)");
            return fallback;
        }
    }

    /** libcenda_kernels with the inference runtime: {@code -Dstonebreak.terrain.cendaLib}, else the engine's own. */
    static Path cendaLibrary() {
        String set = System.getProperty("stonebreak.terrain.cendaLib");
        if (set != null && !set.isBlank()) {
            return Path.of(set);
        }
        return CendaKernels.libraryPath()
                .orElseThrow(() -> new TGMPipeException("the Cenda engine library is not loaded"));
    }

    /** {@code stonebreak.tgmpipe.device}: "cuda:N" = N; "auto"/"cuda" = cuda:1 on a two-GPU
     *  machine, TERRAIN_SLM_DEVICE overrides). */
    static int device() {
        String d = System.getProperty("stonebreak.tgmpipe.device", "auto").trim();
        if (d.equals("auto") || d.equals("cuda")) {
            String env = System.getenv("TERRAIN_SLM_DEVICE");
            d = env != null && !env.isBlank() ? env : (gpuCount() > 1 ? "cuda:1" : "cuda:0");
        }
        if (d.startsWith("cuda:")) {
            return Integer.parseInt(d.substring(5));
        }
        throw new TGMPipeException("native terrain needs a CUDA device, got " + d);
    }

    private static int gpuCount() {
        int n = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of("/proc/driver/nvidia/gpus"))) {
            for (Path ignored : s) {
                n++;
            }
        } catch (IOException | RuntimeException e) {
            return 1;
        }
        return Math.max(n, 1);
    }

    /** The handshake's worlds as JSON texts, in {@code TerrainScale.worldIndex} order. */
    static List<String> worlds() {
        try {
            ObjectMapper om = new ObjectMapper();
            JsonNode all = om.readTree(TerrainScale.handshakeJson()).get("worlds");
            List<String> out = new ArrayList<>();
            for (JsonNode w : all) {
                out.add(om.writeValueAsString(w));
            }
            return out;
        } catch (IOException e) {
            throw new TGMPipeException("bad handshake json", e);
        }
    }

    static long parseBytes(String s) {
        String t = s.trim().toUpperCase();
        long unit = switch (t.isEmpty() ? ' ' : t.charAt(t.length() - 1)) {
            case 'K' -> 1L << 10;
            case 'M' -> 1L << 20;
            case 'G' -> 1L << 30;
            default -> 1L;
        };
        String num = unit == 1 ? t : t.substring(0, t.length() - 1);
        return (long) (Double.parseDouble(num) * unit);
    }
}
