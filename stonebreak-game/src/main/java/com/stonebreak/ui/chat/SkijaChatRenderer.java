package com.stonebreak.ui.chat;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MFonts;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.chat.chatSystem.ChatCommandExecutor;
import com.stonebreak.ui.chat.chatSystem.commands.ChatCommand;
import com.stonebreak.ui.chat.emoji.ChatEmojiSystem;
import com.stonebreak.ui.chat.emoji.EmojiImageCache;
import com.stonebreak.ui.chat.emoji.EmojiPickerRenderer;
import com.stonebreak.ui.chat.emoji.EmojiType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skija/MasonryUI-backed renderer for the in-game chat panel. Replaces the
 * NanoVG ChatRenderer with the same stone-surface aesthetic shared by
 * PauseMenu, RecipeScreen, and Settings.
 *
 * State stays in {@link ChatSystem}; this class only paints and exposes
 * hit-test queries the InputHandler calls back into.
 */
public final class SkijaChatRenderer {

    private static final int MAX_VISIBLE_LINES = 10;

    private final SkijaUIBackend backend;
    private final MFonts fonts;
    private final EmojiPickerRenderer emojiPickerRenderer;

    private float lastMouseX;
    private float lastMouseY;

    private boolean isDraggingChatScrollbar;
    private boolean isDraggingCommandScrollbar;
    private float scrollbarDragStartY;

    public SkijaChatRenderer(SkijaUIBackend backend) {
        this.backend = backend;
        this.fonts = new MFonts(backend);
        this.emojiPickerRenderer = new EmojiPickerRenderer(this.fonts);
    }

    // ─────────────────────────────────────────────────────────── Layout

    /**
     * Single source of truth for chat geometry. All hit-test methods and the
     * render path call {@link #compute(int, int)} so the panel, viewports,
     * tabs, scrollbars, input box, and emoji button agree on a frame.
     */
    private record Layout(
            float chatX, float lineHeight, float maxChatWidth,
            float panelX, float panelY, float panelWidth, float panelHeight,
            float padding,
            float inputX, float inputY, float inputWidth, float inputHeight,
            float tabX, float tabY, float tabWidth, float tabHeight, float tabSpacing,
            float viewportX, float viewportY, float viewportWidth, float viewportHeight,
            float scrollbarX, float scrollbarY, float scrollbarWidth, float scrollbarHeight,
            float emojiButtonX, float emojiButtonY, float emojiButtonSize,
            int windowWidth, int windowHeight
    ) {
        static Layout compute(int sw, int sh) {
            float chatX = 20f;
            float lineHeight = 20f;
            float maxChatWidth = sw * 0.4f;
            float padding = 10f;
            float inputBoxHeight = 25f;
            float inputBoxMargin = 10f;

            float panelHeight = (MAX_VISIBLE_LINES * lineHeight) + inputBoxHeight + inputBoxMargin + padding * 2f;
            float panelX = chatX - padding;
            float panelY = sh - panelHeight;
            float panelWidth = maxChatWidth + padding * 2f;

            float tabHeight = 22f;
            float tabSpacing = 2f;
            float tabWidth = 80f;
            float tabX = panelX + 5f;
            float tabY = panelY - tabHeight - tabSpacing;

            // Emoji button — square, sits at the right of the panel inside its padding,
            // vertically centred on the input box.
            float emojiButtonSize = inputBoxHeight;
            float emojiButtonX    = panelX + panelWidth - padding - emojiButtonSize;
            float emojiButtonY    = sh - inputBoxHeight - inputBoxMargin;

            // Shrink the input field so it doesn't overlap the emoji button.
            // inputX - 5 (box x offset) + inputWidth + 10 (box w offset) + 4 gap = emojiButtonX
            float inputX     = chatX;
            float inputY     = sh - inputBoxHeight - inputBoxMargin;
            float inputWidth = emojiButtonX - inputX - 5f - 4f;

            float viewportX = panelX + padding;
            float viewportY = panelY + padding;
            float viewportWidth = panelWidth - padding * 2f;
            float viewportHeight = panelHeight - inputBoxHeight - inputBoxMargin - padding * 2f;

            float scrollbarWidth = 6f;
            float scrollbarX = panelX + panelWidth - scrollbarWidth - 5f;
            float scrollbarY = viewportY;
            float scrollbarHeight = viewportHeight;

            return new Layout(
                    chatX, lineHeight, maxChatWidth,
                    panelX, panelY, panelWidth, panelHeight, padding,
                    inputX, inputY, inputWidth, inputBoxHeight,
                    tabX, tabY, tabWidth, tabHeight, tabSpacing,
                    viewportX, viewportY, viewportWidth, viewportHeight,
                    scrollbarX, scrollbarY, scrollbarWidth, scrollbarHeight,
                    emojiButtonX, emojiButtonY, emojiButtonSize,
                    sw, sh
            );
        }
    }

    // ─────────────────────────────────────────────────────────── Render

    public void render(ChatSystem chat, int sw, int sh) {
        if (chat == null || backend == null || !backend.isAvailable()) return;

        List<ChatMessage> visible = chat.getVisibleMessages();
        boolean open = chat.isOpen();
        if (visible.isEmpty() && !open) return;

        Layout L = Layout.compute(sw, sh);

        backend.beginFrame(sw, sh, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;

            if (open) {
                drawPanel(canvas, L);
                drawTabs(canvas, L, chat);
                ChatSystem.ChatTab tab = chat.getCurrentTab();
                if (tab == ChatSystem.ChatTab.CHAT) {
                    drawMessages(canvas, L, chat, visible, true);
                    drawChatScrollbar(canvas, L, chat);
                } else {
                    drawCommandButtons(canvas, L, chat);
                    drawCommandScrollbar(canvas, L, chat);
                }
                drawInputField(canvas, L, chat);
                drawEmojiButton(canvas, L, chat.isEmojiPickerOpen());
                if (chat.isEmojiPickerOpen()) {
                    drawEmojiPicker(canvas, L, chat.getEmojiSystem());
                }
            } else {
                // Closed chat: floating fading messages, no panel, no input.
                drawMessages(canvas, L, chat, visible, false);
            }
        } finally {
            backend.endFrame();
        }
    }

    // ─────────────────────────────────────────────────────────── Panel + tabs

    private void drawPanel(Canvas canvas, Layout L) {
        MPainter.panel(canvas, L.panelX, L.panelY, L.panelWidth, L.panelHeight);
    }

    private void drawTabs(Canvas canvas, Layout L, ChatSystem chat) {
        ChatSystem.ChatTab active = chat.getCurrentTab();
        drawTab(canvas, L, "Chat",     L.tabX,                          active == ChatSystem.ChatTab.CHAT);
        drawTab(canvas, L, "Commands", L.tabX + L.tabWidth + 3f,        active == ChatSystem.ChatTab.COMMANDS);
    }

    /**
     * Folder-style tab: top corners rounded, flat bottom that visually merges
     * with the panel. Active tab raised by the inactive offset and tinted
     * brighter so the tongue-and-groove illusion reads correctly.
     */
    private void drawTab(Canvas canvas, Layout L, String label, float x, boolean active) {
        float yOffset = active ? 0f : 3f;
        float y = L.tabY + yOffset;
        float h = active ? L.tabHeight + 2f : L.tabHeight;
        float w = L.tabWidth;
        float r = MStyle.PANEL_RADIUS;

        // Top-rounded body. RRect with explicit per-corner radii lets the
        // bottom edge sit flush against the panel border above.
        try (Paint fill = new Paint().setColor(active ? MStyle.PANEL_FILL : MStyle.BUTTON_FILL).setAntiAlias(true)) {
            canvas.drawRRect(
                    RRect.makeXYWH(x, y, w, h, r, r, 0f, 0f),
                    fill);
        }

        // Border traces only top and sides — leaves the bottom edge open so
        // the active tab "punches through" the panel border above it.
        try (Paint border = new Paint().setColor(active ? MStyle.TEXT_ACCENT : MStyle.PANEL_BORDER)
                .setMode(PaintMode.STROKE).setStrokeWidth(active ? 1.5f : 1f).setAntiAlias(true)) {
            canvas.drawRRect(
                    RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1f, h - 1f, r, r, 0f, 0f),
                    border);
        }

        // Subtle top highlight — same trick as MPainter.stoneSurface bevel.
        MPainter.fillRect(canvas, x + 1f, y + 1f, w - 2f, 1f, MStyle.PANEL_HIGHLIGHT);

        Font font = fonts.get(MStyle.FONT_META);
        int color = active ? MStyle.TEXT_PRIMARY : MStyle.TEXT_SECONDARY;
        float ty = y + h / 2f + MStyle.FONT_META * 0.35f;
        MPainter.drawCenteredStringWithShadow(canvas, label, x + w / 2f, ty, font, color, MStyle.TEXT_SHADOW);
    }

    // ─────────────────────────────────────────────────────────── Messages

    private void drawMessages(Canvas canvas, Layout L, ChatSystem chat, List<ChatMessage> visible, boolean open) {
        Font font = fonts.get(MStyle.FONT_META);
        if (font == null) return;

        // Bottom-up stacking matches the old renderer: newest at the bottom,
        // older messages walk up the panel.
        float currentY = open
                ? L.panelY + L.panelHeight - L.inputHeight - L.padding - 6f
                : L.windowHeight - 30f;
        long previousId = -1L;

        // Clip messages inside the viewport when the panel is shown so the
        // input box and scrollbar gutter stay clean.
        int save = -1;
        if (open) {
            save = canvas.save();
            canvas.clipRect(Rect.makeXYWH(L.viewportX - 2f, L.viewportY,
                    L.viewportWidth - L.scrollbarWidth - 4f, L.viewportHeight + 4f),
                    ClipMode.INTERSECT, true);
        }
        try {
            for (int i = visible.size() - 1; i >= 0; i--) {
                ChatMessage msg = visible.get(i);
                float alpha = msg.getAlpha(open);
                if (alpha <= 0f) continue;

                if (open) {
                    // Per-line backdrop + thin divider between distinct messages.
                    boolean isNew = previousId != -1L && previousId != msg.getMessageId();
                    if (isNew) {
                        int dividerColor = withAlpha(MStyle.PANEL_HIGHLIGHT, alpha * 0.45f);
                        MPainter.fillRect(canvas, L.chatX - 4f, currentY - L.lineHeight + 2f,
                                L.maxChatWidth + 8f, 1f, dividerColor);
                    }
                    int rowFill = withAlpha(MStyle.PANEL_FILL_DEEP, alpha * 0.35f);
                    MPainter.fillRoundedRect(canvas,
                            L.chatX - 4f, currentY - L.lineHeight + 2f,
                            L.maxChatWidth + 8f, L.lineHeight, 2f, rowFill);
                }

                int color = argb(msg.getColor(), alpha);
                float baseline = currentY - L.lineHeight / 2f + MStyle.FONT_META * 0.35f;

                if (!open) {
                    // Subtle shadow when floating without the panel — keeps text
                    // readable over varied world backgrounds.
                    int shadow = withAlpha(MStyle.TEXT_SHADOW, alpha * 0.6f);
                    drawMixedLine(canvas, msg.getText(), L.chatX + 1f, baseline + 1f, font, shadow, false);
                }
                drawMixedLine(canvas, msg.getText(), L.chatX, baseline, font, color, true);

                previousId = msg.getMessageId();
                currentY -= L.lineHeight;
            }
        } finally {
            if (save >= 0) canvas.restoreToCount(save);
        }
    }

    /**
     * Render one line of text that may contain emoji tokens such as {@code [banana]}.
     * Text segments are drawn at {@code (x, y)} with the given font and colour;
     * emoji tokens are replaced with the item sprite at inline size.
     *
     * @param spritesEnabled when {@code false} the emoji slot width is still
     *                       reserved so shadow and main passes align, but no
     *                       sprite image is drawn (shadow pass only needs text).
     */
    private void drawMixedLine(Canvas canvas, String text, float x, float y,
                               Font font, int color, boolean spritesEnabled) {
        float spriteSize = L_LINE_HEIGHT - 4f; // fits neatly in the 20px line
        float xCursor = x;
        int pos = 0;

        while (pos < text.length()) {
            // Find the nearest emoji token starting at pos.
            int nextStart = -1;
            EmojiType nextEmoji = null;
            for (EmojiType e : EmojiType.values()) {
                int idx = text.indexOf(e.token, pos);
                if (idx >= 0 && (nextStart < 0 || idx < nextStart)) {
                    nextStart = idx;
                    nextEmoji = e;
                }
            }

            if (nextStart < 0) {
                // No more tokens — draw remaining text and stop.
                MPainter.drawString(canvas, text.substring(pos), xCursor, y, font, color);
                break;
            }

            // Draw text segment before the token.
            if (nextStart > pos) {
                String segment = text.substring(pos, nextStart);
                MPainter.drawString(canvas, segment, xCursor, y, font, color);
                xCursor += MPainter.measureWidth(font, segment);
            }

            // Draw (or skip) the emoji sprite.
            if (spritesEnabled) {
                Image img = EmojiImageCache.get(nextEmoji);
                if (img != null) {
                    float spriteY = y - spriteSize + 2f; // align with text baseline
                    MPainter.drawImage(canvas, img, xCursor, spriteY, spriteSize, spriteSize);
                }
            }
            xCursor += spriteSize + 1f; // advance cursor by sprite width + 1px gap

            pos = nextStart + nextEmoji.token.length();
        }
    }

    // Line height constant referenced in drawMixedLine without a Layout instance.
    private static final float L_LINE_HEIGHT = 20f;

    // ─────────────────────────────────────────────────────────── Input field

    private void drawInputField(Canvas canvas, Layout L, ChatSystem chat) {
        float x = L.inputX - 5f;
        float y = L.inputY;
        float w = L.inputWidth + 10f;
        float h = L.inputHeight;
        float r = 3f;

        try (Paint fill = new Paint().setColor(MStyle.SLIDER_TRACK).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), fill);
        }
        // Inner-shadow strip — same recessed feel as MSearchField.
        MPainter.fillRect(canvas, x + 1f, y + 1f, w - 2f, 2f, withAlpha(MStyle.PANEL_SHADOW, 0.6f));

        try (Paint border = new Paint().setColor(MStyle.SLIDER_FILL)
                .setMode(PaintMode.STROKE).setStrokeWidth(1.5f).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1f, h - 1f, r), border);
        }

        Font font = fonts.get(MStyle.FONT_META);
        if (font == null) return;

        String displayText = chat.getDisplayInput();
        String currentInput = chat.getCurrentInput();
        String ghost = chat.getGhostText();

        // Clip text painting to the input rect — long inputs scroll horizontally.
        int save = canvas.save();
        canvas.clipRect(Rect.makeXYWH(x, y, w, h), ClipMode.INTERSECT, true);
        try {
            float baseline = y + h / 2f + MStyle.FONT_META * 0.35f;
            float availableWidth = L.inputWidth;

            // No placeholder — show only the live input + blinking cursor so
            // the empty state isn't visually thrashing between two strings.
            float textWidth = MPainter.measureWidth(font, displayText);
            float scrollOffset = Math.max(0f, textWidth - availableWidth);
            MPainter.drawString(canvas, displayText, L.inputX - scrollOffset, baseline,
                    font, MStyle.TEXT_PRIMARY);

            if (!ghost.isEmpty()) {
                float caretX = MPainter.measureWidth(font, currentInput);
                if (caretX - scrollOffset >= 0f && caretX - scrollOffset < availableWidth) {
                    MPainter.drawString(canvas, ghost,
                            L.inputX + caretX - scrollOffset, baseline,
                            font, withAlpha(MStyle.TEXT_DISABLED, 0.6f));
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    // ─────────────────────────────────────────────────────────── Emoji button

    private void drawEmojiButton(Canvas canvas, Layout L, boolean pickerOpen) {
        float bx = L.emojiButtonX;
        float by = L.emojiButtonY;
        float bs = L.emojiButtonSize;

        int fill = pickerOpen ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, bx, by, bs, bs, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

        // Draw a minimal smiley: two dot eyes + arc mouth using canvas primitives.
        float cx = bx + bs / 2f;
        float cy = by + bs / 2f;
        float fr = bs / 2f - 4f; // face radius

        int faceColor = pickerOpen ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;

        // Face outline
        try (Paint p = new Paint().setColor(faceColor).setMode(PaintMode.STROKE)
                .setStrokeWidth(1.5f).setAntiAlias(true)) {
            canvas.drawCircle(cx, cy, fr, p);
        }

        // Eyes — two small filled circles
        try (Paint p = new Paint().setColor(faceColor).setAntiAlias(true)) {
            canvas.drawCircle(cx - fr * 0.28f, cy - fr * 0.22f, 1.3f, p);
            canvas.drawCircle(cx + fr * 0.28f, cy - fr * 0.22f, 1.3f, p);
        }

        // Smile — bottom arc of a small oval placed below face centre.
        // sweepAngle 180° starting from 0° (3-o'clock) traces the bottom semicircle.
        float smileW    = fr * 0.75f;
        float smileH    = fr * 0.4f;
        float smileL    = cx - smileW / 2f;
        float smileT    = cy + fr * 0.1f;
        float smileR    = smileL + smileW;
        float smileB    = smileT + smileH;
        try (Paint p = new Paint().setColor(faceColor).setMode(PaintMode.STROKE)
                .setStrokeWidth(1.5f).setAntiAlias(true)) {
            canvas.drawArc(smileL, smileT, smileR, smileB, 0f, 180f, false, p);
        }
    }

    private void drawEmojiPicker(Canvas canvas, Layout L, ChatEmojiSystem emojiSystem) {
        float anchorRight = L.emojiButtonX + L.emojiButtonSize;
        float anchorTop   = L.emojiButtonY;
        emojiPickerRenderer.draw(canvas, emojiSystem, anchorRight, anchorTop,
                lastMouseX, lastMouseY);
    }

    // ─────────────────────────────────────────────────────────── Commands tab

    private void drawCommandButtons(Canvas canvas, Layout L, ChatSystem chat) {
        ChatCommandExecutor executor = chat.getCommandExecutor();
        if (executor == null) return;

        List<Map.Entry<String, ChatCommand>> sorted = new ArrayList<>(executor.getCommands().entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        if (sorted.isEmpty()) return;

        float buttonWidth = L.viewportWidth - L.scrollbarWidth - 12f;
        float buttonHeight = 25f;
        float buttonPadding = 5f;

        int save = canvas.save();
        canvas.clipRect(Rect.makeXYWH(L.viewportX, L.viewportY, L.viewportWidth, L.viewportHeight),
                ClipMode.INTERSECT, true);
        try {
            int scrollOffset = chat.getCommandScrollOffset();
            float scrollPx = scrollOffset * (buttonHeight + buttonPadding);
            float currentY = L.viewportY + 4f - scrollPx;

            Font nameFont = fonts.get(MStyle.FONT_META);
            Font descFont = fonts.get(12f);

            for (Map.Entry<String, ChatCommand> entry : sorted) {
                if (currentY + buttonHeight < L.viewportY || currentY > L.viewportY + L.viewportHeight) {
                    currentY += buttonHeight + buttonPadding;
                    continue;
                }
                float bx = L.viewportX + 5f;
                boolean hovered = lastMouseX >= bx && lastMouseX <= bx + buttonWidth
                        && lastMouseY >= currentY && lastMouseY <= currentY + buttonHeight;

                int fill = hovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
                MPainter.stoneSurface(canvas, bx, currentY, buttonWidth, buttonHeight, MStyle.BUTTON_RADIUS,
                        fill, MStyle.BUTTON_BORDER,
                        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

                int textColor = hovered ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
                float baseline = currentY + buttonHeight / 2f + MStyle.FONT_META * 0.35f;
                MPainter.drawStringWithShadow(canvas, "/" + entry.getKey(),
                        bx + 10f, baseline, nameFont, textColor, MStyle.TEXT_SHADOW);

                String desc = entry.getValue().getDescription();
                if (desc != null && !desc.isEmpty() && descFont != null) {
                    float descWidth = MPainter.measureWidth(descFont, desc);
                    MPainter.drawString(canvas, desc,
                            bx + buttonWidth - descWidth - 10f, baseline,
                            descFont, MStyle.TEXT_SECONDARY);
                }

                currentY += buttonHeight + buttonPadding;
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    // ─────────────────────────────────────────────────────────── Scrollbars

    private void drawChatScrollbar(Canvas canvas, Layout L, ChatSystem chat) {
        int max = chat.getMaxScroll();
        if (max <= 0) return;
        int current = chat.getScrollOffset();
        float visibleRatio = MAX_VISIBLE_LINES / (float) (MAX_VISIBLE_LINES + max);
        drawScrollbar(canvas, L, current, max, visibleRatio);
    }

    private void drawCommandScrollbar(Canvas canvas, Layout L, ChatSystem chat) {
        int max = chat.getMaxCommandScroll();
        if (max <= 0) return;
        int current = chat.getCommandScrollOffset();
        int total = chat.getCommandExecutor().getCommands().size();
        float visibleRatio = total > 0 ? Math.min(1f, 6f / total) : 1f;
        drawScrollbar(canvas, L, current, max, visibleRatio);
    }

    private void drawScrollbar(Canvas canvas, Layout L, int current, int max, float visibleRatio) {
        MPainter.fillRoundedRect(canvas, L.scrollbarX, L.scrollbarY,
                L.scrollbarWidth, L.scrollbarHeight, 2f, MStyle.SCROLLBAR_TRACK);

        float thumbHeight = Math.max(20f, L.scrollbarHeight * visibleRatio);
        float thumbY = L.scrollbarY + (L.scrollbarHeight - thumbHeight) * ((float) current / max);
        MPainter.fillRoundedRect(canvas, L.scrollbarX, thumbY,
                L.scrollbarWidth, thumbHeight, 2f, MStyle.SCROLLBAR_THUMB);
        try (Paint p = new Paint().setColor(MStyle.SCROLLBAR_THUMB_EDGE)
                .setMode(PaintMode.STROKE).setStrokeWidth(1f).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(L.scrollbarX + 0.5f, thumbY + 0.5f,
                    L.scrollbarWidth - 1f, thumbHeight - 1f, 2f), p);
        }
    }

    // ─────────────────────────────────────────────────────────── Hit-tests

    public void updateMousePosition(float mouseX, float mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    /** Returns true if the emoji button was clicked. */
    public boolean isEmojiButtonClicked(float mx, float my, int sw, int sh) {
        Layout L = Layout.compute(sw, sh);
        return mx >= L.emojiButtonX && mx <= L.emojiButtonX + L.emojiButtonSize
                && my >= L.emojiButtonY && my <= L.emojiButtonY + L.emojiButtonSize;
    }

    /**
     * Returns the emoji clicked inside the picker, or {@code null}.
     * Call {@link #getPickerFavoriteStarClick} first to resolve star vs. emoji ambiguity.
     */
    public EmojiType getPickerEmojiClick(ChatSystem chat, float mx, float my, int sw, int sh) {
        Layout L = Layout.compute(sw, sh);
        float anchorRight = L.emojiButtonX + L.emojiButtonSize;
        return emojiPickerRenderer.getClickedEmoji(chat.getEmojiSystem(), mx, my, anchorRight, L.emojiButtonY);
    }

    /** Returns the emoji whose favourite-star was clicked, or {@code null}. */
    public EmojiType getPickerFavoriteStarClick(ChatSystem chat, float mx, float my, int sw, int sh) {
        Layout L = Layout.compute(sw, sh);
        float anchorRight = L.emojiButtonX + L.emojiButtonSize;
        return emojiPickerRenderer.getClickedFavoriteStar(chat.getEmojiSystem(), mx, my, anchorRight, L.emojiButtonY);
    }

    /** Returns true if {@code (mx,my)} is anywhere inside the picker panel. */
    public boolean isPickerClick(ChatSystem chat, float mx, float my, int sw, int sh) {
        Layout L = Layout.compute(sw, sh);
        float anchorRight = L.emojiButtonX + L.emojiButtonSize;
        return emojiPickerRenderer.containsPoint(mx, my, anchorRight, L.emojiButtonY, chat.getEmojiSystem());
    }

    public String getClickedCommand(ChatSystem chat, float mx, float my, int sw, int sh) {
        if (!chat.isOpen() || chat.getCurrentTab() != ChatSystem.ChatTab.COMMANDS) return null;
        Layout L = Layout.compute(sw, sh);
        if (mx < L.viewportX || mx > L.viewportX + L.viewportWidth - L.scrollbarWidth - 4f) return null;
        if (my < L.viewportY || my > L.viewportY + L.viewportHeight) return null;

        float buttonHeight = 25f;
        float buttonPadding = 5f;
        int scrollOffset = chat.getCommandScrollOffset();
        float relativeY = my - (L.viewportY + 4f);
        int index = scrollOffset + (int) (relativeY / (buttonHeight + buttonPadding));

        List<String> names = new ArrayList<>(chat.getCommandExecutor().getCommands().keySet());
        names.sort(String::compareTo);
        if (index >= 0 && index < names.size()) return names.get(index);
        return null;
    }

    public boolean handleChatScrollbarPress(ChatSystem chat, float mx, float my, int sw, int sh) {
        if (!chat.isOpen() || chat.getCurrentTab() != ChatSystem.ChatTab.CHAT) return false;
        int max = chat.getMaxScroll();
        if (max <= 0) return false;

        Layout L = Layout.compute(sw, sh);
        if (!inScrollbarTrack(L, mx, my)) return false;

        float visibleRatio = MAX_VISIBLE_LINES / (float) (MAX_VISIBLE_LINES + max);
        float thumbHeight = Math.max(20f, L.scrollbarHeight * visibleRatio);
        float thumbY = L.scrollbarY + (L.scrollbarHeight - thumbHeight) * ((float) chat.getScrollOffset() / max);

        if (my >= thumbY && my <= thumbY + thumbHeight) {
            isDraggingChatScrollbar = true;
            scrollbarDragStartY = my - thumbY;
            return true;
        }
        // Track-click: jump in the click direction, exactly like the old renderer.
        chat.handleScroll(my < thumbY ? 1 : -1);
        return true;
    }

    public boolean handleCommandScrollbarPress(ChatSystem chat, float mx, float my, int sw, int sh) {
        if (!chat.isOpen() || chat.getCurrentTab() != ChatSystem.ChatTab.COMMANDS) return false;
        int max = chat.getMaxCommandScroll();
        if (max <= 0) return false;

        Layout L = Layout.compute(sw, sh);
        if (!inScrollbarTrack(L, mx, my)) return false;

        int total = chat.getCommandExecutor().getCommands().size();
        float visibleRatio = total > 0 ? Math.min(1f, 6f / total) : 1f;
        float thumbHeight = Math.max(20f, L.scrollbarHeight * visibleRatio);
        float thumbY = L.scrollbarY + (L.scrollbarHeight - thumbHeight) * ((float) chat.getCommandScrollOffset() / max);

        if (my >= thumbY && my <= thumbY + thumbHeight) {
            isDraggingCommandScrollbar = true;
            scrollbarDragStartY = my - thumbY;
            return true;
        }
        chat.handleScroll(my < thumbY ? 1 : -1);
        return true;
    }

    public void handleScrollbarDrag(ChatSystem chat, float mouseY, int windowHeight) {
        if (!isDraggingChatScrollbar && !isDraggingCommandScrollbar || chat == null) return;

        // Scrollbar Y bounds depend only on window height — compute directly
        // so the drag path doesn't need to know window width.
        float lineHeight = 20f;
        float padding = 10f;
        float inputBoxHeight = 25f;
        float inputBoxMargin = 10f;
        float panelHeight = (MAX_VISIBLE_LINES * lineHeight) + inputBoxHeight + inputBoxMargin + padding * 2f;
        float panelY = windowHeight - panelHeight;
        float trackY = panelY + padding;
        float trackHeight = panelHeight - inputBoxHeight - inputBoxMargin - padding * 2f;

        if (isDraggingChatScrollbar) {
            int max = chat.getMaxScroll();
            if (max <= 0) return;
            float visibleRatio = MAX_VISIBLE_LINES / (float) (MAX_VISIBLE_LINES + max);
            float thumbHeight = Math.max(20f, trackHeight * visibleRatio);
            float ratio = (mouseY - scrollbarDragStartY - trackY) / (trackHeight - thumbHeight);
            ratio = Math.max(0f, Math.min(1f, ratio));
            applyScrollDelta(chat, Math.round(ratio * max) - chat.getScrollOffset());
        } else {
            int max = chat.getMaxCommandScroll();
            if (max <= 0) return;
            int total = chat.getCommandExecutor().getCommands().size();
            float visibleRatio = total > 0 ? Math.min(1f, 6f / total) : 1f;
            float thumbHeight = Math.max(20f, trackHeight * visibleRatio);
            float ratio = (mouseY - scrollbarDragStartY - trackY) / (trackHeight - thumbHeight);
            ratio = Math.max(0f, Math.min(1f, ratio));
            applyScrollDelta(chat, Math.round(ratio * max) - chat.getCommandScrollOffset());
        }
    }

    public void handleScrollbarRelease() {
        isDraggingChatScrollbar = false;
        isDraggingCommandScrollbar = false;
    }

    public boolean isDraggingScrollbar() {
        return isDraggingChatScrollbar || isDraggingCommandScrollbar;
    }

    public void dispose() {
        fonts.dispose();
    }

    // ─────────────────────────────────────────────────────────── Helpers

    private static boolean inScrollbarTrack(Layout L, float mx, float my) {
        return mx >= L.scrollbarX && mx <= L.scrollbarX + L.scrollbarWidth
                && my >= L.scrollbarY && my <= L.scrollbarY + L.scrollbarHeight;
    }

    private static void applyScrollDelta(ChatSystem chat, int delta) {
        int sign = delta > 0 ? 1 : -1;
        for (int i = 0; i < Math.abs(delta); i++) chat.handleScroll(sign);
    }

    private static int argb(float[] rgba, float alphaScale) {
        int a = clamp255(Math.round(rgba[3] * alphaScale * 255f));
        int r = clamp255(Math.round(rgba[0] * 255f));
        int g = clamp255(Math.round(rgba[1] * 255f));
        int b = clamp255(Math.round(rgba[2] * 255f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int argb, float alphaScale) {
        int a = clamp255(Math.round(((argb >>> 24) & 0xFF) * alphaScale));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
