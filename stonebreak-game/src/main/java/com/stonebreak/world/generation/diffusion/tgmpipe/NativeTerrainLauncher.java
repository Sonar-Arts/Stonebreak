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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Chooses and starts the terrain backend's native half (native engine phase 3). {@code -Dstonebreak.terrain.backend}:
 * <ul>
 *   <li>{@code python} — the TGMPipe child process only (the explicit fallback, kept until the user approves
 *       removing it);</li>
 *   <li>{@code native} — in-process only: a native start failure fails the start;</li>
 *   <li>{@code auto} (default; parity proven 2026-11-19: whole tiles bit-identical to the Python generator on the
 *       engine path, all 18 handshake worlds) — in-process when the engine library carries the inference runtime, libstonebreak_terrain
 *       is present and the model's release export can be had; otherwise the TGMPipe child, with the reason logged.</li>
 * </ul>
 * The release export ({@code <model>/native}: manifest.json + .cim networks) is written by the model's Python
 * ({@code python -m terrain_slm.export.native_release}) when it is missing or stale and the Python environment exists;
 * a machine without Python uses an export that ships with the model.
 */
final class NativeTerrainLauncher {

    private static final Logger LOG = Logger.getLogger(NativeTerrainLauncher.class.getName());
    private static final String RELEASE_MODULE = "terrain_slm.export.native_release";
    static final String DEFAULT_MODE = "auto";

    private NativeTerrainLauncher() {
    }

    static String mode() {
        return System.getProperty("stonebreak.terrain.backend", DEFAULT_MODE).trim().toLowerCase();
    }

    static boolean wanted() {
        return !mode().equals("python");
    }

    static boolean forced() {
        return mode().equals("native");
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

    /**
     * Starts the in-process pipeline: refreshes the release export if the model's Python is there, opens and warms the
     * engine, registers the handshake's worlds. Throws {@link TGMPipeException} with the reason when it cannot.
     */
    static NativeTerrainService start(Path logFile) {
        String why = NativeTerrain.unavailableReason();
        if (why != null) {
            throw new TGMPipeException("native terrain unavailable: " + why);
        }
        // Only EXP-2 models are ported (their folders carry the climate node); a world made with v4 (FX1: saves
        // without a model id) keeps the TGMPipe service.
        Path model = Path.of(TGMPipe.modelPath());
        Path modelDir = model.isAbsolute() ? model : TGMPipe.slmDir().resolve(model);
        if (!Files.isRegularFile(modelDir.resolve("climate.pt")) && !Files.isRegularFile(nativeDir().resolve("manifest.json"))) {
            throw new TGMPipeException("model " + model + " is not an EXP-2 model: the native pipeline does not serve it");
        }
        Path cenda = cendaLibrary();
        Path dir = nativeDir();
        refreshExport(dir, logFile);
        if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
            throw new TGMPipeException("no native release export at " + dir
                    + " (python -m " + RELEASE_MODULE + " " + TGMPipe.modelPath() + ")");
        }
        String exportKey;
        try {
            exportKey = new ObjectMapper().readTree(dir.resolve("manifest.json").toFile()).path("export_key").asText("");
        } catch (IOException e) {
            throw new TGMPipeException("unreadable native manifest in " + dir, e);
        }
        int device = device();
        long t0 = System.nanoTime();
        NativeTerrain terrain;
        try {
            terrain = NativeTerrain.open(cenda, dir, device, TGMPipe.kernelCacheDir(), torchNvrtc());
        } catch (RuntimeException e) {
            throw new TGMPipeException("native terrain failed to open: " + e.getMessage(), e);
        }
        try {
            terrain.warmup();
            List<String> worlds = worlds();
            Path cacheRoot = TGMPipe.modelsDir().resolve("tile_cache");
            long bytes = parseBytes(System.getProperty("stonebreak.tgmpipe.diskCache", "8G"));
            NativeTerrainService s = new NativeTerrainService(terrain, worlds, cacheRoot, bytes, exportKey);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.info(() -> "native terrain ready in " + ms + " ms on cuda:" + device + ": " + terrain.info());
            return s;
        } catch (RuntimeException e) {
            terrain.close();
            throw e instanceof TGMPipeException t ? t : new TGMPipeException("native terrain failed to start: " + e.getMessage(), e);
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

    /** {@code stonebreak.tgmpipe.device}: "cuda:N" = N; "auto"/"cuda" = the service's rule (cuda:1 on a two-GPU
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

    /** The NVRTC of the model's torch (the compiler the Python path's kernels came from), if installed. */
    static Path torchNvrtc() {
        String set = System.getProperty("stonebreak.terrain.nvrtc");
        if (set != null && !set.isBlank()) {
            return Path.of(set);
        }
        Path lib = TGMPipe.slmDir().resolve(".venv/lib");
        try (DirectoryStream<Path> pys = Files.newDirectoryStream(lib, "python3*")) {
            for (Path py : pys) {
                Path d = py.resolve("site-packages/nvidia/cuda_nvrtc/lib");
                if (!Files.isDirectory(d)) {
                    continue;
                }
                try (DirectoryStream<Path> libs = Files.newDirectoryStream(d, "libnvrtc.so.*")) {
                    for (Path p : libs) {
                        if (!p.getFileName().toString().contains("builtins")) {
                            return p;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
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

    /** Runs the model's exporter when its Python exists and the export is missing or stale (exit 3 of --check). */
    private static void refreshExport(Path dir, Path logFile) {
        Path python = TGMPipe.pythonExe();
        if (!Files.isExecutable(python)) {
            return;   // no Python here: whatever export ships with the model is used as is
        }
        String model = TGMPipe.modelPath();
        try {
            int check = run(List.of(python.toString(), "-m", RELEASE_MODULE, model, "--out", dir.toString(), "--check"),
                    logFile, 120);
            if (check == 0) {
                return;
            }
            String dev = "cuda:" + device();
            LOG.info(() -> "writing the native terrain export for " + model + " (" + dir + ") on " + dev);
            int code = run(List.of(python.toString(), "-m", RELEASE_MODULE, model, "--out", dir.toString(),
                    "--device", dev), logFile, 1800);
            if (code != 0) {
                throw new TGMPipeException("native export failed (exit " + code + "); see " + logFile);
            }
        } catch (IOException e) {
            throw new TGMPipeException("could not run the native exporter", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TGMPipeException("interrupted while exporting the native terrain model", e);
        }
    }

    private static int run(List<String> cmd, Path logFile, long timeoutS) throws IOException, InterruptedException {
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(TGMPipe.slmDir().toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.environment().put("TGM_KERNEL_CACHE", TGMPipe.kernelCacheDir().toString());
        Process p = pb.start();
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new TGMPipeException("native exporter timed out after " + timeoutS + " s: " + cmd);
        }
        return p.exitValue();
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
