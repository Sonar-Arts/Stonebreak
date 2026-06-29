package com.stonebreak.ui.glossaryScreen;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityAttributes;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skija/MasonryUI renderer for the Entity Glossary screen.
 *
 * <p>Presents one "entity card" per glossary mob type in a fixed 3-column
 * layout. Each card flows top-to-bottom through a running cursor (so sections
 * never overlap), shows a live 3D model preview at the top with a discovered-
 * only variant cycler, and wraps long descriptions inside the card.
 *
 * <p>The 3D preview is drawn with raw GL <em>after</em> the Skija frame closes:
 * the Skija surface wraps the default framebuffer, so a scissored viewport draw
 * lands on top of the cards in the same presented frame.
 */
public final class SkijaGlossaryRenderer {

    public static final float BUTTON_WIDTH  = 360f;
    public static final float BUTTON_HEIGHT = 50f;
    public static final float PANEL_HEIGHT  = 680f;
    public static final float BACK_BUTTON_BOTTOM_MARGIN = 30f;

    private static final float BASE_PANEL_WIDTH  = 1000f;
    private static final float BASE_PANEL_HEIGHT = PANEL_HEIGHT;

    private static final float CARD_WIDTH  = 300f;
    private static final float CARD_HEIGHT = 490f;
    private static final float CARD_GAP    = 16f;

    // Card-relative layout constants (unscaled). Shared with GlossaryScreen for
    // hit-testing via the public static rect helpers below.
    private static final float CARDS_TOP   = 84f;  // first card top, relative to panel top
    private static final float CONTENT_PAD = 14f;  // inner horizontal padding
    private static final float CYCLER_TOP  = 40f;  // cycler row top, relative to card top
    private static final float ARROW_SIZE  = 28f;  // variant arrow button size
    private static final float PREVIEW_H    = 96f; // 3D preview viewport height
    private static final float ATTR_ROW_H   = 15f; // per-attribute row height

    private static final float BASE_TITLE_SIZE    = 36f;
    private static final float BASE_CARD_TITLE    = 22f;
    private static final float BASE_HEADER_SIZE   = 14f;
    private static final float BASE_STAT_SIZE     = 13f;
    private static final float BASE_BUTTON_SIZE   = 20f;

    private static final int COLOR_OVERLAY = 0x78000000;
    private static final int COLOR_INSET_FILL   = 0xFF1E1E1E;
    private static final int COLOR_INSET_BORDER = 0xFF0A0A0A;
    private static final int COLOR_SEPARATOR    = 0x33FFFFFF;

    private final SkijaUIBackend backend;

    private Font fontTitle;
    private Font fontCardTitle;
    private Font fontHeader;
    private Font fontStat;
    private Font fontButton;
    private float lastFontScale = -1f;

    /** Cached model AABBs keyed by "objectId/variant" → {minX,minY,minZ,maxX,maxY,maxZ}. */
    private final Map<String, float[]> boundsCache = new HashMap<>();

    /** A model preview to draw with GL once the Skija frame has closed. */
    private record PreviewSlot(EntityType type, String variant, float x, float y, float w, float h) {}

    public SkijaGlossaryRenderer(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public void render(int windowWidth, int windowHeight, boolean backHovered, GlossaryScreen screen) {
        if (backend == null || !backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        ensureFonts(scale);

        float panelW = BASE_PANEL_WIDTH  * scale;
        float panelH = BASE_PANEL_HEIGHT * scale;
        float bw     = BUTTON_WIDTH  * scale;
        float bh     = BUTTON_HEIGHT * scale;

        List<PreviewSlot> previews = new ArrayList<>();

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();

            // Dark overlay
            try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
                canvas.drawRect(Rect.makeXYWH(0, 0, windowWidth, windowHeight), p);
            }

            float cx = windowWidth  / 2f;
            float cy = windowHeight / 2f;
            float panelX = cx - panelW / 2f;
            float panelY = cy - panelH / 2f;

            // Main panel background
            MPainter.panel(canvas, panelX, panelY, panelW, panelH);

            // Title
            drawTitle(canvas, cx, panelY + 56f * scale);

            Player player = Game.getPlayer();
            EntityDiscoveries discoveries = (player != null) ? player.getEntityDiscoveries() : null;
            PlayerStats stats = (player != null) ? player.getStats() : null;

            for (int i = 0; i < EntityType.GLOSSARY_TYPES.length; i++) {
                EntityType type = EntityType.GLOSSARY_TYPES[i];
                float[] c = cardBounds(i, windowWidth, windowHeight, scale);
                PreviewSlot slot = drawCard(canvas, c[0], c[1], c[2], c[3], type, discoveries, stats, screen);
                if (slot != null) previews.add(slot);
            }

            // Back button
            float panelBottom = panelY + panelH;
            float backBtnY = panelBottom - BACK_BUTTON_BOTTOM_MARGIN * scale - bh;
            float backBtnX = cx - bw / 2f;
            int fill = backHovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
            MPainter.stoneSurface(canvas, backBtnX, backBtnY, bw, bh, MStyle.BUTTON_RADIUS,
                    fill, MStyle.BUTTON_BORDER,
                    MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                    MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
            int btnTextColor = backHovered ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            MPainter.drawCenteredStringWithShadow(canvas, "Back", backBtnX + bw / 2f,
                    backBtnY + bh / 2f + 7f * scale, fontButton, btnTextColor, MStyle.TEXT_SHADOW);
        } finally {
            backend.endFrame();
        }

        // 3D model previews: drawn with GL on top of the just-composited cards.
        drawEntityPreviews(previews, windowWidth, windowHeight);
    }

    // ─────────────────────────────────────────────── Card

    private PreviewSlot drawCard(Canvas canvas, float cardX, float cardY, float w, float h,
                                 EntityType type, EntityDiscoveries discoveries,
                                 PlayerStats stats, GlossaryScreen screen) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        float pad = CONTENT_PAD * scale;
        float contentX = cardX + pad;
        float contentW = w - 2f * pad;
        float cxCard = cardX + w / 2f;

        // Card background
        MPainter.stoneSurface(canvas, cardX, cardY, w, h, MStyle.BUTTON_RADIUS,
                MStyle.BUTTON_FILL, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

        // Clip all card internals to the card rect: a hard backstop so nothing
        // can ever bleed into a neighbouring card.
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(cardX, cardY, w, h));
        try {
            // Title
            MPainter.drawCenteredStringWithShadow(canvas, type.getDisplayName(), cxCard,
                    cardY + 24f * scale, fontCardTitle, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

            // Variant cycler (discovered-only)
            List<String> discovered = discoveredVariants(type, discoveries);
            int count = discovered.size();
            int idx = (screen != null) ? screen.getSelectedVariantIndex(type, count) : 0;
            String variantName = count > 0 ? discovered.get(idx) : null;

            float cyclerTop = cardY + CYCLER_TOP * scale;
            float arrow = ARROW_SIZE * scale;
            if (count > 1) {
                drawArrowButton(canvas, contentX, cyclerTop, arrow, "<", scale);
                drawArrowButton(canvas, cardX + w - pad - arrow, cyclerTop, arrow, ">", scale);
            }
            MPainter.drawCenteredStringWithShadow(canvas, count > 0 ? variantName : "—",
                    cxCard, cyclerTop + 13f * scale, fontHeader,
                    count > 0 ? MStyle.TEXT_PRIMARY : MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
            if (count > 1) {
                MPainter.drawCenteredStringWithShadow(canvas, (idx + 1) + " / " + count,
                        cxCard, cyclerTop + 26f * scale, fontStat, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
            }

            // Preview frame (the 3D model is rendered into this rect after the frame)
            float previewTop = cyclerTop + arrow + 8f * scale;
            float previewH = PREVIEW_H * scale;
            drawInset(canvas, contentX, previewTop, contentW, previewH);
            PreviewSlot slot;
            if (count > 0) {
                slot = new PreviewSlot(type, variantName, contentX, previewTop, contentW, previewH);
            } else {
                slot = null;
                MPainter.drawCenteredStringWithShadow(canvas, "Observe in the world",
                        cxCard, previewTop + previewH / 2f + 4f * scale, fontStat,
                        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
            }

            float cy = previewTop + previewH + 12f * scale;

            // Kill count
            separator(canvas, cardX, cy, w, scale);
            cy += 14f * scale;
            long kills = stats != null ? stats.getKillsByType().getOrDefault(type, 0L) : 0L;
            String killsLabel = kills > 0 ? formatLong(kills) + " defeated" : "Not yet defeated";
            MPainter.drawCenteredStringWithShadow(canvas, killsLabel, cxCard, cy, fontStat,
                    kills > 0 ? MStyle.TEXT_ACCENT : MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
            cy += 8f * scale;

            // Attributes
            separator(canvas, cardX, cy, w, scale);
            cy += 14f * scale;
            cy += drawAttributes(canvas, contentX, cy, contentW, type.getAttributes(), kills > 0, scale);
            cy += 6f * scale;

            // Weakness
            separator(canvas, cardX, cy, w, scale);
            cy += 14f * scale;
            cy += drawWeakness(canvas, contentX, cy, contentW, type, discoveries, scale);
            cy += 6f * scale;

            // Abilities
            separator(canvas, cardX, cy, w, scale);
            cy += 14f * scale;
            drawAbilities(canvas, contentX, cy, contentW, type, scale);

            return slot;
        } finally {
            canvas.restore();
        }
    }

    private float drawAttributes(Canvas canvas, float x, float y, float w,
                                 EntityAttributes attrs, boolean unlocked, float scale) {
        float rowH = ATTR_ROW_H * scale;
        String[] names = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

        if (!unlocked || attrs == null) {
            for (int i = 0; i < 6; i++) {
                float by = y + i * rowH + 12f * scale;
                MPainter.drawStringWithShadow(canvas, names[i], x, by, fontStat,
                        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
                drawRightAligned(canvas, "???", x, by, w, fontStat, MStyle.TEXT_SHADOW);
            }
            float msgY = y + 6 * rowH + 16f * scale;
            MPainter.drawCenteredStringWithShadow(canvas, "Defeat one to reveal", x + w / 2f, msgY,
                    fontStat, MStyle.TEXT_SHADOW, MStyle.TEXT_SHADOW);
            return 6 * rowH + 24f * scale;
        }

        int[] scores = {attrs.str(), attrs.dex(), attrs.con(), attrs.intel(), attrs.wis(), attrs.cha()};
        for (int i = 0; i < 6; i++) {
            float by = y + i * rowH + 12f * scale;
            MPainter.drawStringWithShadow(canvas, names[i], x, by, fontStat,
                    MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
            String val = scores[i] + " (" + modifierStr(scores[i]) + ")";
            drawRightAligned(canvas, val, x, by, w, fontStat, MStyle.TEXT_PRIMARY);
        }

        // Derived stats
        String[] derivedNames = {"HP", "SPD", "ATK"};
        String[] derivedVals = {
                String.format("%.0f", attrs.deriveMaxHealth()),
                String.format("%.1f", attrs.deriveMoveSpeed()),
                String.valueOf(attrs.deriveMeleeDamage())
        };
        for (int i = 0; i < 3; i++) {
            float by = y + (6 + i) * rowH + 12f * scale;
            MPainter.drawStringWithShadow(canvas, derivedNames[i], x, by, fontStat,
                    MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
            drawRightAligned(canvas, derivedVals[i], x, by, w, fontStat, MStyle.TEXT_PRIMARY);
        }
        return 9 * rowH + 4f * scale;
    }

    private float drawWeakness(Canvas canvas, float x, float y, float w,
                               EntityType type, EntityDiscoveries discoveries, float scale) {
        float by = y + 12f * scale;
        MPainter.drawStringWithShadow(canvas, "Weakness", x, by, fontHeader,
                MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        by += 16f * scale;

        boolean discovered = discoveries != null && discoveries.isWeaknessDiscovered(type);
        if (discovered) {
            LivingEntity.DamageSource weakness = type.getWeakness();
            MPainter.drawStringWithShadow(canvas, weakness != null ? weakness.name() : "None",
                    x, by, fontStat, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            by += 15f * scale;
            for (String line : wrapText(fontStat, type.getWeaknessDescription(), w)) {
                MPainter.drawStringWithShadow(canvas, line, x, by, fontStat,
                        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
                by += 13f * scale;
            }
        } else {
            for (String line : wrapText(fontStat, "??? — Study as Quarry (Ranger) to reveal", w)) {
                MPainter.drawStringWithShadow(canvas, line, x, by, fontStat,
                        MStyle.TEXT_SHADOW, MStyle.TEXT_SHADOW);
                by += 13f * scale;
            }
        }
        return by - y;
    }

    private void drawAbilities(Canvas canvas, float x, float y, float w, EntityType type, float scale) {
        float by = y + 12f * scale;
        MPainter.drawStringWithShadow(canvas, "Abilities", x, by, fontHeader,
                MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        by += 16f * scale;
        String[] abilities = type.getSpecialAbilities();
        if (abilities != null && abilities.length > 0) {
            for (String a : abilities) {
                for (String line : wrapText(fontStat, a, w)) {
                    MPainter.drawStringWithShadow(canvas, line, x, by, fontStat,
                            MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
                    by += 13f * scale;
                }
            }
        } else {
            MPainter.drawStringWithShadow(canvas, "None known", x, by, fontStat,
                    MStyle.TEXT_SHADOW, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── 3D preview pass

    private void drawEntityPreviews(List<PreviewSlot> slots, int windowWidth, int windowHeight) {
        if (slots.isEmpty()) return;
        Renderer renderer = Game.getRenderer();
        if (renderer == null) return;
        EntityRenderer entityRenderer = renderer.getEntityRenderer();
        if (entityRenderer == null) return;

        float time = Game.getInstance().getTotalTimeElapsed();
        float az = time * 0.6f;                       // orbit speed (rad/s)
        float el = (float) Math.toRadians(18.0);      // camera elevation
        float fov = (float) Math.toRadians(35.0);
        float halfFovTan = (float) Math.tan(fov / 2f);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);

        for (PreviewSlot s : slots) {
            int vx = Math.round(s.x());
            int vy = Math.round(windowHeight - (s.y() + s.h()));   // GL origin is bottom-left
            int vw = Math.round(s.w());
            int vh = Math.round(s.h());
            if (vw <= 0 || vh <= 0) continue;

            float[] b = boundsFor(s.type(), s.variant());
            if (b == null) continue;

            GL11.glViewport(vx, vy, vw, vh);
            GL11.glScissor(vx, vy, vw, vh);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            float ctrX = (b[0] + b[3]) / 2f, ctrY = (b[1] + b[4]) / 2f, ctrZ = (b[2] + b[5]) / 2f;
            float ex = b[3] - b[0], ey = b[4] - b[1], ez = b[5] - b[2];
            float radius = 0.5f * (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (radius <= 0f) radius = 0.5f;

            float dist = radius / halfFovTan * 1.25f;
            float horiz = dist * (float) Math.cos(el);
            float eyeX = ctrX + horiz * (float) Math.sin(az);
            float eyeZ = ctrZ + horiz * (float) Math.cos(az);
            float eyeY = ctrY + dist * (float) Math.sin(el);

            Matrix4f view = new Matrix4f().setLookAt(eyeX, eyeY, eyeZ, ctrX, ctrY, ctrZ, 0f, 1f, 0f);
            Matrix4f proj = new Matrix4f().setPerspective(fov, (float) vw / vh, 0.05f, dist + radius * 4f);

            entityRenderer.renderEntityPreview(s.type(), s.variant(), "Idle", time,
                    new Vector3f(0f, 0f, 0f), 0f, new Vector3f(1f, 1f, 1f), view, proj);
        }

        // Restore a clean GL baseline matching SkiaContext.restoreGLDefaults().
        GL11.glScissor(0, 0, windowWidth, windowHeight);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glViewport(0, 0, windowWidth, windowHeight);
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Returns (and caches) the model AABB for an asset variant, or null if unavailable. */
    private float[] boundsFor(EntityType type, String variant) {
        String key = type.getSbeObjectId() + "/" + variant;
        float[] cached = boundsCache.get(key);
        if (cached != null) return cached;

        SbeEntityAsset asset = SbeEntityRegistry.get(type.getSbeObjectId());
        if (asset == null) return null;
        SbeModelGeometry geo = asset.geometryFor(variant);
        if (geo == null) return null;
        float[] v = geo.vertices();
        if (v == null || v.length < 3) return null;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i + 2 < v.length; i += 3) {
            minX = Math.min(minX, v[i]);     maxX = Math.max(maxX, v[i]);
            minY = Math.min(minY, v[i + 1]); maxY = Math.max(maxY, v[i + 1]);
            minZ = Math.min(minZ, v[i + 2]); maxZ = Math.max(maxZ, v[i + 2]);
        }
        float[] bounds = {minX, minY, minZ, maxX, maxY, maxZ};
        boundsCache.put(key, bounds);
        return bounds;
    }

    // ─────────────────────────────────────────────── Small drawing helpers

    private void drawArrowButton(Canvas canvas, float x, float y, float size, String glyph, float scale) {
        MPainter.stoneSurface(canvas, x, y, size, size, MStyle.BUTTON_RADIUS,
                MStyle.BUTTON_FILL, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        MPainter.drawCenteredStringWithShadow(canvas, glyph, x + size / 2f, y + size / 2f + 5f * scale,
                fontHeader, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
    }

    private static void drawInset(Canvas canvas, float x, float y, float w, float h) {
        try (Paint p = new Paint().setColor(COLOR_INSET_FILL).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, 3f), p);
        }
        try (Paint p = new Paint().setColor(COLOR_INSET_BORDER).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(1.5f)) {
            canvas.drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1f, h - 1f, 3f), p);
        }
    }

    private static void separator(Canvas canvas, float cardX, float y, float w, float scale) {
        try (Paint p = new Paint().setColor(COLOR_SEPARATOR)) {
            canvas.drawRect(Rect.makeXYWH(cardX + 12f * scale, y, w - 24f * scale, 1f), p);
        }
    }

    private static void drawRightAligned(Canvas canvas, String text, float x, float baseline,
                                         float w, Font font, int color) {
        float valX = x + w - MPainter.measureWidth(font, text);
        MPainter.drawStringWithShadow(canvas, text, valX, baseline, font, color, MStyle.TEXT_SHADOW);
    }

    /** Greedy word-wrap that keeps every line within {@code maxWidth}. */
    private static List<String> wrapText(Font font, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String trial = cur.length() == 0 ? word : cur + " " + word;
            if (cur.length() == 0 || MPainter.measureWidth(font, trial) <= maxWidth) {
                cur.setLength(0);
                cur.append(trial);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    // ─────────────────────────────────────────────── Shared layout (hit-testing)

    /** {@code {x,y,w,h}} of the glossary card at {@code index}, in screen pixels. */
    public static float[] cardBounds(int index, int windowWidth, int windowHeight, float scale) {
        float panelH = BASE_PANEL_HEIGHT * scale;
        float cx = windowWidth / 2f;
        float panelY = windowHeight / 2f - panelH / 2f;
        float cardW = CARD_WIDTH * scale;
        float cardH = CARD_HEIGHT * scale;
        float gap = CARD_GAP * scale;
        float total = 3f * cardW + 2f * gap;
        float startX = cx - total / 2f;
        float cardsY = panelY + CARDS_TOP * scale;
        return new float[]{startX + index * (cardW + gap), cardsY, cardW, cardH};
    }

    /** {@code {x,y,w,h}} of the left variant arrow on card {@code index}. */
    public static float[] leftArrowRect(int index, int windowWidth, int windowHeight, float scale) {
        float[] c = cardBounds(index, windowWidth, windowHeight, scale);
        float pad = CONTENT_PAD * scale;
        float arrow = ARROW_SIZE * scale;
        return new float[]{c[0] + pad, c[1] + CYCLER_TOP * scale, arrow, arrow};
    }

    /** {@code {x,y,w,h}} of the right variant arrow on card {@code index}. */
    public static float[] rightArrowRect(int index, int windowWidth, int windowHeight, float scale) {
        float[] c = cardBounds(index, windowWidth, windowHeight, scale);
        float pad = CONTENT_PAD * scale;
        float arrow = ARROW_SIZE * scale;
        return new float[]{c[0] + c[2] - pad - arrow, c[1] + CYCLER_TOP * scale, arrow, arrow};
    }

    /** Variants of {@code type} the player has discovered, in declaration order. */
    public static List<String> discoveredVariants(EntityType type, EntityDiscoveries discoveries) {
        List<String> out = new ArrayList<>();
        String[] all = type.getTextureVariants();
        if (all == null) return out;
        for (String v : all) {
            if (discoveries != null && discoveries.hasSeenVariant(type, v)) out.add(v);
        }
        return out;
    }

    // ─────────────────────────────────────────────── Fonts / misc

    private void ensureFonts(float scale) {
        if (fontTitle != null && scale == lastFontScale) return;
        disposeFonts();
        lastFontScale = scale;
        Typeface tf = backend.getMinecraftTypeface();
        fontTitle     = new Font(tf, BASE_TITLE_SIZE  * scale);
        fontCardTitle = new Font(tf, BASE_CARD_TITLE  * scale);
        fontHeader    = new Font(tf, BASE_HEADER_SIZE * scale);
        fontStat      = new Font(tf, BASE_STAT_SIZE   * scale);
        fontButton    = new Font(tf, BASE_BUTTON_SIZE * scale);
    }

    private void disposeFonts() {
        if (fontTitle     != null) { fontTitle.close();     fontTitle     = null; }
        if (fontCardTitle != null) { fontCardTitle.close(); fontCardTitle = null; }
        if (fontHeader    != null) { fontHeader.close();    fontHeader    = null; }
        if (fontStat      != null) { fontStat.close();      fontStat      = null; }
        if (fontButton    != null) { fontButton.close();    fontButton    = null; }
    }

    private void drawTitle(Canvas canvas, float cx, float cy) {
        for (int i = 4; i >= 0; i--) {
            int color;
            switch (i) {
                case 0 -> color = 0xFFFFDC64;
                case 1 -> color = 0xFFDCB450;
                default -> {
                    int v = Math.max(30, 100 - i * 20);
                    color = (0xC8 << 24) | (v << 16) | (v << 8) | v;
                }
            }
            float offset = i * 2.0f;
            MPainter.drawCenteredString(canvas, "ENTITY GLOSSARY", cx + offset, cy + offset, fontTitle, color);
        }
    }

    private static String formatLong(long v) {
        return String.format("%,d", v);
    }

    private static String modifierStr(int score) {
        int mod = EntityAttributes.getModifier(score);
        return (mod >= 0 ? "+" : "") + mod;
    }

    public void dispose() {
        disposeFonts();
    }
}
