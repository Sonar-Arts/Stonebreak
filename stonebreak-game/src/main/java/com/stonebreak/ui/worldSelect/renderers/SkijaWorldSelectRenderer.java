package com.stonebreak.ui.worldSelect.renderers;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.worldSelect.WorldSelectLayout;
import com.stonebreak.ui.worldSelect.handlers.WorldInputHandler;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;
import com.stonebreak.world.save.model.WorldData;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;
import io.github.humbleui.types.RRect;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Skija-backed renderer for the world select screen. Owns no state beyond the
 * Skija handles it caches; layout comes from {@link WorldSelectLayout} and
 * model state from the supplied managers.
 */
public final class SkijaWorldSelectRenderer {

    private static final int COLOR_TEXT_PRIMARY      = 0xFFF7F7F2;
    private static final int COLOR_TEXT_SHADOW       = 0xFF1A1A1A;
    private static final int COLOR_TEXT_SECONDARY    = 0xFFB8B8B0;
    private static final int COLOR_TEXT_ACCENT       = 0xFFFFCC55;
    private static final int COLOR_TEXT_DISABLED     = 0xFF787878;
    private static final int COLOR_TEXT_ERROR        = 0xFFFF6464;

    private static final int COLOR_OVERLAY_DARK      = 0x66000000;
    private static final int COLOR_OVERLAY_DEEP      = 0xCC000000;

    private static final int COLOR_ITEM_FILL         = 0xFF3A3D42;
    private static final int COLOR_ITEM_HOVER        = 0xFF4A5570;
    private static final int COLOR_ITEM_SELECTED     = 0xFF6A82C8;
    private static final int COLOR_ITEM_BORDER       = 0xFF1F1F1F;

    private static final int COLOR_INPUT_FILL        = 0xFF1E1E1E;
    private static final int COLOR_INPUT_BORDER      = 0xFF505050;
    private static final int COLOR_INPUT_BORDER_HOT  = 0xFF6496FF;

    private final SkijaUIBackend backend;
    private final WorldStateManager stateManager;
    private final WorldDiscoveryManager discoveryManager;
    private final WorldInputHandler inputHandler;

    private Font fontTitle;
    private Font fontSubtitle;
    private Font fontButton;
    private Font fontItem;
    private Font fontMeta;
    private Font fontInput;

    private Shader dirtShader;
    private long lastCursorBlink = System.currentTimeMillis();
    private boolean cursorVisible = true;

    public SkijaWorldSelectRenderer(SkijaUIBackend backend,
                                    WorldStateManager stateManager,
                                    WorldDiscoveryManager discoveryManager,
                                    WorldInputHandler inputHandler) {
        this.backend = backend;
        this.stateManager = stateManager;
        this.discoveryManager = discoveryManager;
        this.inputHandler = inputHandler;
    }

    private void ensureFonts() {
        if (fontTitle != null) return;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle    = new Font(tf, 56f);
        fontSubtitle = new Font(tf, 18f);
        fontButton   = new Font(tf, 20f);
        fontItem     = new Font(tf, 22f);
        fontMeta     = new Font(tf, 14f);
        fontInput    = new Font(tf, 18f);
    }

    private void ensureDirtShader() {
        if (dirtShader != null) return;
        Image dirt = backend.getDirtTexture();
        if (dirt == null) return;
        dirtShader = dirt.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!backend.isAvailable()) return;
        ensureFonts();
        ensureDirtShader();

        WorldSelectLayout layout = WorldSelectLayout.compute(windowWidth, windowHeight);
        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            drawBackground(canvas, windowWidth, windowHeight);
            drawTitle(canvas, layout);
            drawListPanel(canvas, layout);
            drawWorldList(canvas, layout);
            drawScrollbar(canvas, layout);
            drawActionButtons(canvas, layout);

            if (stateManager.isShowCreateDialog()) {
                drawCreateDialog(canvas, layout, windowWidth, windowHeight);
            }
            if (stateManager.isShowDeleteDialog()) {
                drawDeleteDialog(canvas, layout, windowWidth, windowHeight);
            }
        } finally {
            backend.endFrame();
        }
    }

    // ─────────────────────────────────────────────────────────── Background

    private void drawBackground(Canvas canvas, int w, int h) {
        // Solid base in case dirt fails to load
        try (Paint p = new Paint().setColor(0xFF2C2C2C)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
        if (dirtShader != null) {
            try (Paint p = new Paint().setShader(dirtShader)) {
                canvas.save();
                canvas.scale(4f, 4f);
                canvas.drawRect(Rect.makeXYWH(0, 0, w / 4f, h / 4f), p);
                canvas.restore();
            }
        }
        // Vignette
        try (Paint p = new Paint().setColor(COLOR_OVERLAY_DARK)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
        // Top + bottom darkening bands give the screen a stage feel
        try (Paint p = new Paint().setColor(0x80000000)) {
            float bandH = h * 0.14f;
            canvas.drawRect(Rect.makeXYWH(0, 0, w, bandH), p);
            canvas.drawRect(Rect.makeXYWH(0, h - bandH, w, bandH), p);
        }
    }

    // ─────────────────────────────────────────────────────────── Title

    private void drawTitle(Canvas canvas, WorldSelectLayout layout) {
        String title = "Select World";
        // Layered drop shadow (matches main-menu style)
        for (int i = 5; i >= 0; i--) {
            int color;
            switch (i) {
                case 0 -> color = COLOR_TEXT_PRIMARY;
                case 1 -> color = 0xFFC8C8B4;
                default -> {
                    int v = Math.max(20, 80 - i * 14);
                    color = (0xC0 << 24) | (v << 16) | (v << 8) | v;
                }
            }
            drawCenteredString(canvas, title, layout.centerX + i * 2.5f, layout.titleY + i * 2.5f, fontTitle, color);
        }
        drawCenteredString(canvas, "Choose a world to load or create a new one",
                layout.centerX, layout.subtitleY, fontSubtitle, COLOR_TEXT_SECONDARY);
    }

    // ─────────────────────────────────────────────────────────── List panel

    private void drawListPanel(Canvas canvas, WorldSelectLayout layout) {
        drawMinecraftPanel(canvas, layout.panelX, layout.panelY,
                WorldSelectLayout.PANEL_WIDTH, WorldSelectLayout.PANEL_HEIGHT);
    }

    private void drawMinecraftPanel(Canvas canvas, float x, float y, float w, float h) {
        MPainter.panel(canvas, x, y, w, h);
    }

    // ─────────────────────────────────────────────────────────── World list

    private void drawWorldList(Canvas canvas, WorldSelectLayout layout) {
        List<String> worlds = stateManager.getWorldList();
        if (worlds.isEmpty()) {
            drawCenteredString(canvas, "No worlds yet — click 'Create New World' to begin.",
                    layout.centerX, layout.listY + WorldSelectLayout.LIST_HEIGHT / 2f,
                    fontItem, COLOR_TEXT_SECONDARY);
            return;
        }
        int start = stateManager.getVisibleStartIndex();
        int end = stateManager.getVisibleEndIndex();
        for (int i = start; i < end; i++) {
            float itemY = layout.listY + (i - start) * WorldSelectLayout.ITEM_HEIGHT;
            drawWorldItem(canvas, worlds.get(i), i, layout.listX, itemY);
        }
    }

    private void drawWorldItem(Canvas canvas, String name, int index, float x, float y) {
        boolean selected = index == stateManager.getSelectedIndex();
        boolean hovered = index == stateManager.getHoveredIndex();
        int fill = selected ? COLOR_ITEM_SELECTED : (hovered ? COLOR_ITEM_HOVER : COLOR_ITEM_FILL);

        RRect rect = RRect.makeXYWH(x, y + 4f, WorldSelectLayout.LIST_WIDTH, WorldSelectLayout.ITEM_HEIGHT - 8f, 4f);
        try (Paint p = new Paint().setColor(fill)) {
            canvas.drawRRect(rect, p);
        }
        try (Paint p = new Paint().setColor(COLOR_ITEM_BORDER).setMode(PaintMode.STROKE).setStrokeWidth(1.5f)) {
            canvas.drawRRect(rect, p);
        }
        // Name (with shadow)
        float nameX = x + 18f;
        float nameY = y + 26f;
        drawString(canvas, name, nameX + 1, nameY + 1, fontItem, COLOR_TEXT_SHADOW);
        drawString(canvas, name, nameX, nameY, fontItem, COLOR_TEXT_PRIMARY);
        // Meta
        WorldData data = discoveryManager.getWorldData(name);
        String meta = formatMeta(data);
        if (meta != null) {
            drawString(canvas, meta, nameX, y + 46f, fontMeta, COLOR_TEXT_SECONDARY);
        }
    }

    private String formatMeta(WorldData data) {
        if (data == null) return null;
        StringBuilder sb = new StringBuilder();
        if (data.getLastPlayed() != null) {
            LocalDateTime dt = data.getLastPlayed();
            LocalDateTime now = LocalDateTime.now();
            if (dt.toLocalDate().equals(now.toLocalDate())) {
                sb.append("Last played today at ").append(String.format("%d:%02d", dt.getHour(), dt.getMinute()));
            } else {
                sb.append(String.format("Last played %d/%d/%d", dt.getMonthValue(), dt.getDayOfMonth(), dt.getYear()));
            }
        }
        if (data.getSeed() != 0) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("Seed: ").append(data.getSeed());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void drawScrollbar(Canvas canvas, WorldSelectLayout layout) {
        int total = stateManager.getWorldList().size();
        if (total <= WorldSelectLayout.ITEMS_PER_PAGE) return;
        try (Paint track = new Paint().setColor(0x80000000);
             Paint thumb = new Paint().setColor(0xFFB4B4B4)) {
            canvas.drawRRect(RRect.makeXYWH(layout.scrollbarX, layout.listY, 6f, WorldSelectLayout.LIST_HEIGHT, 3f), track);
            float thumbH = WorldSelectLayout.LIST_HEIGHT * WorldSelectLayout.ITEMS_PER_PAGE / (float) total;
            float thumbY = layout.listY + (WorldSelectLayout.LIST_HEIGHT - thumbH)
                    * stateManager.getScrollOffset() / Math.max(1, total - WorldSelectLayout.ITEMS_PER_PAGE);
            canvas.drawRRect(RRect.makeXYWH(layout.scrollbarX, thumbY, 6f, thumbH, 3f), thumb);
        }
    }

    // ─────────────────────────────────────────────────────────── Buttons

    private void drawActionButtons(Canvas canvas, WorldSelectLayout layout) {
        boolean hasSelection = stateManager.hasWorlds()
                && stateManager.getSelectedIndex() >= 0
                && stateManager.getSelectedIndex() < stateManager.getWorldList().size();
        String hov = stateManager.getHoveredButton();
        drawMinecraftButton(canvas, "Back", layout.backButtonX, layout.backButtonY,
                WorldSelectLayout.ACTION_BUTTON_WIDTH, WorldSelectLayout.ACTION_BUTTON_HEIGHT,
                "back".equals(hov), true);
        drawMinecraftButton(canvas, "Create World", layout.createButtonX, layout.createButtonY,
                WorldSelectLayout.ACTION_BUTTON_WIDTH, WorldSelectLayout.ACTION_BUTTON_HEIGHT,
                "create".equals(hov), true);
        drawMinecraftButton(canvas, "Delete World", layout.deleteButtonX, layout.deleteButtonY,
                WorldSelectLayout.ACTION_BUTTON_WIDTH, WorldSelectLayout.ACTION_BUTTON_HEIGHT,
                hasSelection && "delete".equals(hov), hasSelection);
        drawMinecraftButton(canvas, "Play Selected", layout.playButtonX, layout.playButtonY,
                WorldSelectLayout.ACTION_BUTTON_WIDTH, WorldSelectLayout.ACTION_BUTTON_HEIGHT,
                hasSelection && "play".equals(hov), hasSelection);
    }

    private void drawMinecraftButton(Canvas canvas, String text, float x, float y, float w, float h, boolean highlight, boolean enabled) {
        int fill = !enabled ? MStyle.BUTTON_FILL_DIS
                : (highlight ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL);
        MPainter.stoneSurface(canvas, x, y, w, h, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

        int textColor = enabled ? (highlight ? COLOR_TEXT_ACCENT : COLOR_TEXT_PRIMARY) : COLOR_TEXT_DISABLED;
        float tx = x + w / 2f;
        float ty = y + h / 2f + 7f;
        MPainter.drawCenteredStringWithShadow(canvas, text, tx, ty, fontButton, textColor, COLOR_TEXT_SHADOW);
    }

    // ─────────────────────────────────────────────────────────── Create dialog

    private void drawCreateDialog(Canvas canvas, WorldSelectLayout layout, int w, int h) {
        try (Paint p = new Paint().setColor(COLOR_OVERLAY_DEEP)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
        drawMinecraftPanel(canvas, layout.dialogX, layout.dialogY,
                WorldSelectLayout.DIALOG_WIDTH, WorldSelectLayout.DIALOG_HEIGHT);

        drawCenteredString(canvas, "Create New World", layout.centerX + 1, layout.dialogY + 41, fontButton, COLOR_TEXT_SHADOW);
        drawCenteredString(canvas, "Create New World", layout.centerX, layout.dialogY + 40, fontButton, COLOR_TEXT_ACCENT);

        boolean nameActive = inputHandler.isNameInputMode();
        drawString(canvas, "World Name", layout.nameFieldX, layout.nameFieldY - 8, fontMeta, COLOR_TEXT_SECONDARY);
        drawInputField(canvas, layout.nameFieldX, layout.nameFieldY,
                stateManager.getNewWorldName(), nameActive, "e.g. New World");

        drawString(canvas, "Seed (optional)", layout.seedFieldX, layout.seedFieldY - 8, fontMeta, COLOR_TEXT_SECONDARY);
        drawInputField(canvas, layout.seedFieldX, layout.seedFieldY,
                stateManager.getNewWorldSeed(), !nameActive, "leave blank for random");

        boolean canCreate = stateManager.isValidWorldName();
        String hov = stateManager.getHoveredButton();
        drawMinecraftButton(canvas, "Create", layout.dialogCreateX, layout.dialogButtonY,
                WorldSelectLayout.DIALOG_BUTTON_WIDTH, WorldSelectLayout.DIALOG_BUTTON_HEIGHT,
                canCreate && "dialog-create".equals(hov), canCreate);
        drawMinecraftButton(canvas, "Cancel", layout.dialogCancelX, layout.dialogButtonY,
                WorldSelectLayout.DIALOG_BUTTON_WIDTH, WorldSelectLayout.DIALOG_BUTTON_HEIGHT,
                "dialog-cancel".equals(hov), true);

        // Validation hint
        String typed = stateManager.getNewWorldName().trim();
        if (!typed.isEmpty() && !canCreate) {
            drawCenteredString(canvas, "A world with that name already exists",
                    layout.centerX, layout.dialogButtonY - 14, fontMeta, COLOR_TEXT_ERROR);
        }
    }

    private void drawDeleteDialog(Canvas canvas, WorldSelectLayout layout, int w, int h) {
        try (Paint p = new Paint().setColor(COLOR_OVERLAY_DEEP)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
        drawMinecraftPanel(canvas, layout.confirmDialogX, layout.confirmDialogY,
                WorldSelectLayout.CONFIRM_DIALOG_WIDTH, WorldSelectLayout.CONFIRM_DIALOG_HEIGHT);

        drawCenteredString(canvas, "Delete World?", layout.centerX + 1, layout.confirmDialogY + 41, fontButton, COLOR_TEXT_SHADOW);
        drawCenteredString(canvas, "Delete World?", layout.centerX, layout.confirmDialogY + 40, fontButton, COLOR_TEXT_ERROR);

        String target = stateManager.getWorldPendingDelete();
        if (target != null) {
            drawCenteredString(canvas, "\"" + target + "\" will be permanently removed.",
                    layout.centerX, layout.confirmDialogY + 80, fontMeta, COLOR_TEXT_SECONDARY);
            drawCenteredString(canvas, "This cannot be undone.",
                    layout.centerX, layout.confirmDialogY + 100, fontMeta, COLOR_TEXT_SECONDARY);
        }

        String hov = stateManager.getHoveredButton();
        drawMinecraftButton(canvas, "Delete", layout.confirmConfirmX, layout.confirmButtonY,
                WorldSelectLayout.DIALOG_BUTTON_WIDTH, WorldSelectLayout.DIALOG_BUTTON_HEIGHT,
                "confirm-delete".equals(hov), true);
        drawMinecraftButton(canvas, "Cancel", layout.confirmCancelX, layout.confirmButtonY,
                WorldSelectLayout.DIALOG_BUTTON_WIDTH, WorldSelectLayout.DIALOG_BUTTON_HEIGHT,
                "confirm-cancel".equals(hov), true);
    }

    private void drawInputField(Canvas canvas, float x, float y, String text, boolean focused, String placeholder) {
        try (Paint fill = new Paint().setColor(COLOR_INPUT_FILL)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, WorldSelectLayout.DIALOG_INPUT_WIDTH,
                    WorldSelectLayout.DIALOG_INPUT_HEIGHT, 4f), fill);
        }
        int border = focused ? COLOR_INPUT_BORDER_HOT : COLOR_INPUT_BORDER;
        try (Paint p = new Paint().setColor(border).setMode(PaintMode.STROKE).setStrokeWidth(focused ? 2.5f : 1.5f)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, WorldSelectLayout.DIALOG_INPUT_WIDTH,
                    WorldSelectLayout.DIALOG_INPUT_HEIGHT, 4f), p);
        }
        float textY = y + WorldSelectLayout.DIALOG_INPUT_HEIGHT / 2f + 6f;
        if (text == null || text.isEmpty()) {
            drawString(canvas, placeholder, x + 12f, textY, fontInput, COLOR_TEXT_DISABLED);
        } else {
            drawString(canvas, text, x + 12f, textY, fontInput, COLOR_TEXT_PRIMARY);
        }
        if (focused) {
            updateCursorBlink();
            if (cursorVisible) {
                float caretX = x + 12f + measureWidthSafe(fontInput, text);
                try (Paint p = new Paint().setColor(COLOR_TEXT_PRIMARY)) {
                    canvas.drawRect(Rect.makeXYWH(caretX, y + 6f, 2f, WorldSelectLayout.DIALOG_INPUT_HEIGHT - 12f), p);
                }
            }
        }
    }

    /**
     * Skija's native measureTextWidth throws "Invalid UTF-16 string (unpaired
     * surrogate)" when handed an empty string or any string containing a lone
     * surrogate half. The character callback already filters non-BMP
     * codepoints, but defensive guarding here keeps a stray surrogate from
     * taking the whole screen down.
     */
    private static float measureWidthSafe(Font font, String text) {
        if (text == null || text.isEmpty()) return 0f;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isSurrogate(text.charAt(i))) return 0f;
        }
        return font.measureTextWidth(text);
    }

    private void updateCursorBlink() {
        long now = System.currentTimeMillis();
        if (now - lastCursorBlink > 500L) {
            cursorVisible = !cursorVisible;
            lastCursorBlink = now;
        }
    }

    // ─────────────────────────────────────────────────────────── Text helpers

    private void drawString(Canvas canvas, String text, float x, float y, Font font, int color) {
        if (text == null || text.isEmpty()) return;
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawString(text, x, y, font, p);
        }
    }

    private void drawCenteredString(Canvas canvas, String text, float cx, float y, Font font, int color) {
        if (text == null || text.isEmpty()) return;
        float width = font.measureTextWidth(text);
        try (Paint p = new Paint().setColor(color)) {
            canvas.drawString(text, cx - width / 2f, y, font, p);
        }
    }

    public void dispose() {
        if (dirtShader != null) { dirtShader.close(); dirtShader = null; }
        if (fontTitle != null) { fontTitle.close(); fontTitle = null; }
        if (fontSubtitle != null) { fontSubtitle.close(); fontSubtitle = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
        if (fontItem != null) { fontItem.close(); fontItem = null; }
        if (fontMeta != null) { fontMeta.close(); fontMeta = null; }
        if (fontInput != null) { fontInput.close(); fontInput = null; }
    }
}
