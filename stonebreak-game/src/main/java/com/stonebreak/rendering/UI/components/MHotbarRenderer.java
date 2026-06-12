package com.stonebreak.rendering.UI.components;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.core.Game;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.QuarryController;
import com.stonebreak.player.combat.RageController;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.player.combat.arcanist.ArcanistHudText;
import com.stonebreak.player.combat.arcanist.ResonanceTracker;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.player.combat.berserker.BerserkerTierText;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.ranger.RangerHudText;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.rpg.classes.AbilityIconCache;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rendering.UI.masonryUI.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import static com.stonebreak.player.PlayerConstants.RAGE_T1_THRESHOLD;
import static com.stonebreak.player.PlayerConstants.RAGE_T2_THRESHOLD;
import static com.stonebreak.player.PlayerConstants.RAGE_T3_THRESHOLD;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;

/**
 * MasonryUI/Skija-based hotbar renderer.
 *
 * Replaces the NanoVG {@code HotbarRenderer} using the same 3-phase pattern
 * as {@code InventoryRenderCoordinator}:
 *   A) Skija  – background panel, slot backgrounds, health hearts
 *   B) GL     – item icons drawn directly into the framebuffer
 *   C) Skija  – item count text
 *   D) Skija  – tooltip (separate call, layered after block drops)
 */
public class MHotbarRenderer {

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final MasonryUI ui;

    // ── Background panel (matches HotbarTheme.Background values) ─────────────
    private static final int   BG_FILL      = 0xC8282828; // RGBA(40,40,40,200)
    private static final int   BG_BORDER    = 0xFF505050; // RGBA(80,80,80,255)
    private static final int   BG_HIGHLIGHT = 0x22FFFFFF; // subtle top bevel
    private static final int   BG_SHADOW    = 0x44000000; // subtle bottom bevel
    private static final int   BG_DROP      = 0x78000000; // drop shadow alpha=120
    private static final float BG_RADIUS    = 8f;

    // ── Health hearts ─────────────────────────────────────────────────────────
    /** Target heart edge length, in pixels. Snapped at draw time to the
     *  nearest integer multiple of the source texture's native size to avoid
     *  nearest-neighbour sampling asymmetry. */
    private static final int   HEART_SIZE_TARGET         = 28;
    private static final int   HEART_SPACING             = 2;
    private static final int   HEART_Y_GAP               = 38; // pixels above hotbar background
    private static final int   HEART_ROW_GAP             = 4;
    private static final float HEART_MIN_VISIBLE_FRACTION = 0.40f;

    // ── Stamina bar ───────────────────────────────────────────────────────────
    private static final int STAMINA_BAR_HEIGHT = 8;
    private static final int STAMINA_BAR_GAP    = 6;    // pixels above top heart row
    private static final int STAMINA_BG         = 0xC83C3C3C;
    private static final int STAMINA_FILL       = 0xDC50C850;
    private static final int STAMINA_BORDER     = 0xFF000000;

    // ── Berserker Rage indicator (only shown when Berserker is the selected class) ────
    private static final int   RAGE_PANEL_GAP       = 12;   // pixels right of the hotbar background
    private static final int   RAGE_PANEL_WIDTH     = 190;
    private static final int   RAGE_SEGMENT_HEIGHT  = 10;
    private static final int   RAGE_SEGMENT_GAP     = 3;
    private static final int   RAGE_SEGMENT_BG      = 0xC83C3C3C;
    private static final int   RAGE_SEGMENT_BORDER  = 0xFF000000;
    private static final int   RAGE_SEGMENT_FILLED  = 0xDCC83C32; // fierce red
    private static final int   RAGE_SEGMENT_PARTIAL = 0xDC9C5028; // dim ember
    private static final int   RAGE_LABEL_GAP       = 10;
    private static final int   RAGE_BONUS_LINE_GAP  = 8;
    private static final float RAGE_ICON_SIZE       = 20f;
    private static final float RAGE_ICON_GAP        = 4f;

    private static final String RAGE_ICON_PATH          = "/ui/abilities/berserker/Rage.png";
    private static final String RAMPAGE_ICON_PATH       = "/ui/abilities/berserker/Rampage.png";
    private static final String SKULL_CRUSHER_ICON_PATH = "/ui/abilities/berserker/Skull_Crusher.png";

    // ── Ranger Quarry indicator (only shown when Ranger is the selected class) ────────
    private static final int   QUARRY_PIP_HEIGHT  = 10;
    private static final int   QUARRY_PIP_GAP     = 3;
    private static final int   QUARRY_PIP_FILLED  = 0xDC58B858; // hunter green
    private static final int   QUARRY_PIP_DIMMED  = 0xDC4A6E3C; // decaying — dim moss
    private static final int   QUARRY_HP_HEIGHT   = 5;
    private static final int   QUARRY_HP_FILL     = 0xDCC83C32;
    private static final int   QUARRY_LINE_GAP    = 8;

    private static final String QUARRY_ICON_PATH       = "/ui/abilities/ranger/Quarry.png";
    private static final String SNARE_ICON_PATH        = "/ui/abilities/ranger/Snare.png";
    private static final String CULLING_SHOT_ICON_PATH = "/ui/abilities/ranger/Culling_Shot.png";

    // ── Arcanist Resonance indicator (only shown when Arcanist is the selected class) ──
    private static final int RESONANCE_PIP_HEIGHT     = 10;
    private static final int RESONANCE_PIP_GAP        = 3;
    private static final int RESONANCE_PIP_FILLED     = 0xDC9C50E8; // arcane violet
    private static final int RESONANCE_PIP_OVERLOADED = 0xDCFFD24A; // hot gold — Overloaded
    private static final int RESONANCE_LINE_GAP       = 8;

    // ── Mana bar (only shown when the selected class spends mana) ─────────────
    private static final int MANA_BAR_HEIGHT = 8;
    private static final int MANA_BAR_GAP    = 6;    // pixels above the stamina bar
    private static final int MANA_FILL       = 0xDC3C78DC; // arcane blue

    private record HeartLayout(int heartsPerRow, int numRows, float step) {}

    private static final String HEART_EMPTY_SBT = "/ui/HUD/Health Icon/SB_Empty_Health_Icon.sbt";
    private static final String HEART_HALF_SBT  = "/ui/HUD/Health Icon/SB_Half_Health_Icon.sbt";
    private static final String HEART_FULL_SBT  = "/ui/HUD/Health Icon/SB_Full_Health_Icon.sbt";

    public MHotbarRenderer(UIRenderer uiRenderer, Renderer renderer) {
        this.uiRenderer = uiRenderer;
        this.renderer   = renderer;
        this.ui         = new MasonryUI(renderer.getSkijaBackend());
    }

    // ─────────────────────────────────────────────── Public API

    /**
     * Renders the complete hotbar (background, slots, items, hearts, counts).
     * Tooltip is NOT rendered here — call {@link #renderHotbarTooltip} separately.
     */
    public void renderHotbar(HotbarScreen hotbarScreen, int sw, int sh) {
        if (hotbarScreen == null) return;

        HotbarLayoutCalculator.HotbarLayout layout =
                hotbarScreen.calculateLayout(sw, sh);
        ItemStack[] slots    = hotbarScreen.getHotbarSlots();
        int         selected = hotbarScreen.getSelectedSlotIndex();

        // ── Phase A: Skija ────────────────────────────────────────────────
        if (ui.beginFrame(sw, sh, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawBackground(canvas, layout);
            drawSlots(layout, selected);
            drawSbtItemIcons(canvas, slots, layout);
            drawHealthHearts(canvas, layout);
            drawStaminaBar(canvas, layout);
            drawManaBar(canvas, layout);
            drawRageIndicator(canvas, layout);
            drawQuarryIndicator(canvas, layout);
            drawResonanceIndicator(canvas, layout);
            ui.renderOverlays();
            ui.endFrame();
        }

        // ── Phase B: GL item icons ────────────────────────────────────────
        renderItemIcons(slots, layout, hotbarScreen);

        // ── Phase C: Skija count texts ────────────────────────────────────
        if (ui.beginFrame(sw, sh, 1.0f)) {
            drawCountTexts(ui.canvas(), slots, layout, hotbarScreen);
            ui.endFrame();
        }
    }

    /**
     * Renders the tooltip for the selected hotbar slot.
     * Call this after block drops so the tooltip layers on top.
     */
    public void renderHotbarTooltip(HotbarScreen hotbarScreen, int sw, int sh) {
        if (hotbarScreen == null || !hotbarScreen.shouldShowTooltip()) return;
        String text  = hotbarScreen.getTooltipText();
        float  alpha = hotbarScreen.getTooltipAlpha();
        if (text == null || alpha <= 0f) return;

        HotbarLayoutCalculator.HotbarLayout layout =
                hotbarScreen.calculateLayout(sw, sh);
        int selectedIndex = hotbarScreen.getSelectedSlotIndex();

        if (ui.beginFrame(sw, sh, 1.0f)) {
            drawTooltip(ui.canvas(), text, alpha, selectedIndex, layout, sw, sh);
            ui.endFrame();
        }
    }

    // ─────────────────────────────────────────────── Phase A helpers

    private void drawBackground(Canvas canvas,
                                HotbarLayoutCalculator.HotbarLayout layout) {
        MPainter.stoneSurface(canvas,
                layout.backgroundX, layout.backgroundY,
                layout.backgroundWidth, layout.backgroundHeight,
                BG_RADIUS,
                BG_FILL, BG_BORDER,
                BG_HIGHLIGHT, BG_SHADOW, BG_DROP,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    private void drawSlots(HotbarLayoutCalculator.HotbarLayout layout, int selectedIndex) {
        for (int i = 0; i < layout.slotCount; i++) {
            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            new MItemSlot()
                    .hotbarSelected(i == selectedIndex)
                    .bounds(pos.x, pos.y, pos.width, pos.height)
                    .render(ui);
        }
    }

    private void drawHealthHearts(Canvas canvas,
                                  HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;

        float health      = player.getHealth();
        float maxHealth   = player.getMaxHealth();
        int   totalHearts = (int) Math.ceil(maxHealth / 2.0f);
        float filled      = health / 2.0f;

        MTexture empty = MTextureRegistry.get(HEART_EMPTY_SBT);
        MTexture half  = MTextureRegistry.get(HEART_HALF_SBT);
        MTexture full  = MTextureRegistry.get(HEART_FULL_SBT);

        int nativeSize = nativeHeartSize(empty, half, full);
        int scale      = Math.max(1, Math.round((float) HEART_SIZE_TARGET / nativeSize));
        int heartSize  = nativeSize * scale;

        HeartLayout hl = computeHeartLayout(totalHearts, heartSize, layout.backgroundWidth);
        if (hl.heartsPerRow() == 0) return;

        for (int i = 0; i < totalHearts; i++) {
            int   row  = i / hl.heartsPerRow();
            int   col  = i % hl.heartsPerRow();
            float x    = layout.backgroundX + col * hl.step();
            float y    = layout.backgroundY - HEART_Y_GAP - row * (heartSize + HEART_ROW_GAP);
            float fill = Math.max(0f, Math.min(1f, filled - i));

            MTexture sprite = fill >= 0.75f ? full : fill >= 0.25f ? half : empty;
            if (sprite != null) {
                MPainter.drawImage(canvas, sprite.image(), x, y, heartSize, heartSize);
            }
        }
    }

    private void drawStaminaBar(Canvas canvas,
                                HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;
        float maxStamina = player.getMaxStamina();
        if (maxStamina <= 0) return;

        int totalHearts = (int) Math.ceil(player.getMaxHealth() / 2.0f);
        MTexture empty = MTextureRegistry.get(HEART_EMPTY_SBT);
        MTexture half  = MTextureRegistry.get(HEART_HALF_SBT);
        MTexture full  = MTextureRegistry.get(HEART_FULL_SBT);
        int nativeSize = nativeHeartSize(empty, half, full);
        int heartSize  = nativeSize * Math.max(1, Math.round((float) HEART_SIZE_TARGET / nativeSize));

        HeartLayout hl     = computeHeartLayout(totalHearts, heartSize, layout.backgroundWidth);
        int         rows   = Math.max(1, hl.numRows());
        float       topRowY = layout.backgroundY - HEART_Y_GAP - (rows - 1) * (heartSize + HEART_ROW_GAP);
        float       barY    = topRowY - STAMINA_BAR_GAP - STAMINA_BAR_HEIGHT;
        float       fillW   = layout.backgroundWidth
                * Math.max(0f, Math.min(1f, player.getStamina() / maxStamina));

        MPainter.fillRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, STAMINA_BAR_HEIGHT, STAMINA_BG);
        if (fillW > 0) {
            MPainter.fillRect(canvas, layout.backgroundX, barY, fillW, STAMINA_BAR_HEIGHT, STAMINA_FILL);
        }
        MPainter.strokeRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, STAMINA_BAR_HEIGHT, STAMINA_BORDER, 1f);
    }

    /**
     * Draws the mana bar above the stamina bar. Renders only when the selected class
     * spends mana (currently just the Arcanist) to avoid HUD clutter for the others.
     */
    private void drawManaBar(Canvas canvas, HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;
        if (!ArcanistAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;
        float maxMana = player.getMaxMana();
        if (maxMana <= 0) return;

        int totalHearts = (int) Math.ceil(player.getMaxHealth() / 2.0f);
        MTexture empty = MTextureRegistry.get(HEART_EMPTY_SBT);
        MTexture half  = MTextureRegistry.get(HEART_HALF_SBT);
        MTexture full  = MTextureRegistry.get(HEART_FULL_SBT);
        int nativeSize = nativeHeartSize(empty, half, full);
        int heartSize  = nativeSize * Math.max(1, Math.round((float) HEART_SIZE_TARGET / nativeSize));

        HeartLayout hl      = computeHeartLayout(totalHearts, heartSize, layout.backgroundWidth);
        int         rows    = Math.max(1, hl.numRows());
        float       topRowY = layout.backgroundY - HEART_Y_GAP - (rows - 1) * (heartSize + HEART_ROW_GAP);
        float       staminaY = topRowY - STAMINA_BAR_GAP - STAMINA_BAR_HEIGHT;
        float       barY     = staminaY - MANA_BAR_GAP - MANA_BAR_HEIGHT;
        float       fillW    = layout.backgroundWidth
                * Math.max(0f, Math.min(1f, player.getMana() / maxMana));

        MPainter.fillRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, MANA_BAR_HEIGHT, STAMINA_BG);
        if (fillW > 0) {
            MPainter.fillRect(canvas, layout.backgroundX, barY, fillW, MANA_BAR_HEIGHT, MANA_FILL);
        }
        MPainter.strokeRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, MANA_BAR_HEIGHT, STAMINA_BORDER, 1f);
    }

    /**
     * Draws the Berserker Rage panel — three tier segments (T1/T2/T3), a "Rage: T<N>"
     * label, and one live tier-bonus line per unlocked ability — to the right of the
     * hotbar. Renders only when the player's selected class is the Berserker.
     */
    private void drawRageIndicator(Canvas canvas, HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;
        if (!BerserkerAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;

        RageController rage = player.getBerserkerAbilities().getRage();
        RageTier tier = rage.getTier();
        float currentRage = rage.getRage();
        float[] thresholds = { RAGE_T1_THRESHOLD, RAGE_T2_THRESHOLD, RAGE_T3_THRESHOLD };

        float panelX = layout.backgroundX + layout.backgroundWidth + RAGE_PANEL_GAP;
        float y = layout.backgroundY;

        Font font = ui.fonts().getScaled(MStyle.FONT_META);
        String tierLabel = "Rage: T" + tier.ordinal();
        float labelX = drawIconBeforeText(canvas, RAGE_ICON_PATH, panelX, y, MStyle.FONT_META);
        MPainter.drawStringWithShadow(canvas, tierLabel, labelX, y + MStyle.FONT_META,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        y += MStyle.FONT_META + RAGE_LABEL_GAP;

        for (int i = 0; i < thresholds.length; i++) {
            float segMin = i == 0 ? 0f : thresholds[i - 1];
            float segMax = thresholds[i];
            float fraction = Math.max(0f, Math.min(1f, (currentRage - segMin) / (segMax - segMin)));

            MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH, RAGE_SEGMENT_HEIGHT, RAGE_SEGMENT_BG);
            if (fraction >= 1f) {
                MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH, RAGE_SEGMENT_HEIGHT, RAGE_SEGMENT_FILLED);
            } else if (fraction > 0f) {
                MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH * fraction, RAGE_SEGMENT_HEIGHT, RAGE_SEGMENT_PARTIAL);
            }
            MPainter.strokeRect(canvas, panelX, y, RAGE_PANEL_WIDTH, RAGE_SEGMENT_HEIGHT, RAGE_SEGMENT_BORDER, 1f);

            y += RAGE_SEGMENT_HEIGHT + RAGE_SEGMENT_GAP;
        }

        y += RAGE_BONUS_LINE_GAP;
        var stats = player.getCharacterStats();
        if (stats.getSpentCp(BerserkerAbilityController.RAMPAGE_KEY) > 0) {
            y += MStyle.FONT_META;
            float bonusX = drawIconBeforeText(canvas, RAMPAGE_ICON_PATH, panelX, y - MStyle.FONT_META, MStyle.FONT_META);
            MPainter.drawStringWithShadow(canvas, BerserkerTierText.rampageBonus(tier), bonusX, y,
                    font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            y += RAGE_BONUS_LINE_GAP;
        }
        if (stats.getSpentCp(BerserkerAbilityController.SKULL_CRUSHER_KEY) > 0) {
            y += MStyle.FONT_META;
            float bonusX = drawIconBeforeText(canvas, SKULL_CRUSHER_ICON_PATH, panelX, y - MStyle.FONT_META, MStyle.FONT_META);
            MPainter.drawStringWithShadow(canvas, BerserkerTierText.skullCrusherBonus(tier), bonusX, y,
                    font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        }
    }

    /**
     * Draws the Ranger Quarry panel — header with the current Quarry and Study count,
     * three discrete Study pips (the topmost dims while the decay window has elapsed),
     * the Quarry's HP bar, the stack-1 armor/resistance reveal, and one live status line
     * per unlocked ability — to the right of the hotbar. Renders only when the player's
     * selected class is the Ranger.
     */
    private void drawQuarryIndicator(Canvas canvas, HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;
        if (!RangerAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;

        RangerAbilityController ranger = player.getRangerAbilities();
        QuarryController quarry = ranger.getQuarry();
        LivingEntity target = quarry.getQuarry();
        int stacks = quarry.getStudyStacks();
        boolean decaying = quarry.getDecayProgress() >= 1f;

        float panelX = layout.backgroundX + layout.backgroundWidth + RAGE_PANEL_GAP;
        float y = layout.backgroundY;

        Font font = ui.fonts().getScaled(MStyle.FONT_META);
        float labelX = drawIconBeforeText(canvas, QUARRY_ICON_PATH, panelX, y, MStyle.FONT_META);
        MPainter.drawStringWithShadow(canvas, RangerHudText.quarryStatus(quarry), labelX, y + MStyle.FONT_META,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        y += MStyle.FONT_META + RAGE_LABEL_GAP;

        for (int i = 0; i < com.stonebreak.player.PlayerConstants.RANGER_STUDY_MAX_STACKS; i++) {
            MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH, QUARRY_PIP_HEIGHT, RAGE_SEGMENT_BG);
            if (i < stacks) {
                boolean topPip = i == stacks - 1;
                int fill = topPip && decaying ? QUARRY_PIP_DIMMED : QUARRY_PIP_FILLED;
                MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH, QUARRY_PIP_HEIGHT, fill);
            }
            MPainter.strokeRect(canvas, panelX, y, RAGE_PANEL_WIDTH, QUARRY_PIP_HEIGHT, RAGE_SEGMENT_BORDER, 1f);
            y += QUARRY_PIP_HEIGHT + QUARRY_PIP_GAP;
        }

        if (target != null) {
            float hpFraction = target.getMaxHealth() > 0f
                    ? Math.max(0f, Math.min(1f, target.getHealth() / target.getMaxHealth()))
                    : 0f;
            MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH, QUARRY_HP_HEIGHT, RAGE_SEGMENT_BG);
            if (hpFraction > 0f) {
                MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH * hpFraction, QUARRY_HP_HEIGHT, QUARRY_HP_FILL);
            }
            MPainter.strokeRect(canvas, panelX, y, RAGE_PANEL_WIDTH, QUARRY_HP_HEIGHT, RAGE_SEGMENT_BORDER, 1f);
            y += QUARRY_HP_HEIGHT + QUARRY_LINE_GAP;
        }

        if (target != null && stacks >= 1) {
            y += MStyle.FONT_META;
            MPainter.drawStringWithShadow(canvas, RangerHudText.revealLine(target.getType()), panelX, y,
                    font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            y += QUARRY_LINE_GAP;
        }
        if (target != null && stacks >= 2) {
            y += MStyle.FONT_META;
            MPainter.drawStringWithShadow(canvas, "Weak point exposed", panelX, y,
                    font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
            y += QUARRY_LINE_GAP;
        }

        y += RAGE_BONUS_LINE_GAP;
        var stats = player.getCharacterStats();
        if (stats.getSpentCp(RangerAbilityController.SNARE_KEY) > 0) {
            y += MStyle.FONT_META;
            float lineX = drawIconBeforeText(canvas, SNARE_ICON_PATH, panelX, y - MStyle.FONT_META, MStyle.FONT_META);
            MPainter.drawStringWithShadow(canvas, RangerHudText.snareStatus(ranger.getSnare()), lineX, y,
                    font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            y += RAGE_BONUS_LINE_GAP;
        }
        if (stats.getSpentCp(RangerAbilityController.CULLING_SHOT_KEY) > 0) {
            y += MStyle.FONT_META;
            float lineX = drawIconBeforeText(canvas, CULLING_SHOT_ICON_PATH, panelX, y - MStyle.FONT_META, MStyle.FONT_META);
            MPainter.drawStringWithShadow(canvas, RangerHudText.cullingShotStatus(ranger.getCullingShot()), lineX, y,
                    font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        }
    }

    /**
     * Draws the Arcanist Resonance panel — header with the current stack count (or
     * "OVERLOADED"), four discrete Resonance pips (all switching to hot gold while
     * Overloaded — the distinct visual indicator), the live same-school echo line, and
     * one status line per unlocked spell — to the right of the hotbar. Renders only when
     * the player's selected class is the Arcanist.
     */
    private void drawResonanceIndicator(Canvas canvas, HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;
        if (!ArcanistAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;

        ArcanistAbilityController arcanist = player.getArcanistAbilities();
        ResonanceTracker resonance = arcanist.getResonance();
        int stacks = resonance.getResonanceStacks();
        boolean overloaded = resonance.isOverloaded();

        float panelX = layout.backgroundX + layout.backgroundWidth + RAGE_PANEL_GAP;
        float y = layout.backgroundY;

        Font font = ui.fonts().getScaled(MStyle.FONT_META);
        MPainter.drawStringWithShadow(canvas, ArcanistHudText.resonanceStatus(resonance),
                panelX, y + MStyle.FONT_META, font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        y += MStyle.FONT_META + RAGE_LABEL_GAP;

        for (int i = 0; i < com.stonebreak.player.PlayerConstants.ARCANIST_RESONANCE_MAX_STACKS; i++) {
            MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH, RESONANCE_PIP_HEIGHT, RAGE_SEGMENT_BG);
            if (i < stacks) {
                int fill = overloaded ? RESONANCE_PIP_OVERLOADED : RESONANCE_PIP_FILLED;
                MPainter.fillRect(canvas, panelX, y, RAGE_PANEL_WIDTH, RESONANCE_PIP_HEIGHT, fill);
            }
            MPainter.strokeRect(canvas, panelX, y, RAGE_PANEL_WIDTH, RESONANCE_PIP_HEIGHT, RAGE_SEGMENT_BORDER, 1f);
            y += RESONANCE_PIP_HEIGHT + RESONANCE_PIP_GAP;
        }

        String sameSchool = ArcanistHudText.sameSchoolStatus(resonance);
        if (sameSchool != null) {
            y += MStyle.FONT_META;
            MPainter.drawStringWithShadow(canvas, sameSchool, panelX, y,
                    font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            y += RESONANCE_LINE_GAP;
        }

        y += RAGE_BONUS_LINE_GAP;
        var stats = player.getCharacterStats();
        // Spell status lines turn gold while Overloaded — the next cast is the empowered one
        int spellColor = overloaded ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        if (stats.getSpentCp(ArcanistAbilityController.LEYLINE_BREACH_KEY) > 0) {
            y += MStyle.FONT_META;
            MPainter.drawStringWithShadow(canvas,
                    ArcanistHudText.spellStatus("Leyline Breach", arcanist.getLeylineBreach()),
                    panelX, y, font, spellColor, MStyle.TEXT_SHADOW);
            y += RAGE_BONUS_LINE_GAP;
        }
        if (stats.getSpentCp(ArcanistAbilityController.NULL_SPIKE_KEY) > 0) {
            y += MStyle.FONT_META;
            MPainter.drawStringWithShadow(canvas,
                    ArcanistHudText.spellStatus("Null Spike", arcanist.getNullSpike()),
                    panelX, y, font, spellColor, MStyle.TEXT_SHADOW);
        }
    }

    /**
     * Draws the icon at {@code iconPath} vertically centered against a text line spanning
     * {@code [lineTopY, lineTopY + lineHeight]} at {@code lineX}, and returns the x-coordinate
     * where the line's text should start (shifted right past the icon, or {@code lineX}
     * unchanged if the icon failed to load).
     */
    private float drawIconBeforeText(Canvas canvas, String iconPath, float lineX, float lineTopY, float lineHeight) {
        Image icon = AbilityIconCache.get(iconPath);
        if (icon == null) return lineX;
        float iconY = lineTopY + (lineHeight - RAGE_ICON_SIZE) / 2f;
        MPainter.drawImage(canvas, icon, lineX, iconY, RAGE_ICON_SIZE, RAGE_ICON_SIZE);
        return lineX + RAGE_ICON_SIZE + RAGE_ICON_GAP;
    }

    private HeartLayout computeHeartLayout(int totalHearts, int heartSize, int availableWidth) {
        if (totalHearts <= 0) return new HeartLayout(0, 0, 0f);

        float naturalStep   = heartSize + HEART_SPACING;
        int   naturalPerRow = Math.max(1, (int) Math.floor((availableWidth + HEART_SPACING) / naturalStep));

        if (totalHearts <= naturalPerRow) {
            return new HeartLayout(totalHearts, 1, naturalStep);
        }

        float minStep   = heartSize * HEART_MIN_VISIBLE_FRACTION;
        int   maxPerRow = availableWidth <= heartSize ? 1
                : (int) Math.floor((availableWidth - heartSize) / minStep) + 1;

        int numRows      = (int) Math.ceil((float) totalHearts / Math.max(1, maxPerRow));
        int heartsPerRow = (int) Math.ceil((float) totalHearts / numRows);
        float step       = heartsPerRow <= 1 ? 0f
                : (float) (availableWidth - heartSize) / (heartsPerRow - 1);

        return new HeartLayout(heartsPerRow, numRows, Math.min(naturalStep, step));
    }

    private void drawSbtItemIcons(Canvas canvas, ItemStack[] slots,
                                  HotbarLayoutCalculator.HotbarLayout layout) {
        int iconSize    = HotbarLayoutCalculator.calculateIconSize();
        int iconPadding = HotbarLayoutCalculator.calculateIconPadding();

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (!(item instanceof ItemType itemType)) continue;

            if (!SpriteVoxelizer.isSboBackedItem(itemType)) continue;

            MTexture tex = MTextureRegistry.getForSboItem(itemType, stack.getState());
            if (tex == null) continue;

            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            MPainter.drawImage(canvas, tex.image(),
                    pos.x + iconPadding, pos.y + iconPadding, iconSize, iconSize);
        }
    }

    /** Largest native edge across the loaded heart variants, or the target
     *  size as a fallback when no SBT loaded. */
    private static int nativeHeartSize(MTexture... variants) {
        int max = 0;
        for (MTexture t : variants) {
            if (t == null) continue;
            int side = Math.max(t.width(), t.height());
            if (side > max) max = side;
        }
        return max > 0 ? max : HEART_SIZE_TARGET;
    }

    // ─────────────────────────────────────────────── Phase B helpers

    private void renderItemIcons(ItemStack[] slots,
                                 HotbarLayoutCalculator.HotbarLayout layout,
                                 HotbarScreen hotbarScreen) {
        int iconSize    = HotbarLayoutCalculator.calculateIconSize();
        int iconPadding = HotbarLayoutCalculator.calculateIconPadding();

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == null || !item.hasIcon()) continue;

            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            int iconX = pos.x + iconPadding;
            int iconY = pos.y + iconPadding;

            if (item instanceof BlockType bt) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt,
                        iconX, iconY, iconSize, iconSize, renderer.getBlockTextureArray());
            } else if (!(item instanceof ItemType it && SpriteVoxelizer.isSboBackedItem(it))) {
                uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize,
                        item, renderer.getBlockTextureArray());
            }
        }
    }

    // ─────────────────────────────────────────────── Phase C helpers

    private void drawCountTexts(Canvas canvas, ItemStack[] slots,
                                HotbarLayoutCalculator.HotbarLayout layout,
                                HotbarScreen hotbarScreen) {
        Font font = ui.fonts().getScaled(MStyle.FONT_META);
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty() || stack.getCount() <= 1) continue;
            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            String countStr = String.valueOf(stack.getCount());
            float  countMargin = 2f * com.stonebreak.config.Settings.getInstance().getUiScale();
            float  textX    = pos.x + pos.width  - MPainter.measureWidth(font, countStr) - countMargin;
            float  textY    = pos.y + pos.height - countMargin;
            MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                    font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── Tooltip

    private void drawTooltip(Canvas canvas, String text, float alpha,
                             int selectedIndex,
                             HotbarLayoutCalculator.HotbarLayout layout,
                             int sw, int sh) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        Font  font  = ui.fonts().getScaled(MStyle.FONT_ITEM);
        float textW = MPainter.measureWidth(font, text);
        float pad   = 8f * scale;
        float boxW  = textW + pad * 2.5f;
        float boxH  = MStyle.FONT_ITEM * scale + pad * 2f;

        // Centre tooltip above the selected slot
        HotbarLayoutCalculator.SlotPosition slotPos =
                HotbarLayoutCalculator.calculateSlotPosition(selectedIndex, layout);
        float bx  = slotPos.centerX - boxW / 2f;
        float gap = 8f * scale;
        float by  = layout.backgroundY - boxH - gap;

        // Keep within screen
        float margin = 8f * scale;
        bx = Math.max(margin, Math.min(bx, sw - boxW - margin));
        by = Math.max(margin, Math.min(by, sh - boxH - margin));

        MPainter.stoneSurface(canvas, bx, by, boxW, boxH, MStyle.PANEL_RADIUS,
                a(MStyle.PANEL_FILL_DEEP, alpha), a(MStyle.PANEL_BORDER, alpha),
                a(MStyle.PANEL_HIGHLIGHT, alpha), a(MStyle.PANEL_SHADOW, alpha),
                a(MStyle.PANEL_DROP_SHADOW, alpha),
                a(MStyle.PANEL_NOISE_DARK, alpha), a(MStyle.PANEL_NOISE_LIGHT, alpha));

        float textBaseline = by + boxH / 2f + MStyle.FONT_ITEM * 0.35f * scale;
        MPainter.drawCenteredStringWithShadow(canvas, text, bx + boxW / 2f, textBaseline,
                font, a(MStyle.TEXT_PRIMARY, alpha), a(MStyle.TEXT_SHADOW, alpha));
    }

    /** Multiply the alpha channel of an ARGB colour by {@code factor} (0–1). */
    private static int a(int argb, float factor) {
        int   newA = Math.min(255, (int)(((argb >>> 24) & 0xFF) * factor));
        return (newA << 24) | (argb & 0x00FFFFFF);
    }
}
