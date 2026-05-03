package com.stonebreak.ui.multiplayerMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Join screen: enter host:port + username, then connect.
 */
public final class JoinWorldScreen {

    private static final float FIELD_W = 320f;
    private static final float FIELD_H = 32f;
    private static final float ACTION_BUTTON_W = 200f;
    private static final float ACTION_BUTTON_H = 36f;

    private final SkijaUIBackend backend;
    private final MultiplayerUIPainter painter;

    private Font fontTitle, fontBody, fontInfo;

    private String hostText;
    private String portText;
    private String userText;
    private int focusedField = 0; // 0 host, 1 port, 2 user, -1 none
    private int hoverButton = -1; // 0 connect, 1 back
    private String statusMessage = "";
    private long lastBlinkMs = System.currentTimeMillis();
    private boolean caretOn = true;

    public JoinWorldScreen(SkijaUIBackend backend) {
        this.backend = backend;
        this.painter = new MultiplayerUIPainter(backend);
    }

    public void onShow() {
        Settings s = Settings.getInstance();
        hostText = s.getLastJoinHost();
        portText = String.valueOf(s.getMultiplayerPort());
        userText = s.getMultiplayerUsername();
        statusMessage = "";
        focusedField = -1;
    }

    private void ensureFonts() {
        if (fontTitle != null) return;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle = new Font(tf, 36f);
        fontBody  = new Font(tf, 16f);
        fontInfo  = new Font(tf, 14f);
    }

    public void render(int w, int h) {
        if (!backend.isAvailable()) return;
        ensureFonts();
        long now = System.currentTimeMillis();
        if (now - lastBlinkMs > 500) { caretOn = !caretOn; lastBlinkMs = now; }

        backend.beginFrame(w, h, 1.0f);
        try {
            Canvas c = backend.getCanvas();
            painter.drawBackground(c, w, h);
            float cx = w / 2f;
            float cy = h / 2f;

            painter.drawCentered(c, "Join World", cx, cy - 160f, fontTitle, MultiplayerUIPainter.COLOR_TEXT);

            float fx = cx - FIELD_W / 2f;
            float y = cy - 100f;
            painter.drawLeft(c, "Host:", fx, y - 8f, fontBody, MultiplayerUIPainter.COLOR_TEXT);
            painter.drawTextField(c, hostText, focusedField == 0, caretOn, fx, y, FIELD_W, FIELD_H, fontBody);

            y += 60f;
            painter.drawLeft(c, "Port:", fx, y - 8f, fontBody, MultiplayerUIPainter.COLOR_TEXT);
            painter.drawTextField(c, portText, focusedField == 1, caretOn, fx, y, FIELD_W, FIELD_H, fontBody);

            y += 60f;
            painter.drawLeft(c, "Username:", fx, y - 8f, fontBody, MultiplayerUIPainter.COLOR_TEXT);
            painter.drawTextField(c, userText, focusedField == 2, caretOn, fx, y, FIELD_W, FIELD_H, fontBody);

            float actionsY = y + FIELD_H + 30f;
            painter.drawButton(c, "Connect", cx - ACTION_BUTTON_W - 10f, actionsY,
                    ACTION_BUTTON_W, ACTION_BUTTON_H, hoverButton == 0, fontBody);
            painter.drawButton(c, "Back", cx + 10f, actionsY,
                    ACTION_BUTTON_W, ACTION_BUTTON_H, hoverButton == 1, fontBody);

            if (!statusMessage.isEmpty()) {
                painter.drawCentered(c, statusMessage, cx, actionsY + ACTION_BUTTON_H + 28f,
                        fontInfo, MultiplayerUIPainter.COLOR_TEXT_HI);
            }
        } finally {
            backend.endFrame();
        }
    }

    public void handleMouseMove(double mx, double my, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        float fx = cx - FIELD_W / 2f;
        float y = cy - 100f;
        // hover detection for buttons only; field hover not visualized
        float actionsY = y + 60f * 2 + FIELD_H + 30f;
        hoverButton = -1;
        if (painter.hits(mx, my, cx - ACTION_BUTTON_W - 10f, actionsY, ACTION_BUTTON_W, ACTION_BUTTON_H)) hoverButton = 0;
        else if (painter.hits(mx, my, cx + 10f, actionsY, ACTION_BUTTON_W, ACTION_BUTTON_H))               hoverButton = 1;
    }

    public void handleMouseClick(double mx, double my, int w, int h, int button, int action) {
        if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) return;
        handleMouseMove(mx, my, w, h);

        float cx = w / 2f;
        float cy = h / 2f;
        float fx = cx - FIELD_W / 2f;
        float y = cy - 100f;

        focusedField = -1;
        if (painter.hits(mx, my, fx, y,             FIELD_W, FIELD_H)) focusedField = 0;
        else if (painter.hits(mx, my, fx, y + 60f,  FIELD_W, FIELD_H)) focusedField = 1;
        else if (painter.hits(mx, my, fx, y + 120f, FIELD_W, FIELD_H)) focusedField = 2;

        if (hoverButton == 0) connect();
        else if (hoverButton == 1) Game.getInstance().setState(GameState.MULTIPLAYER_MENU);
    }

    public void handleInput(long window) {
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            Game.getInstance().setState(GameState.MULTIPLAYER_MENU);
        }
    }

    public void handleCharInput(char ch) {
        if (focusedField < 0) return;
        if (ch < 32 || ch == 127) return;
        switch (focusedField) {
            case 0 -> { if (hostText.length() < 64) hostText += ch; }
            case 1 -> { if (ch >= '0' && ch <= '9' && portText.length() < 5) portText += ch; }
            case 2 -> { if (userText.length() < 24) userText += ch; }
        }
    }

    public void handleKeyInput(int key, int action, int mods) {
        if (focusedField < 0) return;
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
        if (key == GLFW_KEY_BACKSPACE) {
            switch (focusedField) {
                case 0 -> { if (!hostText.isEmpty()) hostText = hostText.substring(0, hostText.length() - 1); }
                case 1 -> { if (!portText.isEmpty()) portText = portText.substring(0, portText.length() - 1); }
                case 2 -> { if (!userText.isEmpty()) userText = userText.substring(0, userText.length() - 1); }
            }
        } else if (key == GLFW_KEY_TAB) {
            focusedField = (focusedField + 1) % 3;
        } else if (key == GLFW_KEY_ENTER) {
            focusedField = -1;
        }
    }

    private void connect() {
        int port;
        try {
            port = Integer.parseInt(portText.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            statusMessage = "Invalid port.";
            return;
        }
        if (hostText.isBlank()) { statusMessage = "Host required."; return; }
        if (userText.isBlank()) userText = "Player";

        Settings s = Settings.getInstance();
        s.setLastJoinHost(hostText);
        s.setMultiplayerPort(port);
        s.setMultiplayerUsername(userText);
        s.saveSettings();

        try {
            MultiplayerSession.joinServer(hostText.trim(), port, userText.trim());
            statusMessage = "Connecting to " + hostText + ":" + port + " ...";
        } catch (Exception ex) {
            statusMessage = "Connect failed: " + ex.getMessage();
        }
    }

    public void dispose() {
        if (fontTitle != null) { fontTitle.close(); fontTitle = null; }
        if (fontBody  != null) { fontBody.close();  fontBody  = null; }
        if (fontInfo  != null) { fontInfo.close();  fontInfo  = null; }
        painter.dispose();
    }
}
