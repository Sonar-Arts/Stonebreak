package com.stonebreak.ui.multiplayerMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.save.model.WorldData;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;

import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Host-a-world screen: pick an existing world from the list, set a TCP port,
 * and start the integrated server. Displays a port-forwarding hint.
 */
public final class HostWorldScreen {

    private static final float WORLD_ROW_W = 460f;
    private static final float WORLD_ROW_H = 28f;
    private static final float WORLD_ROW_GAP = 4f;
    private static final int   MAX_VISIBLE_WORLDS = 8;
    private static final float PORT_FIELD_W = 140f;
    private static final float PORT_FIELD_H = 32f;
    private static final float ACTION_BUTTON_W = 200f;
    private static final float ACTION_BUTTON_H = 36f;

    private final SkijaUIBackend backend;
    private final MultiplayerUIPainter painter;
    private final WorldDiscoveryManager discovery = new WorldDiscoveryManager();

    private Font fontTitle;
    private Font fontHeader;
    private Font fontBody;
    private Font fontInfo;

    private List<String> worlds = List.of();
    private int selectedWorld = -1;
    private int hoverWorld = -1;
    private int hoverButton = -1; // 0=host, 1=back
    private boolean portFocused = false;
    private String portText = "25565";
    private String statusMessage = "";
    private long lastBlinkMs = System.currentTimeMillis();
    private boolean caretOn = true;

    public HostWorldScreen(SkijaUIBackend backend) {
        this.backend = backend;
        this.painter = new MultiplayerUIPainter(backend);
    }

    public void onShow() {
        worlds = discovery.discoverWorlds();
        selectedWorld = worlds.isEmpty() ? -1 : 0;
        portText = String.valueOf(Settings.getInstance().getMultiplayerPort());
        statusMessage = "";
        portFocused = false;
    }

    private void ensureFonts() {
        if (fontTitle != null) return;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle  = new Font(tf, 36f);
        fontHeader = new Font(tf, 18f);
        fontBody   = new Font(tf, 16f);
        fontInfo   = new Font(tf, 14f);
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

            painter.drawCentered(c, "Host World", cx, 70f, fontTitle, MultiplayerUIPainter.COLOR_TEXT);
            painter.drawCentered(c, "Select a world to host", cx, 100f, fontHeader, MultiplayerUIPainter.COLOR_TEXT_DIM);

            // World list
            float listX = cx - WORLD_ROW_W / 2f;
            float listY = 130f;
            if (worlds.isEmpty()) {
                painter.drawCentered(c, "(No worlds found — create one in Singleplayer first)",
                        cx, listY + 40f, fontBody, MultiplayerUIPainter.COLOR_TEXT_DIM);
            }
            for (int i = 0; i < Math.min(worlds.size(), MAX_VISIBLE_WORLDS); i++) {
                float y = listY + i * (WORLD_ROW_H + WORLD_ROW_GAP);
                boolean hi = (i == selectedWorld) || (i == hoverWorld);
                painter.drawButton(c, worlds.get(i), listX, y, WORLD_ROW_W, WORLD_ROW_H, hi, fontBody);
            }

            // Port field
            float portLabelY = listY + MAX_VISIBLE_WORLDS * (WORLD_ROW_H + WORLD_ROW_GAP) + 16f;
            painter.drawLeft(c, "Port:", listX, portLabelY + 22f, fontBody, MultiplayerUIPainter.COLOR_TEXT);
            painter.drawTextField(c, portText, portFocused, caretOn,
                    listX + 80f, portLabelY, PORT_FIELD_W, PORT_FIELD_H, fontBody);

            // Action buttons
            float actionsY = portLabelY + PORT_FIELD_H + 24f;
            painter.drawButton(c, "Start Hosting", cx - ACTION_BUTTON_W - 10f, actionsY,
                    ACTION_BUTTON_W, ACTION_BUTTON_H, hoverButton == 0, fontBody);
            painter.drawButton(c, "Back", cx + 10f, actionsY,
                    ACTION_BUTTON_W, ACTION_BUTTON_H, hoverButton == 1, fontBody);

            // Info / status
            float infoY = actionsY + ACTION_BUTTON_H + 30f;
            painter.drawCentered(c,
                    "To allow players outside your network, forward TCP port " + portText
                    + " on your router to this computer's local IP.",
                    cx, infoY, fontInfo, MultiplayerUIPainter.COLOR_TEXT_DIM);
            painter.drawCentered(c,
                    "Same network? Others can join via your local IPv4 address.",
                    cx, infoY + 18f, fontInfo, MultiplayerUIPainter.COLOR_TEXT_DIM);
            if (!statusMessage.isEmpty()) {
                painter.drawCentered(c, statusMessage, cx, infoY + 46f, fontBody, MultiplayerUIPainter.COLOR_TEXT_HI);
            }
        } finally {
            backend.endFrame();
        }
    }

    public void handleMouseMove(double mx, double my, int w, int h) {
        float cx = w / 2f;
        float listX = cx - WORLD_ROW_W / 2f;
        float listY = 130f;
        hoverWorld = -1;
        for (int i = 0; i < Math.min(worlds.size(), MAX_VISIBLE_WORLDS); i++) {
            float y = listY + i * (WORLD_ROW_H + WORLD_ROW_GAP);
            if (painter.hits(mx, my, listX, y, WORLD_ROW_W, WORLD_ROW_H)) { hoverWorld = i; break; }
        }
        float portLabelY = listY + MAX_VISIBLE_WORLDS * (WORLD_ROW_H + WORLD_ROW_GAP) + 16f;
        float actionsY = portLabelY + PORT_FIELD_H + 24f;
        hoverButton = -1;
        if (painter.hits(mx, my, cx - ACTION_BUTTON_W - 10f, actionsY, ACTION_BUTTON_W, ACTION_BUTTON_H)) hoverButton = 0;
        else if (painter.hits(mx, my, cx + 10f, actionsY, ACTION_BUTTON_W, ACTION_BUTTON_H))               hoverButton = 1;
    }

    public void handleMouseClick(double mx, double my, int w, int h, int button, int action) {
        if (button != GLFW_MOUSE_BUTTON_LEFT || action != GLFW_PRESS) return;
        handleMouseMove(mx, my, w, h);

        // Port field focus toggle
        float cx = w / 2f;
        float listX = cx - WORLD_ROW_W / 2f;
        float listY = 130f;
        float portLabelY = listY + MAX_VISIBLE_WORLDS * (WORLD_ROW_H + WORLD_ROW_GAP) + 16f;
        portFocused = painter.hits(mx, my, listX + 80f, portLabelY, PORT_FIELD_W, PORT_FIELD_H);

        if (hoverWorld >= 0) selectedWorld = hoverWorld;
        if (hoverButton == 0) startHosting();
        else if (hoverButton == 1) Game.getInstance().setState(GameState.MULTIPLAYER_MENU);
    }

    public void handleInput(long window) {
        // ESC = back
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            Game.getInstance().setState(GameState.MULTIPLAYER_MENU);
        }
    }

    public void handleCharInput(char ch) {
        if (!portFocused) return;
        if (ch >= '0' && ch <= '9' && portText.length() < 5) {
            portText += ch;
        }
    }

    public void handleKeyInput(int key, int action, int mods) {
        if (!portFocused) return;
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
        if (key == GLFW_KEY_BACKSPACE && !portText.isEmpty()) {
            portText = portText.substring(0, portText.length() - 1);
        } else if (key == GLFW_KEY_ENTER) {
            portFocused = false;
        }
    }

    private void startHosting() {
        if (selectedWorld < 0 || selectedWorld >= worlds.size()) {
            statusMessage = "Pick a world first.";
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            statusMessage = "Invalid port (1-65535).";
            return;
        }
        Settings.getInstance().setMultiplayerPort(port);
        Settings.getInstance().saveSettings();

        String worldName = worlds.get(selectedWorld);
        WorldData wd = discovery.getWorldData(worldName);
        long seed = (wd != null && wd.getSeed() != 0) ? wd.getSeed() : new Random().nextLong();

        try {
            MultiplayerSession.startHosting(port);
        } catch (Exception ex) {
            statusMessage = "Failed to bind port " + port + ": " + ex.getMessage();
            return;
        }
        // Load the world; networking layer will broadcast events as the host plays.
        Game.getInstance().startWorldGeneration(worldName, seed);
    }

    public void dispose() {
        if (fontTitle  != null) { fontTitle.close();  fontTitle  = null; }
        if (fontHeader != null) { fontHeader.close(); fontHeader = null; }
        if (fontBody   != null) { fontBody.close();   fontBody   = null; }
        if (fontInfo   != null) { fontInfo.close();   fontInfo   = null; }
        painter.dispose();
    }
}
