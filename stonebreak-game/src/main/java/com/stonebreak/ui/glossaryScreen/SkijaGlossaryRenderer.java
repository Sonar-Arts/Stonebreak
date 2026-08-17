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
import com.stonebreak.rendering.UI.masonryUI.MBadge;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MProgressBar;
import com.stonebreak.rendering.UI.masonryUI.MSectionHeader;
import com.stonebreak.rendering.UI.masonryUI.MStatRow;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MSymbol;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
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
 * <p>Master–detail layout (see {@link GlossaryLayout}): an entity list
 * sidebar on the left — one row per glossary mob, with discovery status —
 * and a single large detail pane on the right for the selected entity: a big
 * live 3D preview with a variant cycler, then an attributes column (score
 * bars) beside a weakness/abilities column. Both panes are hard-clipped to
 * their rects, so nothing can bleed outside them regardless of content.
 *
 * <p>The 3D preview is drawn with raw GL <em>after</em> the Skija frame
 * closes: the Skija surface wraps the default framebuffer, so a scissored
 * viewport draw lands on top of the composited panel in the same presented
 * frame.
 */
public final class SkijaGlossaryRenderer {

    // Base font sizes (scaled via MFonts.getScaled).
    private static final float FS_TITLE     = 32f;
    private static final float FS_NAME      = 24f;
    private static final float FS_ROW       = 16f;
    private static final float FS_HEADER    = 14f;
    private static final float FS_STAT      = 13f;
    private static final float FS_BUTTON    = 20f;

    private static final int COLOR_OVERLAY      = 0x78000000;
    private static final int COLOR_INSET_FILL   = 0xFF1E1E1E;
    private static final int COLOR_INSET_BORDER = 0xFF0A0A0A;
    private static final int COLOR_ROW_HOVER    = 0x1EFFFFFF;
    private static final int COLOR_CHIP_FILL    = 0xB4000000;
    private static final int COLOR_BADGE_DIM    = 0xFF474747;

    private static final String[] ATTR_NAMES = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

    private final SkijaUIBackend backend;
    private final MasonryUI mui;

    // Reusable MasonryUI components, re-configured fluently each frame.
    private final MSectionHeader sectionHeader = new MSectionHeader("");
    private final MBadge badge = new MBadge("");
    private final MProgressBar discoveryBar = new MProgressBar();
    private final MStatRow statRow = new MStatRow();

    /** Cached model AABBs keyed by "objectId/variant" → {minX,minY,minZ,maxX,maxY,maxZ}. */
    private final Map<String, float[]> boundsCache = new HashMap<>();

    /** A model preview to draw with GL once the Skija frame has closed. */
    private record PreviewSlot(EntityType type, String variant, float x, float y, float w, float h) {}

    public SkijaGlossaryRenderer(SkijaUIBackend backend) {
        this.backend = backend;
        this.mui = new MasonryUI(backend);
        sectionHeader.scaleText(true);
        badge.scaleText(true);
        discoveryBar.scaleText(true);
        statRow.scaleText(true);
    }

    public void render(int windowWidth, int windowHeight, GlossaryScreen screen) {
        if (backend == null || !backend.isAvailable()) return;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();

        PreviewSlot preview = null;

        backend.beginFrame(windowWidth, windowHeight, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();

            // Dark overlay behind the panel
            try (Paint p = new Paint().setColor(COLOR_OVERLAY)) {
                canvas.drawRect(Rect.makeXYWH(0, 0, windowWidth, windowHeight), p);
            }

            float[] panel = GlossaryLayout.panelRect(windowWidth, windowHeight, scale);
            MPainter.panel(canvas, panel[0], panel[1], panel[2], panel[3]);

            Player player = Game.getPlayer();
            EntityDiscoveries discoveries = (player != null) ? player.getEntityDiscoveries() : null;
            PlayerStats stats = (player != null) ? player.getStats() : null;

            drawHeader(canvas, panel, discoveries, scale);
            drawSidebar(canvas, windowWidth, windowHeight, scale, discoveries, screen);
            preview = drawDetail(canvas, windowWidth, windowHeight, scale, discoveries, stats, screen);
            drawBackButton(canvas, windowWidth, windowHeight, scale, screen);
        } finally {
            backend.endFrame();
        }

        // 3D model preview: drawn with GL on top of the just-composited panel.
        if (preview != null) drawEntityPreview(preview, windowWidth, windowHeight);
    }

    // ─────────────────────────────────────────────── Header strip

    private void drawHeader(Canvas canvas, float[] panel, EntityDiscoveries discoveries, float scale) {
        float cx = panel[0] + panel[2] / 2f;
        drawTitle(canvas, cx, panel[1] + 40f * scale, mui.fonts().getScaled(FS_TITLE));

        // Discovery progress: how many glossary entities have been observed.
        int total = GlossaryLayout.rowCount();
        int seen = 0;
        for (EntityType type : EntityType.GLOSSARY_TYPES) {
            if (!discoveredVariants(type, discoveries).isEmpty()) seen++;
        }
        Font fStat = mui.fonts().getScaled(FS_STAT);
        String label = seen + " / " + total + " observed";
        float barW = 200f * scale;
        float barH = 8f * scale;
        float labelW = MPainter.measureWidth(fStat, label);
        float groupW = barW + 10f * scale + labelW;
        float barX = cx - groupW / 2f;
        float barY = panel[1] + 56f * scale;
        discoveryBar.fraction(total > 0 ? (float) seen / total : 0f)
                .fillColor(MStyle.TEXT_ACCENT).trackColor(MStyle.SLIDER_TRACK)
                .bounds(barX, barY, barW, barH);
        discoveryBar.render(mui);
        MPainter.drawStringWithShadow(canvas, label, barX + barW + 10f * scale,
                barY + barH / 2f + FS_STAT * scale * 0.38f, fStat,
                MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    }

    private void drawTitle(Canvas canvas, float cx, float cy, Font font) {
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
            MPainter.drawCenteredString(canvas, "ENTITY GLOSSARY", cx + offset, cy + offset, font, color);
        }
    }

    // ─────────────────────────────────────────────── Sidebar (entity list)

    private void drawSidebar(Canvas canvas, int ww, int wh, float scale,
                             EntityDiscoveries discoveries, GlossaryScreen screen) {
        float[] sb = GlossaryLayout.sidebarRect(ww, wh, scale);
        drawInset(canvas, sb[0], sb[1], sb[2], sb[3]);

        Font fRow = mui.fonts().getScaled(FS_ROW);
        Font fStat = mui.fonts().getScaled(FS_STAT);

        canvas.save();
        canvas.clipRect(Rect.makeXYWH(sb[0], sb[1], sb[2], sb[3]));
        try {
            for (int i = 0; i < GlossaryLayout.rowCount(); i++) {
                EntityType type = EntityType.GLOSSARY_TYPES[i];
                float[] r = GlossaryLayout.listRowRect(i, ww, wh, scale);
                boolean sel = i == screen.getSelectedEntityIndex();
                boolean hov = i == screen.getHoveredRowIndex();

                if (sel) {
                    MPainter.fillRoundedRect(canvas, r[0], r[1], r[2], r[3], 3f,
                            MStyle.DROPDOWN_ITEM_CURRENT);
                    MPainter.fillRoundedRect(canvas, r[0], r[1], 3f * scale, r[3], 1.5f,
                            MStyle.TEXT_ACCENT);
                } else if (hov) {
                    MPainter.fillRoundedRect(canvas, r[0], r[1], r[2], r[3], 3f, COLOR_ROW_HOVER);
                }

                List<String> seen = discoveredVariants(type, discoveries);
                String[] all = type.getTextureVariants();
                int totalVariants = all != null ? all.length : 0;
                boolean observed = !seen.isEmpty();

                float tx = r[0] + 12f * scale;
                int nameColor = sel ? MStyle.TEXT_ACCENT
                        : observed ? MStyle.TEXT_PRIMARY : MStyle.TEXT_SECONDARY;
                MPainter.drawStringWithShadow(canvas, type.getDisplayName(), tx,
                        r[1] + r[3] * 0.42f + FS_ROW * scale * 0.35f, fRow,
                        nameColor, MStyle.TEXT_SHADOW);

                String sub = observed
                        ? seen.size() + "/" + totalVariants + " variants"
                        : "Not yet observed";
                MPainter.drawStringWithShadow(canvas, sub, tx,
                        r[1] + r[3] * 0.82f, fStat,
                        observed ? MStyle.TEXT_SECONDARY : MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);

                // Status icon on the right edge: lock (unseen) / check (complete).
                float icon = 14f * scale;
                float ix = r[0] + r[2] - icon - 10f * scale;
                float iy = r[1] + (r[3] - icon) / 2f;
                if (!observed) {
                    MSymbol.LOCK.drawWithShadow(canvas, ix, iy, icon, icon,
                            MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
                } else if (seen.size() >= totalVariants && totalVariants > 0) {
                    MSymbol.CHECK.drawWithShadow(canvas, ix, iy, icon, icon,
                            MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
                }
            }
        } finally {
            canvas.restore();
        }
    }

    // ─────────────────────────────────────────────── Detail pane

    private PreviewSlot drawDetail(Canvas canvas, int ww, int wh, float scale,
                                   EntityDiscoveries discoveries, PlayerStats stats,
                                   GlossaryScreen screen) {
        float[] d = GlossaryLayout.detailRect(ww, wh, scale);
        if (d[2] <= 0f || d[3] <= 0f) return null;

        EntityType type = EntityType.GLOSSARY_TYPES[screen.getSelectedEntityIndex()];
        long kills = stats != null ? stats.getKillsByType().getOrDefault(type, 0L) : 0L;

        PreviewSlot slot;
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(d[0], d[1], d[2], d[3]));
        try {
            // Header row: entity name + kill badge
            Font fName = mui.fonts().getScaled(FS_NAME);
            MPainter.drawStringWithShadow(canvas, type.getDisplayName(), d[0],
                    d[1] + 22f * scale, fName, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

            float badgeH = 20f * scale;
            badge.text(kills > 0 ? formatLong(kills) + " defeated" : "Undefeated")
                    .fillColor(kills > 0 ? MStyle.TEXT_ACCENT : COLOR_BADGE_DIM)
                    .textColor(kills > 0 ? 0xFF2B2317 : MStyle.TEXT_SECONDARY)
                    .size(0f, badgeH);
            float badgeW = badge.preferredWidth(mui);
            badge.bounds(d[0] + d[2] - badgeW, d[1] + 6f * scale, badgeW, badgeH);
            badge.render(mui);

            // Preview inset + variant cycler
            float[] pv = GlossaryLayout.previewRect(ww, wh, scale);
            drawInset(canvas, pv[0], pv[1], pv[2], pv[3]);
            slot = drawPreviewChrome(canvas, pv, ww, wh, type, discoveries, screen, scale);

            // Two content columns below the preview
            float colTop = pv[1] + pv[3] + 14f * scale;
            float colGap = 20f * scale;
            float leftW = (d[2] - colGap) * 0.46f;
            float rightX = d[0] + leftW + colGap;
            float rightW = d[2] - leftW - colGap;

            drawAttributesColumn(canvas, d[0], colTop, leftW, type.getAttributes(), kills > 0, scale);
            drawLoreColumn(canvas, rightX, colTop, rightW, type, discoveries, scale);
        } finally {
            canvas.restore();
        }
        return slot;
    }

    /** Cycler arrows + variant chip inside the preview rect; returns the GL slot (or null). */
    private PreviewSlot drawPreviewChrome(Canvas canvas, float[] pv, int ww, int wh,
                                          EntityType type, EntityDiscoveries discoveries,
                                          GlossaryScreen screen, float scale) {
        List<String> discovered = discoveredVariants(type, discoveries);
        int count = discovered.size();
        float pcx = pv[0] + pv[2] / 2f;

        if (count == 0) {
            float icon = 36f * scale;
            MSymbol.LOCK.drawWithShadow(canvas, pcx - icon / 2f, pv[1] + pv[3] / 2f - icon * 0.8f,
                    icon, icon, MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
            MPainter.drawCenteredStringWithShadow(canvas, "Observe one in the world to unlock",
                    pcx, pv[1] + pv[3] / 2f + 16f * scale, mui.fonts().getScaled(FS_STAT),
                    MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
            return null;
        }

        int idx = screen.getSelectedVariantIndex(type, count);
        String variant = discovered.get(idx);

        // Variant chip pinned to the preview's bottom edge
        Font fStat = mui.fonts().getScaled(FS_STAT);
        String chip = count > 1 ? variant + "  " + (idx + 1) + "/" + count : variant;
        float chipW = MPainter.measureWidth(fStat, chip) + 20f * scale;
        float chipH = 18f * scale;
        float chipY = pv[1] + pv[3] - chipH - 6f * scale;
        MPainter.fillRoundedRect(canvas, pcx - chipW / 2f, chipY, chipW, chipH, chipH / 2f, COLOR_CHIP_FILL);
        MPainter.drawCenteredString(canvas, chip, pcx, chipY + chipH / 2f + FS_STAT * scale * 0.38f,
                fStat, MStyle.TEXT_PRIMARY);

        float sideInset = 8f * scale;
        if (count > 1) {
            float[] la = GlossaryLayout.leftArrowRect(ww, wh, scale);
            drawArrowButton(canvas, la, true, screen.isLeftArrowHovered());
            drawArrowButton(canvas, GlossaryLayout.rightArrowRect(ww, wh, scale), false,
                    screen.isRightArrowHovered());
            sideInset = (la[0] + la[2] - pv[0]) + 6f * scale;
        }

        // The GL viewport stays clear of the arrows and the chip: the model is
        // drawn after the Skija frame and would otherwise paint over them.
        float bottomInset = chipH + 12f * scale;
        return new PreviewSlot(type, variant,
                pv[0] + sideInset, pv[1] + 6f * scale,
                pv[2] - 2f * sideInset, pv[3] - bottomInset - 6f * scale);
    }

    private void drawAttributesColumn(Canvas canvas, float x, float y, float w,
                                      EntityAttributes attrs, boolean unlocked, float scale) {
        sectionHeader.label("ATTRIBUTES").bounds(x, y, w, 16f * scale);
        sectionHeader.render(mui);
        y += 24f * scale;

        float rowH = 18f * scale;
        float rowStep = rowH + 2f * scale;

        if (!unlocked || attrs == null) {
            for (String name : ATTR_NAMES) {
                statRow.label(name).value("???").bar(0, 0f).bounds(x, y, w, rowH);
                statRow.render(mui);
                y += rowStep;
            }
            y += 8f * scale;
            float icon = 12f * scale;
            String hint = "Defeat one to reveal";
            Font fStat = mui.fonts().getScaled(FS_STAT);
            float hintW = MPainter.measureWidth(fStat, hint);
            float hx = x + (w - icon - 6f * scale - hintW) / 2f;
            MSymbol.LOCK.drawWithShadow(canvas, hx, y - icon + 3f * scale, icon, icon,
                    MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
            MPainter.drawStringWithShadow(canvas, hint, hx + icon + 6f * scale, y, fStat,
                    MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
            return;
        }

        int[] scores = {attrs.str(), attrs.dex(), attrs.con(), attrs.intel(), attrs.wis(), attrs.cha()};
        for (int i = 0; i < 6; i++) {
            statRow.label(ATTR_NAMES[i])
                    .value(scores[i] + " (" + modifierStr(scores[i]) + ")")
                    .bar(MStyle.TEXT_ACCENT, scores[i] / 20f)
                    .bounds(x, y, w, rowH);
            statRow.render(mui);
            y += rowStep;
        }

        y += 8f * scale;
        String[] derivedNames = {"HP", "SPD", "ATK"};
        String[] derivedVals = {
                String.format("%.0f", attrs.deriveMaxHealth()),
                String.format("%.1f", attrs.deriveMoveSpeed()),
                String.valueOf(attrs.deriveMeleeDamage())
        };
        for (int i = 0; i < 3; i++) {
            statRow.label(derivedNames[i]).value(derivedVals[i]).bar(0, 0f).bounds(x, y, w, rowH);
            statRow.render(mui);
            y += rowStep;
        }
    }

    private void drawLoreColumn(Canvas canvas, float x, float y, float w,
                                EntityType type, EntityDiscoveries discoveries, float scale) {
        Font fStat = mui.fonts().getScaled(FS_STAT);
        Font fHeader = mui.fonts().getScaled(FS_HEADER);
        float lineH = 14f * scale;

        // Weakness
        sectionHeader.label("WEAKNESS").bounds(x, y, w, 16f * scale);
        sectionHeader.render(mui);
        y += 24f * scale;

        boolean discovered = discoveries != null && discoveries.isWeaknessDiscovered(type);
        if (discovered) {
            LivingEntity.DamageSource weakness = type.getWeakness();
            float icon = 13f * scale;
            MSymbol.WARNING.drawWithShadow(canvas, x, y - icon + 2f * scale, icon, icon,
                    MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
            MPainter.drawStringWithShadow(canvas, weakness != null ? weakness.name() : "None",
                    x + icon + 6f * scale, y, fHeader, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
            y += 18f * scale;
            for (String line : wrapText(fStat, type.getWeaknessDescription(), w)) {
                MPainter.drawStringWithShadow(canvas, line, x, y, fStat,
                        MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
                y += lineH;
            }
        } else {
            float icon = 12f * scale;
            MSymbol.LOCK.drawWithShadow(canvas, x, y - icon + 2f * scale, icon, icon,
                    MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
            MPainter.drawStringWithShadow(canvas, "Unknown", x + icon + 6f * scale, y, fHeader,
                    MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
            y += 18f * scale;
            for (String line : wrapText(fStat, "Study as Quarry (Ranger) to reveal", w)) {
                MPainter.drawStringWithShadow(canvas, line, x, y, fStat,
                        MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
                y += lineH;
            }
        }
        y += 12f * scale;

        // Abilities
        sectionHeader.label("ABILITIES").bounds(x, y, w, 16f * scale);
        sectionHeader.render(mui);
        y += 24f * scale;

        String[] abilities = type.getSpecialAbilities();
        if (abilities != null && abilities.length > 0) {
            float bullet = 9f * scale;
            float indent = bullet + 7f * scale;
            for (String ability : abilities) {
                boolean first = true;
                for (String line : wrapText(fStat, ability, w - indent)) {
                    if (first) {
                        MSymbol.STAR.draw(canvas, x, y - bullet + 1f * scale, bullet, bullet,
                                MStyle.TEXT_ACCENT);
                        first = false;
                    }
                    MPainter.drawStringWithShadow(canvas, line, x + indent, y, fStat,
                            MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
                    y += lineH;
                }
                y += 3f * scale;
            }
        } else {
            MPainter.drawStringWithShadow(canvas, "None known", x, y, fStat,
                    MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── Back button

    private void drawBackButton(Canvas canvas, int ww, int wh, float scale, GlossaryScreen screen) {
        float[] b = GlossaryLayout.backButtonRect(ww, wh, scale);
        boolean hovered = screen.isBackButtonHovered();
        int fill = hovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, b[0], b[1], b[2], b[3], MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        int color = hovered ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        MPainter.drawCenteredStringWithShadow(canvas, "Back", b[0] + b[2] / 2f,
                b[1] + b[3] / 2f + 7f * scale, mui.fonts().getScaled(FS_BUTTON),
                color, MStyle.TEXT_SHADOW);
    }

    // ─────────────────────────────────────────────── 3D preview pass

    private void drawEntityPreview(PreviewSlot s, int windowWidth, int windowHeight) {
        Renderer renderer = Game.getRenderer();
        if (renderer == null) return;
        EntityRenderer entityRenderer = renderer.getEntityRenderer();
        if (entityRenderer == null) return;

        float time = Game.getInstance().getTotalTimeElapsed();
        float az = time * 0.6f;                       // orbit speed (rad/s)
        float el = (float) Math.toRadians(18.0);      // camera elevation
        float fov = (float) Math.toRadians(35.0);
        float halfFovTan = (float) Math.tan(fov / 2f);

        int vx = Math.round(s.x());
        int vy = Math.round(windowHeight - (s.y() + s.h()));   // GL origin is bottom-left
        int vw = Math.round(s.w());
        int vh = Math.round(s.h());
        if (vw <= 0 || vh <= 0) return;

        float[] b = boundsFor(s.type(), s.variant());
        if (b == null) return;

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);

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

    private void drawArrowButton(Canvas canvas, float[] r, boolean pointLeft, boolean hovered) {
        int fill = hovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, r[0], r[1], r[2], r[3], MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        float icon = r[2] * 0.6f;
        int color = hovered ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        (pointLeft ? MSymbol.CHEVRON_LEFT : MSymbol.CHEVRON_RIGHT).drawWithShadow(canvas,
                r[0] + (r[2] - icon) / 2f, r[1] + (r[3] - icon) / 2f, icon, icon,
                color, MStyle.TEXT_SHADOW);
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

    // ─────────────────────────────────────────────── Shared queries

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

    private static String formatLong(long v) {
        return String.format("%,d", v);
    }

    private static String modifierStr(int score) {
        int mod = EntityAttributes.getModifier(score);
        return (mod >= 0 ? "+" : "") + mod;
    }

    public void dispose() {
        mui.dispose();
    }
}
