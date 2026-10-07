package com.stonebreak.ui.modelSetup;

import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Outcome;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Snapshot;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Status;
import com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.Step;
import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/**
 * Game-launch setup of the terrain model ({@link ModelSetup}), shown between the intro and the main
 * menu while there is something to install or compile: every step with its live status, what it is
 * doing right now, and how long it has taken. Setup keeps running if the player continues to the
 * menu; the menu's terrain model waits for it. A launch with nothing to do never shows this screen.
 */
public final class ModelSetupScreen {

    private static final int COLOR_BG = 0xFF0D1015;
    private static final int COLOR_PANEL = 0xE0161B22;
    private static final int COLOR_PANEL_BORDER = 0xFF2E3744;
    private static final int COLOR_TITLE = 0xFFE6E6E6;
    private static final int COLOR_TEXT = 0xFFD2D2D2;
    private static final int COLOR_DIM = 0xFF8C96A0;
    private static final int COLOR_FAINT = 0xFF5A6470;
    private static final int COLOR_DONE = 0xFF4CC36A;
    private static final int COLOR_RUNNING = 0xFF5B9BF0;
    private static final int COLOR_FAILED = 0xFFE05252;
    private static final int COLOR_WARN = 0xFFE8B04A;
    private static final int COLOR_BAR_BG = 0xFF26303C;

    /** How long a finished setup stays on screen (all ticks green) before the main menu. */
    private static final float READY_HOLD_SECONDS = 1.6f;

    private final SkijaUIBackend backend;
    private final ModelSetup setup = ModelSetup.getInstance();
    private final Supplier<Snapshot> source;

    private Font fontTitle, fontSub, fontStep, fontDetail, fontSmall, fontButton;
    private float fontScale = -1f;
    private float readyTimer;
    private boolean prevEsc;
    private int hovered = -1;
    /** Button rects of the last frame (x, y, w, h) and what they do, for hit tests. */
    private final List<float[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public ModelSetupScreen(SkijaUIBackend backend) {
        this(backend, ModelSetup.getInstance()::snapshot);
    }

    /** @param source what to show (the live setup; tests pass fixed snapshots) */
    ModelSetupScreen(SkijaUIBackend backend, Supplier<Snapshot> source) {
        this.backend = backend;
        this.source = source;
    }

    // ------------------------------------------------------------------ flow

    public void update(float deltaTime) {
        Snapshot snap = source.get();
        if (snap.outcome() == Outcome.READY && !snap.workNeeded()) {
            toMainMenu();   // an up-to-date install, seen only if the intro was skipped before the check
        } else if (snap.outcome() == Outcome.READY) {
            readyTimer += deltaTime;
            if (readyTimer >= READY_HOLD_SECONDS) toMainMenu();
        } else {
            readyTimer = 0f;
        }
    }

    private static void toMainMenu() {
        Game.getInstance().setState(GameState.MAIN_MENU);
    }

    public void handleInput(long window) {
        boolean esc = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
        if (esc && !prevEsc) toMainMenu();   // setup carries on in the background
        prevEsc = esc;
    }

    public void handleMouseMove(double x, double y, int width, int height) {
        hovered = hit(x, y);
    }

    public void handleMouseClick(double x, double y, int width, int height) {
        int i = hit(x, y);
        if (i >= 0) buttonActions.get(i).run();
    }

    private int hit(double x, double y) {
        for (int i = 0; i < buttonRects.size(); i++) {
            float[] r = buttonRects.get(i);
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ render

    public void render(int width, int height) {
        if (backend == null || !backend.isAvailable()) return;
        float s = Settings.getInstance().getUiScale();
        ensureFonts(s);
        Snapshot snap = source.get();

        backend.beginFrame(width, height, 1.0f);
        try {
            Canvas c = backend.getCanvas();
            MPainter.fillRect(c, 0, 0, width, height, COLOR_BG);
            float cx = width / 2f;
            float y = drawHeader(c, cx, s, snap);

            float panelW = Math.min(680f * s, width - 40f * s);
            float panelX = cx - panelW / 2f;
            float rowH = 54f * s;
            float panelH = rowH * snap.steps().size() + 16f * s;
            MPainter.fillRoundedRect(c, panelX, y, panelW, panelH, 8f * s, COLOR_PANEL);
            MPainter.strokeRoundedRect(c, panelX, y, panelW, panelH, 8f * s, COLOR_PANEL_BORDER, 1.5f * s);
            float rowY = y + 8f * s;
            for (Step step : snap.steps()) {
                drawStep(c, step, panelX + 16f * s, rowY, panelW - 32f * s, rowH, s, snap.outcome());
                rowY += rowH;
            }
            y += panelH + 22f * s;
            y = drawFooter(c, cx, y, panelW, s, snap);
            drawButtons(c, cx, Math.max(y + 10f * s, height - 90f * s), s, snap);
        } finally {
            backend.endFrame();
        }
    }

    private float drawHeader(Canvas c, float cx, float s, Snapshot snap) {
        float y = 40f * s;
        Image logo = backend.getStonebreakLogo();
        if (logo != null) {
            float h = 84f * s;
            float w = h * (1641f / 419f);
            c.drawImageRect(logo, Rect.makeXYWH(cx - w / 2f, y, w, h));
            y += h + 26f * s;
        }
        String title = switch (snap.outcome()) {
            case READY -> "Terrain model ready";
            case FAILED -> "Terrain model setup failed";
            case UNAVAILABLE -> "Terrain model unavailable";
            default -> "Setting up the terrain model";
        };
        MPainter.drawCenteredStringWithShadow(c, title, cx, y + 22f * s, fontTitle, COLOR_TITLE, MStyle.TEXT_SHADOW);
        MPainter.drawCenteredString(c, TGMPipe.generatorLabel().replace(" \u00b7 ", " ") + "  -  set up once, later launches skip this",
                cx, y + 46f * s, fontSub, COLOR_DIM);
        return y + 66f * s;
    }

    private void drawStep(Canvas c, Step step, float x, float y, float w, float rowH, float s, Outcome outcome) {
        float iconR = 10f * s;
        float icx = x + iconR + 2f * s;
        float icy = y + rowH / 2f - 2f * s;
        drawIcon(c, step.status(), icx, icy, iconR, s);

        float tx = x + 2 * iconR + 18f * s;
        float textW = w - (tx - x);
        int titleColor = switch (step.status()) {
            case PENDING, SKIPPED -> COLOR_DIM;
            case FAILED -> COLOR_FAILED;
            default -> COLOR_TEXT;
        };
        MPainter.drawString(c, step.id().title, tx, y + 21f * s, fontStep, titleColor);
        String detail = step.detail();
        if (step.status() == Status.PENDING) detail = outcome == Outcome.RUNNING ? "waiting" : "not run";
        if (detail != null && !detail.isEmpty()) {
            int dc = step.status() == Status.FAILED ? COLOR_FAILED : step.status() == Status.SKIPPED ? COLOR_FAINT : COLOR_DIM;
            MPainter.drawString(c, fit(detail, fontDetail, textW), tx, y + 40f * s, fontDetail, dc);
        }
        if (step.status() == Status.RUNNING && step.fraction() >= 0) {
            float by = y + rowH - 8f * s;
            float bw = textW;
            MPainter.fillRoundedRect(c, tx, by, bw, 4f * s, 2f * s, COLOR_BAR_BG);
            MPainter.fillRoundedRect(c, tx, by, Math.max(4f * s, bw * (float) Math.min(1.0, step.fraction())),
                    4f * s, 2f * s, COLOR_RUNNING);
        }
    }

    private void drawIcon(Canvas c, Status status, float cx, float cy, float r, float s) {
        switch (status) {
            case DONE -> {
                MPainter.fillCircle(c, cx, cy, r, COLOR_DONE);
                strokePath(c, 0xFF0D1015, 2.6f * s, cx - r * 0.45f, cy + r * 0.02f, cx - r * 0.1f, cy + r * 0.38f,
                        cx + r * 0.48f, cy - r * 0.35f);
            }
            case FAILED -> {
                MPainter.fillCircle(c, cx, cy, r, COLOR_FAILED);
                float d = r * 0.38f;
                strokePath(c, 0xFF0D1015, 2.6f * s, cx - d, cy - d, cx + d, cy + d);
                strokePath(c, 0xFF0D1015, 2.6f * s, cx + d, cy - d, cx - d, cy + d);
            }
            case RUNNING -> {
                MPainter.strokeCircle(c, cx, cy, r, COLOR_BAR_BG, 3f * s);
                float angle = (System.nanoTime() / 1_000_000L % 1000L) * 0.36f;
                try (Paint p = new Paint().setColor(COLOR_RUNNING).setMode(PaintMode.STROKE).setStrokeWidth(3f * s)
                        .setStrokeCap(PaintStrokeCap.ROUND).setAntiAlias(true)) {
                    c.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 100f, false, p);
                }
            }
            case SKIPPED -> {
                MPainter.strokeCircle(c, cx, cy, r, COLOR_FAINT, 1.5f * s);
                strokePath(c, COLOR_FAINT, 2f * s, cx - r * 0.4f, cy, cx + r * 0.4f, cy);
            }
            default -> MPainter.strokeCircle(c, cx, cy, r, COLOR_FAINT, 1.5f * s);
        }
    }

    private static void strokePath(Canvas c, int color, float width, float... xy) {
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) pb.lineTo(xy[i], xy[i + 1]);
            try (Path path = pb.build(); Paint p = new Paint().setColor(color).setMode(PaintMode.STROKE)
                    .setStrokeWidth(width).setStrokeCap(PaintStrokeCap.ROUND).setAntiAlias(true)) {
                c.drawPath(path, p);
            }
        }
    }

    private float drawFooter(Canvas c, float cx, float y, float w, float s, Snapshot snap) {
        long secs = snap.elapsedMs() / 1000;
        String elapsed = String.format("%d:%02d", secs / 60, secs % 60);
        switch (snap.outcome()) {
            case RUNNING -> {
                String act = snap.activity() == null || snap.activity().isEmpty() ? "Working..." : snap.activity();
                MPainter.drawCenteredString(c, fit("> " + act, fontSmall, w), cx, y, fontSmall, COLOR_DIM);
                MPainter.drawCenteredString(c, "Elapsed " + elapsed + "  -  you can keep playing with standard terrain meanwhile",
                        cx, y + 20f * s, fontSmall, COLOR_FAINT);
                return y + 30f * s;
            }
            case READY -> {
                MPainter.drawCenteredString(c, "Done in " + elapsed + ". Starting...", cx, y, fontSub, COLOR_DONE);
                return y + 20f * s;
            }
            case FAILED, UNAVAILABLE -> {
                int color = snap.outcome() == Outcome.FAILED ? COLOR_FAILED : COLOR_WARN;
                for (String line : wrap(snap.message() == null ? "" : snap.message(), fontSub, w)) {
                    MPainter.drawCenteredString(c, line, cx, y, fontSub, color);
                    y += 20f * s;
                }
                if (snap.outcome() == Outcome.FAILED) {
                    MPainter.drawCenteredString(c, "Details: Models/logs/setup.log", cx, y + 2f * s, fontSmall, COLOR_DIM);
                    y += 22f * s;
                }
                return y;
            }
            default -> {
                return y;
            }
        }
    }

    private void drawButtons(Canvas c, float cx, float y, float s, Snapshot snap) {
        buttonRects.clear();
        buttonActions.clear();
        List<String> labels = new ArrayList<>();
        switch (snap.outcome()) {
            case RUNNING -> {
                labels.add("Continue in background");
                buttonActions.add(ModelSetupScreen::toMainMenu);
            }
            case FAILED -> {
                labels.add("Retry");
                buttonActions.add(setup::retry);
                labels.add("Continue without it");
                buttonActions.add(ModelSetupScreen::toMainMenu);
            }
            case UNAVAILABLE -> {
                labels.add("Continue");
                buttonActions.add(ModelSetupScreen::toMainMenu);
            }
            default -> { }
        }
        if (labels.isEmpty()) return;
        float bw = 280f * s, bh = 44f * s, gap = 16f * s;
        float total = labels.size() * bw + (labels.size() - 1) * gap;
        float x = cx - total / 2f;
        for (int i = 0; i < labels.size(); i++) {
            boolean hi = i == hovered;
            MPainter.stoneSurface(c, x, y, bw, bh, MStyle.BUTTON_RADIUS,
                    hi ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL, MStyle.BUTTON_BORDER,
                    MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                    MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
            MPainter.drawCenteredStringWithShadow(c, labels.get(i), x + bw / 2f, y + bh / 2f + 6f * s, fontButton,
                    hi ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            buttonRects.add(new float[]{x, y, bw, bh});
            x += bw + gap;
        }
    }

    // ------------------------------------------------------------------ text helpers

    private static String fit(String text, Font font, float width) {
        if (MPainter.measureWidth(font, text) <= width) return text;
        String ell = "...";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (MPainter.measureWidth(font, text.substring(0, mid) + ell) <= width) lo = mid; else hi = mid - 1;
        }
        return text.substring(0, lo) + ell;
    }

    private static List<String> wrap(String text, Font font, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (MPainter.measureWidth(font, candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.size() > 4 ? lines.subList(0, 4) : lines;
    }

    private void ensureFonts(float s) {
        if (fontTitle != null && s == fontScale) return;
        dispose();
        fontScale = s;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle = new Font(tf, 30f * s);
        fontSub = new Font(tf, 14f * s);
        fontStep = new Font(tf, 17f * s);
        fontDetail = new Font(tf, 13f * s);
        fontSmall = new Font(tf, 12f * s);
        fontButton = new Font(tf, 18f * s);
    }

    public void dispose() {
        for (Font f : new Font[]{fontTitle, fontSub, fontStep, fontDetail, fontSmall, fontButton}) {
            if (f != null) f.close();
        }
        fontTitle = fontSub = fontStep = fontDetail = fontSmall = fontButton = null;
    }
}
