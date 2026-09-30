package com.stonebreak.ui.modelSetup;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Outcome;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Snapshot;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Status;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Step;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.StepId;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setup screen rendered headlessly (CPU raster) in each state it can be in. Writes the frames to
 * {@code target/model-setup-*.png} for eyeballing and checks the states look different.
 */
class ModelSetupScreenRenderTest {

    private static final int W = 1280, H = 800;

    @Test
    void everyStateRendersAndLooksDifferent() throws Exception {
        Bitmap running = render(snapshot(Outcome.RUNNING,
                step(StepId.GPU, Status.DONE, "NVIDIA RTX PRO 6000 Blackwell Workstation Edition", -1),
                step(StepId.UV, Status.DONE, "uv 0.12.20 (downloaded)", -1),
                step(StepId.ENVIRONMENT, Status.RUNNING, "1.84 GB of 4.37 GB downloaded (3 of 9 large packages)", 0.42),
                step(StepId.VERIFY, Status.PENDING, "", -1),
                step(StepId.KERNELS, Status.PENDING, "", -1), "Downloading torch (846.1MiB)", null), "running");
        Bitmap failed = render(snapshot(Outcome.FAILED,
                step(StepId.GPU, Status.DONE, "NVIDIA GeForce RTX 5090", -1),
                step(StepId.UV, Status.DONE, "uv (/usr/bin/uv)", -1),
                step(StepId.ENVIRONMENT, Status.FAILED, "uv sync failed (exit 2): network unreachable", -1),
                step(StepId.VERIFY, Status.PENDING, "", -1),
                step(StepId.KERNELS, Status.PENDING, "", -1), "", "uv sync failed (exit 2): error sending request for url (https://download.pytorch.org/whl/cu128/torch/)"), "failed");
        Bitmap unavailable = render(snapshot(Outcome.UNAVAILABLE,
                step(StepId.GPU, Status.FAILED, "No NVIDIA GPU found", -1),
                step(StepId.UV, Status.SKIPPED, "", -1),
                step(StepId.ENVIRONMENT, Status.SKIPPED, "", -1),
                step(StepId.VERIFY, Status.SKIPPED, "", -1),
                step(StepId.KERNELS, Status.SKIPPED, "", -1), "",
                "no NVIDIA graphics card found (the terrain model needs one); standard terrain still works"), "unavailable");
        Bitmap ready = render(snapshot(Outcome.READY,
                step(StepId.GPU, Status.DONE, "NVIDIA RTX PRO 6000 Blackwell Workstation Edition", -1),
                step(StepId.UV, Status.SKIPPED, "not needed", -1),
                step(StepId.ENVIRONMENT, Status.SKIPPED, "already installed", -1),
                step(StepId.VERIFY, Status.DONE, "NVIDIA RTX PRO 6000 Blackwell Workstation Edition (cuda:1)", -1),
                step(StepId.KERNELS, Status.DONE, "3 kernel groups in 14.5 s", -1), "", null), "ready");
        assertTrue(differ(running, failed) > 1000);
        assertTrue(differ(failed, unavailable) > 1000);
        assertTrue(differ(unavailable, ready) > 1000);
    }

    private static Step step(StepId id, Status status, String detail, double fraction) {
        return new Step(id, status, detail, fraction);
    }

    private static Snapshot snapshot(Outcome outcome, Step a, Step b, Step c, Step d, Step e, String activity, String message) {
        return new Snapshot(outcome, List.of(a, b, c, d, e), activity, message, 83_000, true);
    }

    private static Bitmap render(Snapshot snap, String name) throws Exception {
        Bitmap bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(W, H));
        Canvas canvas = new Canvas(bitmap);
        new ModelSetupScreen(new RasterBackend(canvas), () -> snap).render(W, H);
        Path out = Path.of("target", "model-setup-" + name + ".png");
        Files.createDirectories(out.getParent());
        try (Image img = Image.makeRasterFromBitmap(bitmap); Data png = img.encodeToData(EncodedImageFormat.PNG)) {
            Files.write(out, png.getBytes());
        }
        return bitmap;
    }

    private static int differ(Bitmap a, Bitmap b) {
        int n = 0;
        for (int y = 0; y < H; y += 2) {
            for (int x = 0; x < W; x += 2) {
                if (a.getColor(x, y) != b.getColor(x, y)) n++;
            }
        }
        return n;
    }

    /** Raster canvas + the game's font and logo from the classpath; no GL. */
    private static final class RasterBackend extends SkijaUIBackend {
        private static final Typeface FONT = load("/fonts/Minecraft.ttf", true);
        private static final Image LOGO = load("/ui/mainMenu/Stonebreak_Logo.png", false);
        private final Canvas canvas;

        RasterBackend(Canvas canvas) {
            this.canvas = canvas;
        }

        @SuppressWarnings("unchecked")
        private static <T> T load(String resource, boolean font) {
            try (InputStream in = ModelSetupScreenRenderTest.class.getResourceAsStream(resource)) {
                byte[] bytes = in.readAllBytes();
                return (T) (font ? FontMgr.getDefault().makeFromData(Data.makeFromBytes(bytes))
                        : Image.makeDeferredFromEncodedBytes(bytes));
            } catch (Exception e) {
                throw new IllegalStateException(resource, e);
            }
        }

        @Override public Canvas getCanvas() { return canvas; }
        @Override public boolean isAvailable() { return true; }
        @Override public Typeface getMinecraftTypeface() { return FONT; }
        @Override public Image getStonebreakLogo() { return LOGO; }
        @Override public void beginFrame(int width, int height, float pixelRatio) { }
        @Override public void endFrame() { }
    }
}
