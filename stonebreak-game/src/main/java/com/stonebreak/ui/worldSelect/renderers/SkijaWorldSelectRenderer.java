package com.stonebreak.ui.worldSelect.renderers;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.worldSelect.SectionBounds;
import com.stonebreak.ui.worldSelect.WorldSelectLayout;
import com.stonebreak.ui.worldSelect.handlers.WorldInputHandler;
import com.stonebreak.ui.worldSelect.managers.WorldBackupService;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.ui.worldSelect.managers.WorldStatsService;
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

    /**
     * ASCII stand-ins: the bundled Minecraft typeface has no glyphs for the typographic
     * em dash / ellipsis / middle dot, which draw as tofu boxes.
     */
    private static final String UNKNOWN  = "Unknown";
    private static final String ELLIPSIS = "...";

    private final SkijaUIBackend backend;
    private final WorldStateManager stateManager;
    private final WorldDiscoveryManager discoveryManager;
    private final WorldInputHandler inputHandler;
    private final WorldBackupService backupService;

    private Font fontTitle;
    private Font fontSubtitle;
    private Font fontButton;
    private Font fontItem;
    private Font fontMeta;
    private Font fontInput;
    private float lastFontScale = -1f;

    private Shader dirtShader;
    private long lastCursorBlink = System.currentTimeMillis();
    private boolean cursorVisible = true;

    public SkijaWorldSelectRenderer(SkijaUIBackend backend,
                                    WorldStateManager stateManager,
                                    WorldDiscoveryManager discoveryManager,
                                    WorldInputHandler inputHandler,
                                    WorldBackupService backupService) {
        this.backend = backend;
        this.stateManager = stateManager;
        this.discoveryManager = discoveryManager;
        this.inputHandler = inputHandler;
        this.backupService = backupService;
    }

    private void ensureFonts(float scale) {
        if (fontTitle != null && scale == lastFontScale) return;
        disposeFonts();
        lastFontScale = scale;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle    = new Font(tf, 56f * scale);
        fontSubtitle = new Font(tf, 18f * scale);
        fontButton   = new Font(tf, 20f * scale);
        fontItem     = new Font(tf, 22f * scale);
        fontMeta     = new Font(tf, 14f * scale);
        fontInput    = new Font(tf, 18f * scale);
    }

    private void disposeFonts() {
        if (fontTitle != null) { fontTitle.close(); fontTitle = null; }
        if (fontSubtitle != null) { fontSubtitle.close(); fontSubtitle = null; }
        if (fontButton != null) { fontButton.close(); fontButton = null; }
        if (fontItem != null) { fontItem.close(); fontItem = null; }
        if (fontMeta != null) { fontMeta.close(); fontMeta = null; }
        if (fontInput != null) { fontInput.close(); fontInput = null; }
    }

    private void ensureDirtShader() {
        if (dirtShader != null) return;
        Image dirt = backend.getDirtTexture();
        if (dirt == null) return;
        dirtShader = dirt.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!backend.isAvailable()) return;
        ensureFonts(com.stonebreak.config.Settings.getInstance().getUiScale());
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
            drawInfoCard(canvas, layout);

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
                layout.panelWidth, layout.panelHeight);
    }

    private void drawMinecraftPanel(Canvas canvas, float x, float y, float w, float h) {
        MPainter.panel(canvas, x, y, w, h);
    }

    // ─────────────────────────────────────────────────────────── World list

    private void drawWorldList(Canvas canvas, WorldSelectLayout layout) {
        List<String> worlds = stateManager.getWorldList();
        if (worlds.isEmpty()) {
            drawCenteredString(canvas, "No worlds yet - click 'Create New World' to begin.",
                    layout.centerX, layout.listY + layout.listHeight / 2f,
                    fontItem, COLOR_TEXT_SECONDARY);
            return;
        }
        int start = stateManager.getVisibleStartIndex();
        int end = stateManager.getVisibleEndIndex();
        for (int i = start; i < end; i++) {
            float itemY = layout.listY + (i - start) * layout.itemHeight;
            drawWorldItem(canvas, worlds.get(i), i, layout.listX, itemY, layout);
        }
    }

    private void drawWorldItem(Canvas canvas, String name, int index, float x, float y, WorldSelectLayout layout) {
        boolean selected = index == stateManager.getSelectedIndex();
        boolean hovered = index == stateManager.getHoveredIndex();
        int fill = selected ? COLOR_ITEM_SELECTED : (hovered ? COLOR_ITEM_HOVER : COLOR_ITEM_FILL);

        RRect rect = RRect.makeXYWH(x, y + 4f, layout.listWidth, layout.itemHeight - 8f, 4f);
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
        // On-disk size, right-aligned on the name row. Blank until the background
        // scan for this world finishes, then it stays cached.
        long sizeBytes = discoveryManager.getWorldSizeBytes(name);
        if (sizeBytes >= 0) {
            String size = WorldStatsService.formatSize(sizeBytes);
            float sizeX = x + layout.listWidth - 18f - measureWidthSafe(fontMeta, size);
            drawString(canvas, size, sizeX + 1, nameY + 1, fontMeta, COLOR_TEXT_SHADOW);
            drawString(canvas, size, sizeX, nameY, fontMeta, COLOR_TEXT_SECONDARY);
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
            if (sb.length() > 0) sb.append("   -   ");
            sb.append("Seed: ").append(data.getSeed());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void drawScrollbar(Canvas canvas, WorldSelectLayout layout) {
        int total = stateManager.getWorldList().size();
        if (total <= WorldSelectLayout.ITEMS_PER_PAGE) return;
        try (Paint track = new Paint().setColor(0x80000000);
             Paint thumb = new Paint().setColor(0xFFB4B4B4)) {
            canvas.drawRRect(RRect.makeXYWH(layout.scrollbarX, layout.listY, 6f, layout.listHeight, 3f), track);
            float thumbH = layout.listHeight * WorldSelectLayout.ITEMS_PER_PAGE / (float) total;
            float thumbY = layout.listY + (layout.listHeight - thumbH)
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
                layout.actionButtonWidth, layout.actionButtonHeight,
                "back".equals(hov), true);
        drawMinecraftButton(canvas, "Create World", layout.createButtonX, layout.createButtonY,
                layout.actionButtonWidth, layout.actionButtonHeight,
                "create".equals(hov), true);
        drawMinecraftButton(canvas, "Delete World", layout.deleteButtonX, layout.deleteButtonY,
                layout.actionButtonWidth, layout.actionButtonHeight,
                hasSelection && "delete".equals(hov), hasSelection);
        drawMinecraftButton(canvas, "Play Selected", layout.playButtonX, layout.playButtonY,
                layout.actionButtonWidth, layout.actionButtonHeight,
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
                layout.dialogWidth, layout.dialogHeight);

        drawCenteredString(canvas, "Create New World", layout.centerX + 1, layout.dialogY + 41, fontButton, COLOR_TEXT_SHADOW);
        drawCenteredString(canvas, "Create New World", layout.centerX, layout.dialogY + 40, fontButton, COLOR_TEXT_ACCENT);

        boolean nameActive = inputHandler.isNameInputMode();
        drawString(canvas, "World Name", layout.nameFieldX, layout.nameFieldY - 8, fontMeta, COLOR_TEXT_SECONDARY);
        drawInputField(canvas, layout.nameFieldX, layout.nameFieldY, layout,
                stateManager.getNewWorldName(), nameActive, "e.g. New World");

        drawString(canvas, "Seed (optional)", layout.seedFieldX, layout.seedFieldY - 8, fontMeta, COLOR_TEXT_SECONDARY);
        drawInputField(canvas, layout.seedFieldX, layout.seedFieldY, layout,
                stateManager.getNewWorldSeed(), !nameActive, "leave blank for random");

        boolean canCreate = stateManager.isValidWorldName();
        String hov = stateManager.getHoveredButton();
        drawMinecraftButton(canvas, "Create", layout.dialogCreateX, layout.dialogButtonY,
                layout.dialogButtonWidth, layout.dialogButtonHeight,
                canCreate && "dialog-create".equals(hov), canCreate);
        drawMinecraftButton(canvas, "Cancel", layout.dialogCancelX, layout.dialogButtonY,
                layout.dialogButtonWidth, layout.dialogButtonHeight,
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
                layout.confirmDialogWidth, layout.confirmDialogHeight);

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
                layout.dialogButtonWidth, layout.dialogButtonHeight,
                "confirm-delete".equals(hov), true);
        drawMinecraftButton(canvas, "Cancel", layout.confirmCancelX, layout.confirmButtonY,
                layout.dialogButtonWidth, layout.dialogButtonHeight,
                "confirm-cancel".equals(hov), true);
    }

    // ─────────────────────────────────────────────────────────── Hover info card

    /**
     * Draws the sticky info card for the world the cursor is resting on. The card is
     * anchored to its row by {@link WorldSelectLayout#cardBounds(int)}; the same bounds
     * drive hit-testing, so what is clickable is exactly what is drawn.
     */
    private void drawInfoCard(Canvas canvas, WorldSelectLayout layout) {
        String world = stateManager.getCardWorld();
        if (world == null) return;
        int visibleRow = stateManager.getCardIndex() - stateManager.getScrollOffset();
        if (visibleRow < 0 || visibleRow >= WorldSelectLayout.ITEMS_PER_PAGE) return;

        SectionBounds card = layout.cardBounds(visibleRow);
        float s = layout.uiScale;
        drawMinecraftPanel(canvas, card.x, card.y, card.width, card.height);

        float left = card.x + layout.cardPadding;
        float right = card.getRight() - layout.cardPadding;
        float innerWidth = right - left;

        // Title
        String title = ellipsize(world, fontItem, innerWidth);
        drawString(canvas, title, left + 1, card.y + 21f * s, fontItem, COLOR_TEXT_SHADOW);
        drawString(canvas, title, left, card.y + 20f * s, fontItem, COLOR_TEXT_ACCENT);
        try (Paint p = new Paint().setColor(COLOR_ITEM_BORDER)) {
            canvas.drawRect(Rect.makeXYWH(left, card.y + 30f * s, innerWidth, 1f), p);
        }

        // Info rows
        WorldData data = discoveryManager.getWorldData(world);
        WorldStatsService.Stats stats = discoveryManager.getWorldStats(world);
        String pending = "Measuring...";

        float rowY = card.y + 52f * s;
        rowY = drawCardRow(canvas, left, right, rowY, layout,
                "Size", stats == null ? pending : WorldStatsService.formatSize(stats.bytes()));
        rowY = drawCardRow(canvas, left, right, rowY, layout,
                "Chunks", stats == null ? pending : String.format("%,d", stats.chunkCount()));
        rowY = drawCardRow(canvas, left, right, rowY, layout,
                "Seed", data == null ? UNKNOWN : Long.toString(data.getSeed()));
        rowY = drawCardRow(canvas, left, right, rowY, layout,
                "Created", data == null ? UNKNOWN : formatDate(data.getCreatedTime()));
        rowY = drawCardRow(canvas, left, right, rowY, layout,
                "Last played", data == null ? UNKNOWN : formatDate(data.getLastPlayed()));
        drawCardRow(canvas, left, right, rowY, layout,
                "Play time", data == null ? UNKNOWN : formatPlayTime(data.getTotalPlayTimeMillis()));

        // Buttons
        String hov = stateManager.getHoveredCardButton();
        WorldBackupService.Status status = backupService.getStatus(world);
        boolean backupRunning = status.state() == WorldBackupService.State.RUNNING;

        SectionBounds folderBtn = layout.cardOpenFolderBounds(card);
        SectionBounds backupBtn = layout.cardBackupBounds(card);
        drawCardButton(canvas, "Open Folder", folderBtn, "card-folder".equals(hov), true);
        drawCardButton(canvas, backupRunning ? "Backing Up" : "Back Up", backupBtn,
                !backupRunning && "card-backup".equals(hov), !backupRunning);

        // Status line, plus a progress bar while a backup is running
        if (status.state() != WorldBackupService.State.IDLE) {
            int color = switch (status.state()) {
                case FAILED -> COLOR_TEXT_ERROR;
                case DONE -> COLOR_TEXT_ACCENT;
                default -> COLOR_TEXT_SECONDARY;
            };
            float statusY = card.getBottom() - layout.cardPadding - 6f * s;
            drawString(canvas, ellipsize(status.message(), fontMeta, innerWidth),
                    left, statusY, fontMeta, color);
            if (backupRunning) {
                float barY = card.getBottom() - layout.cardPadding + 1f * s;
                try (Paint track = new Paint().setColor(0x80000000);
                     Paint fill = new Paint().setColor(COLOR_ITEM_SELECTED)) {
                    canvas.drawRect(Rect.makeXYWH(left, barY, innerWidth, 3f * s), track);
                    canvas.drawRect(Rect.makeXYWH(left, barY, innerWidth * status.progress(), 3f * s), fill);
                }
            }
        }
    }

    /** Draws one "label ..... value" line and returns the baseline of the next one. */
    private float drawCardRow(Canvas canvas, float left, float right, float baselineY,
                              WorldSelectLayout layout, String label, String value) {
        drawString(canvas, label, left, baselineY, fontMeta, COLOR_TEXT_SECONDARY);
        float labelEnd = left + measureWidthSafe(fontMeta, label) + 8f;
        String shown = ellipsize(value, fontMeta, Math.max(0f, right - labelEnd));
        float valueX = right - measureWidthSafe(fontMeta, shown);
        drawString(canvas, shown, valueX + 1, baselineY + 1, fontMeta, COLOR_TEXT_SHADOW);
        drawString(canvas, shown, valueX, baselineY, fontMeta, COLOR_TEXT_PRIMARY);
        return baselineY + layout.cardLineHeight;
    }

    private void drawCardButton(Canvas canvas, String text, SectionBounds bounds, boolean highlight, boolean enabled) {
        int fill = !enabled ? MStyle.BUTTON_FILL_DIS
                : (highlight ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL);
        MPainter.stoneSurface(canvas, bounds.x, bounds.y, bounds.width, bounds.height, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        int textColor = enabled ? (highlight ? COLOR_TEXT_ACCENT : COLOR_TEXT_PRIMARY) : COLOR_TEXT_DISABLED;
        MPainter.drawCenteredStringWithShadow(canvas, text, bounds.getCenterX(),
                bounds.getCenterY() + 5f, fontMeta, textColor, COLOR_TEXT_SHADOW);
    }

    private static String formatDate(LocalDateTime dt) {
        if (dt == null) return UNKNOWN;
        LocalDateTime now = LocalDateTime.now();
        if (dt.toLocalDate().equals(now.toLocalDate())) {
            return String.format("Today %d:%02d", dt.getHour(), dt.getMinute());
        }
        return String.format("%d/%d/%d", dt.getMonthValue(), dt.getDayOfMonth(), dt.getYear());
    }

    private static String formatPlayTime(long millis) {
        if (millis <= 0L) return "0m";
        long minutes = millis / 60_000L;
        if (minutes < 1L) return "< 1m";
        long hours = minutes / 60L;
        if (hours < 1L) return minutes + "m";
        return hours + "h " + (minutes % 60L) + "m";
    }

    /** Shortens text with a trailing ellipsis until it fits {@code maxWidth}. */
    private static String ellipsize(String text, Font font, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (measureWidthSafe(font, text) <= maxWidth) return text;
        String truncated = text;
        while (truncated.length() > 1 && measureWidthSafe(font, truncated + ELLIPSIS) > maxWidth) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated + ELLIPSIS;
    }

    private void drawInputField(Canvas canvas, float x, float y, WorldSelectLayout layout,
                                String text, boolean focused, String placeholder) {
        float iw = layout.dialogInputWidth;
        float ih = layout.dialogInputHeight;
        try (Paint fill = new Paint().setColor(COLOR_INPUT_FILL)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, iw, ih, 4f), fill);
        }
        int border = focused ? COLOR_INPUT_BORDER_HOT : COLOR_INPUT_BORDER;
        try (Paint p = new Paint().setColor(border).setMode(PaintMode.STROKE).setStrokeWidth(focused ? 2.5f : 1.5f)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, iw, ih, 4f), p);
        }
        float textY = y + ih / 2f + 6f;
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
                    canvas.drawRect(Rect.makeXYWH(caretX, y + 6f, 2f, ih - 12f), p);
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
        disposeFonts();
    }
}
