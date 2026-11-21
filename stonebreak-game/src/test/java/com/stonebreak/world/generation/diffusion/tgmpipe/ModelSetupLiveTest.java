package com.stonebreak.world.generation.diffusion.tgmpipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The game-launch setup for real, against this checkout: gets the pinned CUDA compiler if it is missing, opens the
 * model's native export on the GPU and compiles the kernels. Opt-in ({@code -Dstonebreak.modelSetup.live=true}): needs
 * an NVIDIA GPU, the native terrain library, the model release (Git LFS) and network for the first NVRTC download.
 */
class ModelSetupLiveTest {

    private static boolean live() {
        return Boolean.getBoolean("stonebreak.modelSetup.live");
    }

    @Test
    void downloadsThePinnedNvrtc(@TempDir Path tools) {
        assumeTrue(live(), "opt-in: -Dstonebreak.modelSetup.live=true");
        assumeTrue(NvrtcTool.override() == null, "an NVRTC override would be returned instead of downloading");
        assumeTrue(NvrtcTool.wheel() != null, "no NVRTC build for this platform");
        StringWriter out = new StringWriter();
        Path lib = NvrtcTool.locate(tools, new PrintWriter(out, true), (t, f) -> { });
        assertEquals(NvrtcTool.installDir(tools).resolve(NvrtcTool.wheel().library()), lib, out.toString());
        assertTrue(Files.isRegularFile(lib), out.toString());
        assertEquals(lib, NvrtcTool.installed(tools), "found again without downloading");
    }

    @Test
    void setsUpTheModelFromWhateverIsThere() {
        assumeTrue(live(), "opt-in: -Dstonebreak.modelSetup.live=true");
        ModelSetup setup = ModelSetup.getInstance();
        long t0 = System.nanoTime();
        setup.start();
        String last = "";
        while (setup.snapshot().outcome() == ModelSetup.Outcome.RUNNING) {
            ModelSetup.Snapshot s = setup.snapshot();
            String line = s.steps().stream().filter(st -> st.status() == ModelSetup.Status.RUNNING)
                    .map(st -> st.id() + ": " + st.detail() + (st.fraction() >= 0 ? String.format(" [%.0f%%]", 100 * st.fraction()) : ""))
                    .findFirst().orElse("");
            if (!line.equals(last)) {
                System.out.printf("%6.1f s  %s%n", (System.nanoTime() - t0) / 1e9, line);
                last = line;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        ModelSetup.Snapshot s = setup.snapshot();
        s.steps().forEach(st -> System.out.println("  " + st.status() + "  " + st.id().title + "  " + st.detail()));
        System.out.println("outcome " + s.outcome() + " after " + s.elapsedMs() + " ms, work needed " + s.workNeeded()
                + (s.message() != null ? ", " + s.message() : ""));
        assertEquals(ModelSetup.Outcome.READY, s.outcome(), s.message());
        assertTrue(Files.isRegularFile(TGMPipe.kernelCacheDir().resolve(ModelSetup.STAMP)), "stamp written");
        setup.awaitUsable(f -> { });
    }
}
