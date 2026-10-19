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
 * The game-launch setup for real, against this checkout: installs the model's environment with uv if
 * it is missing, checks the model on the GPU and compiles the kernels. Opt-in
 * ({@code -Dstonebreak.modelSetup.live=true}): needs an NVIDIA GPU and network (or uv's cache).
 */
class ModelSetupLiveTest {

    private static boolean live() {
        return Boolean.getBoolean("stonebreak.modelSetup.live");
    }

    @Test
    void downloadsThePinnedUvRelease(@TempDir Path tools) throws Exception {
        assumeTrue(live(), "opt-in: -Dstonebreak.modelSetup.live=true");
        StringWriter out = new StringWriter();
        // locate() would find a dev box's own uv on the PATH first, so exercise the download directly.
        Path target = tools.resolve("uv").resolve(UvTool.windows() ? "uv.exe" : "uv");
        java.lang.reflect.Method download = UvTool.class.getDeclaredMethod("download", Path.class, PrintWriter.class,
                java.util.function.BiConsumer.class);
        download.setAccessible(true);
        download.invoke(null, target, new PrintWriter(out, true), (java.util.function.BiConsumer<String, Double>) (t, f) -> { });
        Process p = new ProcessBuilder(target.toString(), "--version").redirectErrorStream(true).start();
        String version = new String(p.getInputStream().readAllBytes()).strip();
        assertEquals(0, p.waitFor());
        assertTrue(version.startsWith("uv " + UvTool.VERSION), version);
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
        assertTrue(Files.isExecutable(TGMPipe.pythonExe()));
        setup.awaitUsable(f -> { });
    }
}
