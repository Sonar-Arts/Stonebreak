package com.stonebreak.ui.chat.emoji;

import com.stonebreak.rendering.UI.masonryUI.MFonts;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders the floating emoji-picker overlay and provides hit-test queries.
 *
 * <p>All geometry flows from the anchor point passed to each method (the
 * right edge + top edge of the emoji button), so the renderer itself
 * is stateless — all picker state lives in {@link ChatEmojiSystem}.
 */
public final class EmojiPickerRenderer {

    private static final float PANEL_W        = 148f;
    private static final float PADDING        = 8f;
    private static final float CELL_SIZE      = 32f;
    private static final float CELL_GAP       = 6f;
    private static final float SECTION_H      = 17f;  // header row height
    private static final float SECTION_GAP    = 6f;   // gap between sections
    private static final float EMPTY_ROW_H    = 18f;  // "None yet" text row
    private static final float STAR_SIZE      = 8f;   // favourite indicator square
    private static final float PICKER_GAP     = 4f;   // gap between panel bottom and button top

    private final MFonts fonts;

    public EmojiPickerRenderer(MFonts fonts) {
        this.fonts = fonts;
    }

    // ─────────────────────────────────────────── Layout computation

    private record SectionLayout(float headerY, float contentY, float contentH, List<EmojiType> emojis) {}

    private record PickerLayout(float panelX, float panelY, float panelH,
                                SectionLayout favs, SectionLayout recent, SectionLayout all) {}

    private static float contentH(List<EmojiType> emojis) {
        return emojis.isEmpty() ? EMPTY_ROW_H : CELL_SIZE + 4f;
    }

    /**
     * @param anchorRight right edge of the emoji button
     * @param anchorTop   top edge of the emoji button
     */
    private PickerLayout computeLayout(ChatEmojiSystem state, float anchorRight, float anchorTop) {
        List<EmojiType> favs = new ArrayList<>(state.getFavorites());
        favs.sort(Comparator.comparingInt(EmojiType::ordinal));
        List<EmojiType> recent = state.getRecentlyUsed();
        List<EmojiType> all = List.of(EmojiType.values());

        float favH    = contentH(favs);
        float recentH = contentH(recent);
        float allH    = contentH(all);

        float panelH = PADDING
                + SECTION_H + favH + SECTION_GAP
                + SECTION_H + recentH + SECTION_GAP
                + SECTION_H + allH
                + PADDING;

        float panelX = anchorRight - PANEL_W;
        float panelY = anchorTop - panelH - PICKER_GAP;

        float y = panelY + PADDING;

        float favHeaderY  = y;
        float favContentY = y + SECTION_H;
        y = favContentY + favH + SECTION_GAP;

        float recentHeaderY  = y;
        float recentContentY = y + SECTION_H;
        y = recentContentY + recentH + SECTION_GAP;

        float allHeaderY  = y;
        float allContentY = y + SECTION_H;

        return new PickerLayout(panelX, panelY, panelH,
                new SectionLayout(favHeaderY,    favContentY,    favH,    favs),
                new SectionLayout(recentHeaderY, recentContentY, recentH, recent),
                new SectionLayout(allHeaderY,    allContentY,    allH,    all));
    }

    // ─────────────────────────────────────────── Rendering

    public void draw(Canvas canvas, ChatEmojiSystem state,
                     float anchorRight, float anchorTop,
                     float mouseX, float mouseY) {
        PickerLayout L = computeLayout(state, anchorRight, anchorTop);

        // Panel background
        MPainter.panel(canvas, L.panelX, L.panelY, PANEL_W, L.panelH);

        Font headerFont = fonts.get(MStyle.FONT_META);
        Font smallFont  = fonts.get(12f);

        drawSection(canvas, L.panelX, L.favs,    "Favorites",   state.getFavorites().isEmpty(),
                state, mouseX, mouseY, headerFont, smallFont);
        drawSectionSeparator(canvas, L.panelX, L.favs.contentY() + L.favs.contentH());

        drawSection(canvas, L.panelX, L.recent,  "Recent",      state.getRecentlyUsed().isEmpty(),
                state, mouseX, mouseY, headerFont, smallFont);
        drawSectionSeparator(canvas, L.panelX, L.recent.contentY() + L.recent.contentH());

        drawSection(canvas, L.panelX, L.all,     "All",         false,
                state, mouseX, mouseY, headerFont, smallFont);
    }

    private void drawSection(Canvas canvas, float panelX, SectionLayout sec,
                             String label, boolean isEmpty,
                             ChatEmojiSystem state, float mx, float my,
                             Font headerFont, Font smallFont) {
        // Header
        float baseline = sec.headerY() + SECTION_H * 0.75f;
        MPainter.drawString(canvas, label, panelX + PADDING, baseline,
                headerFont, MStyle.TEXT_SECONDARY);

        if (isEmpty) {
            float textBaseline = sec.contentY() + EMPTY_ROW_H * 0.75f;
            MPainter.drawString(canvas, "None yet", panelX + PADDING, textBaseline,
                    smallFont, withAlpha(MStyle.TEXT_DISABLED, 0.6f));
            return;
        }

        float cellY = sec.contentY() + 2f;
        for (int i = 0; i < sec.emojis().size(); i++) {
            EmojiType emoji = sec.emojis().get(i);
            float cellX = panelX + PADDING + i * (CELL_SIZE + CELL_GAP);
            drawEmojiCell(canvas, emoji, cellX, cellY, state, mx, my);
        }
    }

    private void drawEmojiCell(Canvas canvas, EmojiType emoji,
                                float cx, float cy,
                                ChatEmojiSystem state,
                                float mx, float my) {
        boolean hovered = mx >= cx && mx <= cx + CELL_SIZE
                && my >= cy && my <= cy + CELL_SIZE;
        boolean favorited = state.getFavorites().contains(emoji);

        // Cell background
        int fill = hovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
        try (Paint p = new Paint().setColor(fill).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(cx, cy, CELL_SIZE, CELL_SIZE, 3f), p);
        }
        if (hovered) {
            try (Paint p = new Paint().setColor(MStyle.TEXT_ACCENT)
                    .setMode(PaintMode.STROKE).setStrokeWidth(1f).setAntiAlias(true)) {
                canvas.drawRRect(RRect.makeXYWH(cx + 0.5f, cy + 0.5f, CELL_SIZE - 1f, CELL_SIZE - 1f, 3f), p);
            }
        }

        // Sprite — centered at 22×22 inside the 32×32 cell
        Image img = EmojiImageCache.get(emoji);
        if (img != null) {
            float spriteSize = CELL_SIZE - 10f; // 22px
            float spriteX = cx + (CELL_SIZE - spriteSize) / 2f;
            float spriteY = cy + (CELL_SIZE - spriteSize) / 2f;
            MPainter.drawImage(canvas, img, spriteX, spriteY, spriteSize, spriteSize);
        }

        // Favourite indicator — small square in top-right corner of cell
        float starX = cx + CELL_SIZE - STAR_SIZE - 1f;
        float starY = cy + 1f;
        boolean starHovered = mx >= starX && mx <= starX + STAR_SIZE
                && my >= starY && my <= starY + STAR_SIZE;

        if (favorited) {
            MPainter.fillRoundedRect(canvas, starX, starY, STAR_SIZE, STAR_SIZE, 2f, MStyle.TEXT_ACCENT);
        } else if (starHovered) {
            MPainter.fillRoundedRect(canvas, starX, starY, STAR_SIZE, STAR_SIZE, 2f,
                    withAlpha(MStyle.TEXT_ACCENT, 0.45f));
        } else {
            MPainter.fillRoundedRect(canvas, starX, starY, STAR_SIZE, STAR_SIZE, 2f,
                    withAlpha(MStyle.TEXT_PRIMARY, 0.12f));
        }
    }

    private static void drawSectionSeparator(Canvas canvas, float panelX, float y) {
        float sepY = y + SECTION_GAP / 2f;
        MPainter.fillRect(canvas, panelX + PADDING, sepY, PANEL_W - PADDING * 2f, 1f,
                withAlpha(MStyle.PANEL_HIGHLIGHT, 0.35f));
    }

    // ─────────────────────────────────────────── Hit-tests

    /** True if {@code (mx,my)} is inside the picker panel. */
    public boolean containsPoint(float mx, float my, float anchorRight, float anchorTop, ChatEmojiSystem state) {
        PickerLayout L = computeLayout(state, anchorRight, anchorTop);
        return mx >= L.panelX && mx <= L.panelX + PANEL_W
                && my >= L.panelY && my <= L.panelY + L.panelH;
    }

    /**
     * Returns the emoji whose sprite area was clicked, or {@code null}.
     * Prioritises star detection — call {@link #getClickedFavoriteStar} first
     * to avoid ambiguity.
     */
    public EmojiType getClickedEmoji(ChatEmojiSystem state, float mx, float my,
                                     float anchorRight, float anchorTop) {
        PickerLayout L = computeLayout(state, anchorRight, anchorTop);
        EmojiType hit = emojiAtPoint(L.favs,   mx, my, L.panelX);
        if (hit == null) hit = emojiAtPoint(L.recent, mx, my, L.panelX);
        if (hit == null) hit = emojiAtPoint(L.all,    mx, my, L.panelX);
        return hit;
    }

    /**
     * Returns the emoji whose favourite-star area was clicked, or {@code null}.
     */
    public EmojiType getClickedFavoriteStar(ChatEmojiSystem state, float mx, float my,
                                            float anchorRight, float anchorTop) {
        PickerLayout L = computeLayout(state, anchorRight, anchorTop);
        EmojiType hit = starAtPoint(L.favs,   mx, my, L.panelX);
        if (hit == null) hit = starAtPoint(L.recent, mx, my, L.panelX);
        if (hit == null) hit = starAtPoint(L.all,    mx, my, L.panelX);
        return hit;
    }

    private static EmojiType emojiAtPoint(SectionLayout sec, float mx, float my, float panelX) {
        if (sec.emojis().isEmpty()) return null;
        float cellY = sec.contentY() + 2f;
        for (int i = 0; i < sec.emojis().size(); i++) {
            float cellX = panelX + PADDING + i * (CELL_SIZE + CELL_GAP);
            if (mx >= cellX && mx <= cellX + CELL_SIZE
                    && my >= cellY && my <= cellY + CELL_SIZE) {
                return sec.emojis().get(i);
            }
        }
        return null;
    }

    private static EmojiType starAtPoint(SectionLayout sec, float mx, float my, float panelX) {
        if (sec.emojis().isEmpty()) return null;
        float cellY = sec.contentY() + 2f;
        for (int i = 0; i < sec.emojis().size(); i++) {
            float cellX  = panelX + PADDING + i * (CELL_SIZE + CELL_GAP);
            float starX  = cellX + CELL_SIZE - STAR_SIZE - 1f;
            float starY  = cellY + 1f;
            if (mx >= starX && mx <= starX + STAR_SIZE
                    && my >= starY && my <= starY + STAR_SIZE) {
                return sec.emojis().get(i);
            }
        }
        return null;
    }

    // ─────────────────────────────────────────── Helpers

    private static int withAlpha(int argb, float scale) {
        int a = Math.round(((argb >>> 24) & 0xFF) * scale);
        a = Math.max(0, Math.min(255, a));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
