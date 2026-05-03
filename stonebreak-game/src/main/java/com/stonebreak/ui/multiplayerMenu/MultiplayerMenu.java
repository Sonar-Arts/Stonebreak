package com.stonebreak.ui.multiplayerMenu;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Top-level multiplayer screen: Host World, Join World, Back.
 */
public final class MultiplayerMenu {

    private static final float BUTTON_W = 400f;
    private static final float BUTTON_H = 40f;
    private static final float BUTTON_SPACING = 50f;

    private final SkijaUIBackend backend;
    private final MultiplayerUIPainter painter;
    private Font fontTitle;
    private Font fontButton;

    private int selected = -1;

    public MultiplayerMenu(SkijaUIBackend backend) {
        this.backend = backend;
        this.painter = new MultiplayerUIPainter(backend);
    }

    private void ensureFonts() {
        if (fontTitle != null) return;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle = new Font(tf, 40f);
        fontButton = new Font(tf, 20f);
    }

    public void render(int w, int h) {
        if (!backend.isAvailable()) return;
        ensureFonts();
        backend.beginFrame(w, h, 1.0f);
        try {
            Canvas c = backend.getCanvas();
            painter.drawBackground(c, w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            painter.drawCentered(c, "Multiplayer", cx, cy - 120f, fontTitle, MultiplayerUIPainter.COLOR_TEXT);

            float bx = cx - BUTTON_W / 2f;
            painter.drawButton(c, "Host World", bx, cy - 20f,                       BUTTON_W, BUTTON_H, selected == 0, fontButton);
            painter.drawButton(c, "Join World", bx, cy - 20f + BUTTON_SPACING,      BUTTON_W, BUTTON_H, selected == 1, fontButton);
            painter.drawButton(c, "Back",       bx, cy - 20f + BUTTON_SPACING * 2f, BUTTON_W, BUTTON_H, selected == 2, fontButton);
        } finally {
            backend.endFrame();
        }
    }

    public void handleMouseMove(double mx, double my, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        float bx = cx - BUTTON_W / 2f;
        if (painter.hits(mx, my, bx, cy - 20f,                       BUTTON_W, BUTTON_H)) selected = 0;
        else if (painter.hits(mx, my, bx, cy - 20f + BUTTON_SPACING, BUTTON_W, BUTTON_H)) selected = 1;
        else if (painter.hits(mx, my, bx, cy - 20f + BUTTON_SPACING * 2f, BUTTON_W, BUTTON_H)) selected = 2;
        else selected = -1;
    }

    public void handleMouseClick(double mx, double my, int w, int h, int button, int action) {
        if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) return;
        handleMouseMove(mx, my, w, h);
        execute();
    }

    public void handleInput(long window) {
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            Game.getInstance().setState(GameState.MAIN_MENU);
        }
    }

    private void execute() {
        switch (selected) {
            case 0 -> Game.getInstance().setState(GameState.HOST_WORLD_SELECT);
            case 1 -> Game.getInstance().setState(GameState.JOIN_WORLD_SCREEN);
            case 2 -> Game.getInstance().setState(GameState.MAIN_MENU);
            default -> { /* nothing */ }
        }
    }

    public void dispose() {
        if (fontTitle != null)  { fontTitle.close();  fontTitle = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
        painter.dispose();
    }
}
