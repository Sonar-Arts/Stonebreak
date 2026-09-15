package com.openmason.main.systems.assistant.ui;

import com.openmason.main.systems.assistant.ChatMessage;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.core.MortarRegionPool;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.skija.SkijaFontStore;
import imgui.ImGui;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.paragraph.FontCollection;
import io.github.humbleui.skija.paragraph.Paragraph;
import io.github.humbleui.skija.paragraph.ParagraphBuilder;
import io.github.humbleui.skija.paragraph.ParagraphStyle;
import io.github.humbleui.skija.paragraph.TextStyle;
import io.github.humbleui.skija.paragraph.TypefaceFontProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skija-rendered chat prose: markdown blocks laid out with Skija's Paragraph
 * engine (real glyphs — arrows, dashes, checks — proper wrapping, styled
 * bold/inline-code) painted into pooled {@link MortarRegion}s, one region per
 * prose run so per-frame texture uploads stay bounded to visible messages.
 *
 * <p>Fenced code blocks are deliberately NOT painted here — the caller keeps
 * them as ImGui children (free selection + Copy). Layouts are cached per
 * message and rebuilt only when the text grows (streaming) or the wrap width
 * changes; cached {@link Paragraph}s are native objects and are closed on
 * eviction.
 */
final class ChatProseSkija implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ChatProseSkija.class);
    private static final float BASE_SIZE = 14f;
    private static final float PARAGRAPH_GAP = 5f;
    private static final float HEADING_GAP = 9f;
    private static final float PADDING_Y = 2f;

    /** Result of a frame's prose submission for one message. */
    sealed interface Piece {
        record Prose(int poolIndex, float height) implements Piece {
        }

        record Code(String language, String body) implements Piece {
        }
    }

    private record ParaEntry(Paragraph paragraph, float indent, float topGap, float height) {
    }

    private static final class Layout implements AutoCloseable {
        final float width;
        final int sourceLength;
        final List<List<ParaEntry>> proseRuns = new ArrayList<>();
        final List<Float> runHeights = new ArrayList<>();
        final List<Object> order = new ArrayList<>(); // Integer run-index | Block.CodeBlock

        Layout(float width, int sourceLength) {
            this.width = width;
            this.sourceLength = sourceLength;
        }

        @Override
        public void close() {
            for (List<ParaEntry> run : proseRuns) {
                for (ParaEntry entry : run) {
                    entry.paragraph().close();
                }
            }
            proseRuns.clear();
        }
    }

    private static volatile FontCollection fontCollection;
    private static volatile boolean paragraphEngineBroken;

    private final MortarRegionPool pool = new MortarRegionPool();
    private final Map<ChatMessage, Layout> layouts = new HashMap<>();
    private final Set<ChatMessage> touched = new HashSet<>();
    private int poolCursor;

    boolean isAvailable() {
        return pool.isAvailable() && !paragraphEngineBroken && collection() != null;
    }

    /** Start-of-frame: reset pool cursor + touch tracking. */
    void beginFrame() {
        poolCursor = 0;
        touched.clear();
    }

    /** End-of-frame: trim pool, evict layouts for messages no longer shown. */
    void endFrame() {
        pool.trim(poolCursor);
        layouts.entrySet().removeIf(e -> {
            if (!touched.contains(e.getKey())) {
                e.getValue().close();
                return true;
            }
            return false;
        });
    }

    /**
     * Render one message's markdown at the current cursor. Returns the code
     * blocks interleaved so the caller can render them as ImGui children in
     * order (this method renders prose runs itself and leaves the cursor after
     * each; callers invoke {@link #renderRun} through the returned sequence).
     */
    List<Piece> pieces(ChatMessage message, float width) {
        touched.add(message);
        Layout layout = layoutFor(message, width);
        List<Piece> pieces = new ArrayList<>();
        int runIdx = 0;
        for (Object item : layout.order) {
            if (item instanceof ChatMarkdown.Block.CodeBlock code) {
                pieces.add(new Piece.Code(code.language(), code.body()));
            } else {
                pieces.add(new Piece.Prose(runIdx, layout.runHeights.get(runIdx)));
                runIdx++;
            }
        }
        return pieces;
    }

    /** Render one prose run (from {@link #pieces}) at the current ImGui cursor. */
    void renderRun(ChatMessage message, int runIndex, float width, String popupId) {
        Layout layout = layouts.get(message);
        if (layout == null || runIndex >= layout.proseRuns.size()) {
            return;
        }
        float height = layout.runHeights.get(runIndex);
        if (height <= 0) {
            return;
        }
        if (!ImGui.isRectVisible(1, height)) {
            ImGui.dummy(width, height); // culled: keep layout, skip raster+upload
            return;
        }
        MortarRegion region = pool.get(poolCursor++);
        List<ParaEntry> run = layout.proseRuns.get(runIndex);
        region.begin(width, height);
        region.add("prose", 0, 0, width, height, (g, x, y, w, h, state) -> {
            Canvas canvas = g.canvas();
            float cursorY = y + PADDING_Y;
            for (ParaEntry entry : run) {
                cursorY += entry.topGap();
                entry.paragraph().paint(canvas, x + entry.indent(), cursorY);
                cursorY += entry.height();
            }
        });
        var result = region.render();
        region.update(ImGui.getIO().getDeltaTime());
        if (result.rightClicked() != null) {
            ImGui.openPopup(popupId);
        }
        if (ImGui.beginPopup(popupId)) {
            if (ImGui.menuItem("Copy message")) {
                ImGui.setClipboardText(message.text.toString());
            }
            ImGui.endPopup();
        }
    }

    @Override
    public void close() {
        layouts.values().forEach(Layout::close);
        layouts.clear();
        pool.close();
    }

    // -------------------------------------------------------------- layout

    private Layout layoutFor(ChatMessage message, float width) {
        Layout cached = layouts.get(message);
        int length = message.text.length();
        if (cached != null && cached.sourceLength == length
                && Math.abs(cached.width - width) < 1f) {
            return cached;
        }
        if (cached != null) {
            cached.close();
        }
        Layout layout = build(message.text.toString(), width, length);
        layouts.put(message, layout);
        return layout;
    }

    private Layout build(String text, float width, int sourceLength) {
        Layout layout = new Layout(width, sourceLength);
        MortarTheme theme = MortarTheme.capture();
        List<ParaEntry> currentRun = new ArrayList<>();
        try {
            for (ChatMarkdown.Block block : ChatMarkdown.parse(text)) {
                if (block instanceof ChatMarkdown.Block.CodeBlock code) {
                    closeRun(layout, currentRun);
                    layout.order.add(code);
                    currentRun = new ArrayList<>();
                    continue;
                }
                ParaEntry entry = switch (block) {
                    case ChatMarkdown.Block.Heading h -> paragraphEntry(h.spans(), theme,
                            BASE_SIZE + (4 - h.level()), true, 0,
                            currentRun.isEmpty() ? 0 : HEADING_GAP, width);
                    case ChatMarkdown.Block.Bullet b -> {
                        List<ChatMarkdown.Span> spans = new ArrayList<>();
                        spans.add(new ChatMarkdown.Span(b.marker() + " ", false, false));
                        spans.addAll(b.spans());
                        yield paragraphEntry(spans, theme, BASE_SIZE, false,
                                10 + b.indent() * 14,
                                currentRun.isEmpty() ? 0 : 2f, width);
                    }
                    case ChatMarkdown.Block.Paragraph p -> paragraphEntry(p.spans(), theme,
                            BASE_SIZE, false, 0,
                            currentRun.isEmpty() ? 0 : PARAGRAPH_GAP, width);
                    default -> null;
                };
                if (entry != null) {
                    currentRun.add(entry);
                }
            }
            closeRun(layout, currentRun);
        } catch (Throwable t) {
            // Paragraph engine failure (missing native symbol etc.) — flag the
            // whole Skija prose path off; caller falls back to ImGui.
            logger.error("Skija paragraph layout failed — falling back to ImGui prose", t);
            paragraphEngineBroken = true;
            layout.close();
        }
        return layout;
    }

    private static void closeRun(Layout layout, List<ParaEntry> run) {
        if (run.isEmpty()) {
            return;
        }
        float height = PADDING_Y * 2;
        for (ParaEntry entry : run) {
            height += entry.topGap() + entry.height();
        }
        layout.proseRuns.add(run);
        layout.runHeights.add(height);
        layout.order.add(layout.proseRuns.size() - 1);
    }

    private ParaEntry paragraphEntry(List<ChatMarkdown.Span> spans, MortarTheme theme,
                                     float fontSize, boolean boldAll, float indent,
                                     float topGap, float width) {
        FontCollection fonts = collection();
        try (ParagraphStyle style = new ParagraphStyle();
             TextStyle base = textStyle(theme.text, fontSize, boldAll, false, theme)) {
            style.setTextStyle(base);
            ParagraphBuilder builder = new ParagraphBuilder(style, fonts);
            try {
                for (ChatMarkdown.Span span : spans) {
                    try (TextStyle spanStyle = textStyle(
                            span.code() ? blend(theme.accent, theme.text) : theme.text,
                            fontSize, boldAll || span.bold(), span.code(), theme)) {
                        builder.pushStyle(spanStyle);
                        builder.addText(span.text());
                        builder.popStyle();
                    }
                }
                Paragraph paragraph = builder.build();
                paragraph.layout(Math.max(40, width - indent - 4));
                return new ParaEntry(paragraph, indent, topGap, paragraph.getHeight());
            } finally {
                builder.close();
            }
        }
    }

    private TextStyle textStyle(int color, float size, boolean bold, boolean code,
                                MortarTheme theme) {
        TextStyle style = new TextStyle()
                .setFontFamilies(new String[]{"JetBrains Mono"})
                .setFontSize(size)
                .setColor(color)
                .setFontStyle(bold ? FontStyle.BOLD : FontStyle.NORMAL);
        if (code) {
            Paint bg = new Paint();
            bg.setColor(theme.surface);
            style.setBackground(bg);
        }
        return style;
    }

    /** Mix accent toward text so inline code reads tinted, not neon. */
    private static int blend(int accent, int text) {
        int a = 0xFF;
        int r = (((accent >> 16) & 0xFF) * 2 + ((text >> 16) & 0xFF)) / 3;
        int g = (((accent >> 8) & 0xFF) * 2 + ((text >> 8) & 0xFF)) / 3;
        int b = ((accent & 0xFF) * 2 + (text & 0xFF)) / 3;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static FontCollection collection() {
        FontCollection cached = fontCollection;
        if (cached != null) {
            return cached;
        }
        synchronized (ChatProseSkija.class) {
            if (fontCollection == null) {
                try {
                    TypefaceFontProvider provider = new TypefaceFontProvider();
                    provider.registerTypeface(
                            SkijaFontStore.typeface(SkijaFontStore.Weight.REGULAR),
                            "JetBrains Mono");
                    provider.registerTypeface(
                            SkijaFontStore.typeface(SkijaFontStore.Weight.BOLD),
                            "JetBrains Mono");
                    provider.registerTypeface(
                            SkijaFontStore.typeface(SkijaFontStore.Weight.MEDIUM),
                            "JetBrains Mono");
                    FontCollection collection = new FontCollection();
                    collection.setAssetFontManager(provider);
                    collection.setDefaultFontManager(FontMgr.getDefault());
                    collection.setEnableFallback(true);
                    fontCollection = collection;
                } catch (Throwable t) {
                    logger.error("Skija FontCollection init failed — ImGui prose fallback", t);
                    paragraphEngineBroken = true;
                }
            }
            return fontCollection;
        }
    }
}
