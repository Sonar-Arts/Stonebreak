package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TGMPipeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The minimum system {@value TGMPipe#MODEL_NAME} runs on. A machine below it has the model explicitly
 * disabled: {@link TGMPipe#ensureRunning} refuses to start the service, {@link ModelSetup} reports the
 * model unavailable without downloading anything, and tests that need the real model skip
 * ({@code @RequiresDaedalus} in the test sources).
 *
 * <p><b>Why a GPU floor.</b> Daedalus is written for NVIDIA GPUs. With {@code --device auto} the service
 * falls back to its CPU kernels when the GPU has {@value #MAX_EXCLUDED_VRAM_MIB} MiB of VRAM or less
 * (Python {@code CPU_MAX_VRAM} in {@code terrain_slm/tgmpipe/service.py}).
 * <b>The CPU kernels ({@code cpu_sampler.py}, {@code controls_cpu.py}) are experimental and not yet
 * optimized</b>: they work, but a tile takes minutes, so worlds stall waiting for terrain. Until they
 * are optimized, a machine that would land on them does not meet the requirements.
 *
 * <p><b>Reference machine.</b> The thresholds are set from the main dev laptop, which is <i>below</i> the
 * bar: RTX 3050 Ti Laptop GPU (4096 MiB), i7-11370H (4 cores / 8 threads), 16 GB RAM. It meets the RAM
 * and CPU floors and fails the VRAM one, so Daedalus is disabled there. Revisit these when the CPU
 * kernels are optimized or real minimum specs are measured.
 *
 * <p>{@code -Dstonebreak.daedalus.requirements=off} skips the check, to work on the experimental CPU
 * kernels on a machine below the bar.
 */
public final class DaedalusRequirements {

    private static final Logger LOG = Logger.getLogger(DaedalusRequirements.class.getName());

    /** A GPU with this much VRAM or less makes the service run on the experimental CPU kernels. */
    static final long MAX_EXCLUDED_VRAM_MIB = 4096;
    /** System RAM floor; a little under 16 GB, since a 16 GB machine reports ~15.8 GiB usable. */
    static final long MIN_RAM_MIB = 15 * 1024;
    static final int MIN_LOGICAL_CPUS = 8;

    private static final long PROBE_TIMEOUT_SECONDS = 10;

    /**
     * What this machine has. {@code gpuName} is null (and {@code vramMiB} -1) when no NVIDIA GPU was
     * found; {@code ramMiB} is -1 when the JVM cannot report it.
     */
    public record SystemSpecs(String gpuName, long vramMiB, long ramMiB, int logicalCpus) {

        @Override
        public String toString() {
            String gpu = gpuName == null ? "no NVIDIA GPU" : gpuName + " (" + vramMiB + " MiB)";
            String ram = ramMiB < 0 ? "unknown RAM" : String.format("%.1f GiB RAM", ramMiB / 1024.0);
            return gpu + ", " + logicalCpus + " logical CPUs, " + ram;
        }
    }

    /** @param reason why the requirements are not met, or null when they are */
    public record Verdict(boolean met, String reason, SystemSpecs specs) { }

    private static final class Holder {
        static final Verdict VERDICT = computeVerdict();
    }

    private DaedalusRequirements() { }

    /** This machine's verdict, probed once per JVM ({@code nvidia-smi} + the OS) and cached. Any thread. */
    public static Verdict check() {
        return Holder.VERDICT;
    }

    /** Whether {@value TGMPipe#MODEL_NAME} may run on this machine. */
    public static boolean isMet() {
        return check().met();
    }

    /** @throws TGMPipeException when this machine is below the requirements */
    public static void requireMet() {
        Verdict v = check();
        if (!v.met()) {
            throw new TGMPipeException(disabledMessage(v));
        }
    }

    /** What the game says when the model is disabled here. */
    public static String disabledMessage(Verdict v) {
        return TGMPipe.MODEL_NAME + " is disabled on this system: " + v.reason();
    }

    private static Verdict computeVerdict() {
        SystemSpecs specs = probe();
        if (bypassed()) {
            LOG.warning(() -> TGMPipe.MODEL_NAME + " system requirements skipped (-Dstonebreak.daedalus.requirements=off) on "
                    + specs + "; below the bar the model runs on its experimental, unoptimized CPU kernels");
            return new Verdict(true, null, specs);
        }
        Verdict v = evaluate(specs);
        if (v.met()) {
            LOG.info(() -> TGMPipe.MODEL_NAME + " system requirements met: " + specs);
        } else {
            LOG.warning(() -> disabledMessage(v));
        }
        return v;
    }

    static boolean bypassed() {
        return "off".equalsIgnoreCase(System.getProperty("stonebreak.daedalus.requirements", "on"));
    }

    /** The verdict for {@code specs}; pure, so the thresholds are testable without this machine's hardware. */
    static Verdict evaluate(SystemSpecs specs) {
        List<String> missing = new ArrayList<>();
        if (specs.gpuName() == null) {
            missing.add("no NVIDIA GPU found");
        } else if (specs.vramMiB() <= MAX_EXCLUDED_VRAM_MIB) {
            missing.add(specs.gpuName() + " has " + specs.vramMiB() + " MiB of VRAM, needs more than "
                    + MAX_EXCLUDED_VRAM_MIB + " MiB (smaller GPUs fall back to the CPU kernels, which are"
                    + " experimental and not yet optimized)");
        }
        if (specs.ramMiB() >= 0 && specs.ramMiB() < MIN_RAM_MIB) {
            missing.add(String.format("%.1f GiB of RAM, needs %d GiB", specs.ramMiB() / 1024.0, MIN_RAM_MIB / 1024));
        }
        if (specs.logicalCpus() < MIN_LOGICAL_CPUS) {
            missing.add(specs.logicalCpus() + " logical CPUs, needs " + MIN_LOGICAL_CPUS);
        }
        return missing.isEmpty()
                ? new Verdict(true, null, specs)
                : new Verdict(false, String.join("; ", missing) + " (standard terrain still works)", specs);
    }

    // ------------------------------------------------------------------ probing

    private static SystemSpecs probe() {
        String[] gpu = nvidiaGpu();
        return new SystemSpecs(gpu == null ? null : gpu[0], gpu == null ? -1 : Long.parseLong(gpu[1]),
                totalRamMiB(), Runtime.getRuntime().availableProcessors());
    }

    /** {name, MiB} of the GPU the service would use, or null without a working {@code nvidia-smi}. */
    private static String[] nvidiaGpu() {
        List<String> lines = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=name,memory.total",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.isBlank()) lines.add(line.strip());
                }
            }
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
        } catch (IOException e) {
            return null;   // no nvidia-smi: no NVIDIA driver
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return pickGpu(lines);
    }

    /**
     * The GPU the service runs on, from {@code nvidia-smi} csv lines ({@code name, MiB}): the only one, or
     * the second on a multi-GPU machine (the service keeps GPU 0 for the game's rendering; Python
     * {@code pick_device}). Null when the output is not what was asked for.
     */
    static String[] pickGpu(List<String> lines) {
        if (lines.isEmpty()) return null;
        String line = lines.get(lines.size() > 1 ? 1 : 0);
        int comma = line.lastIndexOf(',');   // a GPU name could hold a comma; the number never does
        if (comma < 0) return null;
        String name = line.substring(0, comma).strip();
        String mib = line.substring(comma + 1).strip();
        if (name.isEmpty() || !mib.matches("\\d+")) return null;
        return new String[] {name, mib};
    }

    private static long totalRamMiB() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getTotalMemorySize() / (1024 * 1024);
        }
        return -1;
    }
}
