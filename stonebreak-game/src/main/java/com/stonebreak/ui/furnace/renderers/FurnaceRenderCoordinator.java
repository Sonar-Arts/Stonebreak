package com.stonebreak.ui.furnace.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.MHotbarRenderer;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MTooltip;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.furnace.core.FurnaceController;
import com.stonebreak.ui.furnace.core.FurnaceInputManager;
import com.stonebreak.ui.furnace.core.FurnaceLayout;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import org.joml.Vector2f;

/**
 * Renders the furnace screen in the same three-phase pattern as inventory/workbench:
 *   A) Skija – panel, furnace chrome, slot backgrounds, progress bars
 *   B) GL    – item icons
 *   C) Skija – count text overlays
 */
public class FurnaceRenderCoordinator {

    private static final int PANEL_FILL_TRANS = 0xBF6B6B6B;
    private static final int PROGRESS_FILL    = 0xFFE87D1C;   // orange smelt-fill
    private static final long ANIM_EPOCH      = System.nanoTime();

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final InputHandler inputHandler;
    private final Inventory inventory;
    private final FurnaceController controller;
    private final FurnaceInputManager inputManager;
    private final MasonryUI ui;
    private final MHotbarRenderer mHotbarRenderer;

    public FurnaceRenderCoordinator(UIRenderer uiRenderer,
                                    Renderer renderer,
                                    InputHandler inputHandler,
                                    Inventory inventory,
                                    FurnaceController controller,
                                    FurnaceInputManager inputManager,
                                    com.stonebreak.crafting.SmeltingManager smeltingManager) {
        this.uiRenderer      = uiRenderer;
        this.renderer        = renderer;
        this.inputHandler    = inputHandler;
        this.inventory       = inventory;
        this.controller      = controller;
        this.inputManager    = inputManager;
        this.ui              = new MasonryUI(renderer.getSkijaBackend());
        this.mHotbarRenderer = new MHotbarRenderer(uiRenderer, renderer);
    }

    /* ── Public entry points ─────────────────────────────── */

    public void render(int screenWidth, int screenHeight) {
        renderWithoutTooltips(screenWidth, screenHeight);
        renderTooltipsOnly(screenWidth, screenHeight);
    }

    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        controller.setHoveredItemStack(null);

        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);

        Vector2f mouse = inputHandler.getMousePosition();
        float mouseX = mouse.x, mouseY = mouse.y;

        // Phase A – Skija: chrome
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawPanel(canvas, layout);
            drawFurnaceSection(canvas, layout, mouseX, mouseY);
            drawInventorySection(layout, mouseX, mouseY);
            ui.renderOverlays();
            ui.endFrame();
        }

        // Phase B – GL: item icons
        renderItemIcons(layout);

        // Phase C – Skija: count text
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            drawAllCountTexts(ui.canvas(), layout);
            ui.endFrame();
        }
    }

    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        ItemStack hovered = controller.getHoveredItemStack();
        if (hovered == null || hovered.isEmpty() || inputManager.getDragState().isDragging()) return;
        Item item = hovered.getItem();
        if (item == null || item == BlockType.AIR) return;

        Vector2f mouse = inputHandler.getMousePosition();
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            MTooltip.draw(ui, item.getName(), mouse.x + 15, mouse.y + 15,
                          screenWidth, screenHeight);
            ui.endFrame();
        }
    }

    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        InventoryDragDropHandler.DragState ds = inputManager.getDragState();
        if (!ds.isDragging()) return;
        Item item = ds.draggedItemStack.getItem();
        if (item == null || !item.hasIcon()) return;

        Vector2f mouse = inputHandler.getMousePosition();
        int iconSize = InventoryLayoutCalculator.getSlotSize() - 4;
        int iconX = (int)(mouse.x - iconSize / 2f);
        int iconY = (int)(mouse.y - iconSize / 2f);

        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, iconX, iconY,
                    iconSize, iconSize, renderer.getBlockTextureArray(), true);
        } else {
            uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize, item, renderer.getBlockTextureArray());
        }

        int count = ds.draggedItemStack.getCount();
        if (count > 1 && ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            Font font = ui.fonts().getScaled(MStyle.FONT_META);
            String countStr = String.valueOf(count);
            float textX = iconX + iconSize - MPainter.measureWidth(font, countStr) - 2f;
            float textY = iconY + iconSize - 2f;
            MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                    font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
            ui.endFrame();
        }
    }

    public void renderHotbar(int screenWidth, int screenHeight) {
        mHotbarRenderer.renderHotbar(controller.getHotbarScreen(), screenWidth, screenHeight);
        mHotbarRenderer.renderHotbarTooltip(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        mHotbarRenderer.renderHotbar(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        mHotbarRenderer.renderHotbarTooltip(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    /* ── Phase A helpers ─────────────────────────────────── */

    private void drawPanel(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        MPainter.stoneSurface(canvas,
                layout.panelStartX, layout.panelStartY,
                layout.inventoryPanelWidth, layout.inventoryPanelHeight,
                MStyle.PANEL_RADIUS,
                PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    private void drawFurnaceSection(Canvas canvas,
                                   InventoryLayoutCalculator.InventoryLayout layout,
                                   float mouseX, float mouseY) {
        int panelPad  = InventoryLayoutCalculator.getPanelPadding();
        int titleH    = InventoryLayoutCalculator.getTitleHeight();

        // Title
        Font font = ui.fonts().getScaled(MStyle.FONT_BUTTON);
        float centerX = layout.panelStartX + layout.inventoryPanelWidth / 2f;
        float titleY = layout.panelStartY + panelPad + titleH + InventoryLayoutCalculator.getSectionSpacing();
        MPainter.drawCenteredStringWithShadow(canvas, "Furnace", centerX, titleY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        FurnaceLayout.Slots s = FurnaceLayout.compute(layout);

        // Crucible (heat-colored disk + chutes to slots) drawn BEFORE the slots
        // so the slot frames sit on top of the chutes' endpoints.
        drawCrucible(canvas, s);

        // Slots — top (ingredient), bottom (fuel), right (output)
        drawSlot(s.ingredientX, s.ingredientY, s.slotSize, mouseX, mouseY, false);
        checkHover(controller.getIngredientSlot(), s.ingredientX, s.ingredientY, s.slotSize, mouseX, mouseY);

        drawSlot(s.fuelX, s.fuelY, s.slotSize, mouseX, mouseY, false);
        checkHover(controller.getFuelSlot(), s.fuelX, s.fuelY, s.slotSize, mouseX, mouseY);

        drawSlot(s.outputX, s.outputY, s.slotSize, mouseX, mouseY, false);
        checkHover(controller.getOutputSlot(), s.outputX, s.outputY, s.slotSize, mouseX, mouseY);

        // Progress ring sweeping clockwise from ingredient (12 o'clock) toward output (3 o'clock)
        drawProgressRing(canvas, s);

        // Player inventory title
        float invTitleY = layout.mainInvContentStartY - 20;
        MPainter.drawCenteredStringWithShadow(canvas, "Inventory", centerX, invTitleY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    /** Crucible: dark bowl with rising lava (= fuel left), animated flames + bubbles. */
    private void drawCrucible(Canvas canvas, FurnaceLayout.Slots s) {
        float cx = s.crucibleCenterX;
        float cy = s.crucibleCenterY;
        float r  = s.crucibleRadius;
        float t  = animTime();

        // Chutes (drawn under the disk so chrome overlaps cleanly)
        int chuteColor = 0xA04A4A4A;
        float ct = 6f;
        MPainter.fillRoundedRect(canvas, cx - ct / 2f, s.ingredientY + s.slotSize,
                ct, cy - (s.ingredientY + s.slotSize), 2f, chuteColor);
        MPainter.fillRoundedRect(canvas, cx - ct / 2f, cy,
                ct, s.fuelY - cy, 2f, chuteColor);
        MPainter.fillRoundedRect(canvas, cx, cy - ct / 2f,
                s.outputX - cx, ct, 2f, chuteColor);

        float fuelRatio = controller.getFuelRatio();          // 0..1 of currently-lit unit
        boolean lit     = controller.isCooking() || fuelRatio > 0f;

        // Outer pulsing glow (only when lit). Two soft halos.
        if (lit) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(t * 4.0);
            int glowOuter = withAlpha(heatColor(fuelRatio), (int)(60 * pulse));
            int glowInner = withAlpha(heatColor(fuelRatio), (int)(110 * pulse));
            try (Paint p = new Paint().setColor(glowOuter)) {
                canvas.drawCircle(cx, cy, r + 9f, p);
            }
            try (Paint p = new Paint().setColor(glowInner)) {
                canvas.drawCircle(cx, cy, r + 5f, p);
            }
        }

        // Outer dark rim
        try (Paint p = new Paint().setColor(0xFF1A1A1A)) {
            canvas.drawCircle(cx, cy, r + 3f, p);
        }
        // Rim highlight (subtle metallic bevel)
        try (Paint p = new Paint().setColor(0xFF3A3A3A).setMode(PaintMode.STROKE).setStrokeWidth(1.5f)) {
            canvas.drawCircle(cx, cy, r + 2f, p);
        }
        // Dark bowl interior (visible when fuel low)
        try (Paint p = new Paint().setColor(0xFF0A0405)) {
            canvas.drawCircle(cx, cy, r, p);
        }

        // Lava fill — clip to a polygon-approximated crucible circle, then
        // draw a rectangle from the bottom up to (1 - fuelRatio)*diameter from
        // the top, with a wavy top edge.
        if (lit) {
            int save = canvas.save();
            try (PathBuilder pb = new PathBuilder()) {
                int steps = 36;
                float clipR = r - 1f;
                pb.moveTo(cx + clipR, cy);
                for (int i = 1; i <= steps; i++) {
                    double a = 2 * Math.PI * i / steps;
                    pb.lineTo((float)(cx + clipR * Math.cos(a)),
                              (float)(cy + clipR * Math.sin(a)));
                }
                try (Path circle = pb.build()) {
                    canvas.clipPath(circle, true);
                }
            }
            float lavaTop = cy + r - (2f * r) * Math.max(fuelRatio, 0.05f);
            drawLava(canvas, cx, cy, r, lavaTop, fuelRatio, t);
            drawBubbles(canvas, cx, cy, r, lavaTop, t);
            drawFlameTongues(canvas, cx, lavaTop, r, fuelRatio, t);
            canvas.restoreToCount(save);
        }

        // Inner highlight (top-left) — sits on the rim, makes it feel rounded.
        try (Paint p = new Paint().setColor(0x28FFFFFF)) {
            canvas.drawCircle(cx - r * 0.30f, cy - r * 0.30f, r * 0.30f, p);
        }
    }

    private void drawLava(Canvas canvas, float cx, float cy, float r,
                          float lavaTop, float fuelRatio, float t) {
        int hot  = heatColor(Math.min(1f, fuelRatio + 0.20f));
        int dim  = heatColor(Math.max(0f, fuelRatio - 0.30f));

        // Main lava body — polygon with wavy top, flat sides, flat bottom.
        try (PathBuilder pb = new PathBuilder()) {
            float amp  = Math.min(3f, r * 0.10f);
            float freq = 0.45f;
            float step = 1.5f;
            pb.moveTo(cx - r, cy + r + 2f);
            for (float x = -r; x <= r; x += step) {
                float y = lavaTop + (float) Math.sin(x * freq + t * 3.2) * amp
                                  + (float) Math.sin(x * freq * 2.3 - t * 1.7) * amp * 0.5f;
                pb.lineTo(cx + x, y);
            }
            pb.lineTo(cx + r, cy + r + 2f);
            pb.lineTo(cx - r, cy + r + 2f);
            try (Path lava = pb.build();
                 Paint p = new Paint().setColor(dim).setAntiAlias(true)) {
                canvas.drawPath(lava, p);
            }
        }
        // Hotter inner highlight band just under the surface.
        try (PathBuilder pb = new PathBuilder()) {
            float amp = Math.min(2.5f, r * 0.08f);
            float freq = 0.45f;
            float step = 1.5f;
            float bandH = Math.min(6f, r * 0.30f);
            pb.moveTo(cx - r, lavaTop + bandH);
            for (float x = -r; x <= r; x += step) {
                float y = lavaTop + (float) Math.sin(x * freq + t * 3.2) * amp;
                pb.lineTo(cx + x, y);
            }
            pb.lineTo(cx + r, lavaTop + bandH);
            pb.lineTo(cx - r, lavaTop + bandH);
            try (Path band = pb.build();
                 Paint p = new Paint().setColor(withAlpha(hot, 170)).setAntiAlias(true)) {
                canvas.drawPath(band, p);
            }
        }
    }

    private void drawBubbles(Canvas canvas, float cx, float cy, float r,
                             float lavaTop, float t) {
        // Three drifting bubbles with deterministic phases so they don't all sync.
        for (int i = 0; i < 3; i++) {
            float phase = i * 1.7f;
            float cycle = (float) ((t * 0.9 + phase) % 1.6);
            if (cycle > 1.2f) continue; // brief gap before each bubble re-emerges
            float lifeT = cycle / 1.2f;  // 0..1 over the bubble's visible life
            float bx = cx + (float) Math.sin(phase * 3.1 + t * 0.7) * r * 0.55f;
            float by = lavaTop + (cy + r - lavaTop) * (1f - lifeT) * 0.8f + 2f;
            float br = 1.5f + 1.5f * (1f - lifeT);
            int alpha = (int) (220 * (1f - lifeT));
            try (Paint p = new Paint().setColor(withAlpha(0xFFFFE2A8, alpha))) {
                canvas.drawCircle(bx, by, br, p);
            }
        }
    }

    private void drawFlameTongues(Canvas canvas, float cx, float lavaTop, float r,
                                  float fuelRatio, float t) {
        // 5 little flame tongues licking up from the lava surface. Each flame
        // is an approximated bezier — left edge + right edge, both built as
        // short line segments since Skija's PathBuilder is moveTo/lineTo-only.
        int flameCore = heatColor(Math.min(1f, fuelRatio + 0.35f));
        int flameTip  = withAlpha(0xFFFFE2A8, 200);
        float maxH = Math.min(r * 0.65f, 14f);
        int n = 5;
        for (int i = 0; i < n; i++) {
            float xi = -r * 0.75f + (i + 0.5f) * (1.5f * r / n);
            float wob = (float) Math.sin(t * 6.0 + i * 1.3) * 0.5f
                      + (float) Math.sin(t * 11.0 + i * 2.7) * 0.25f;
            float h = maxH * (0.55f + 0.35f * (float) Math.sin(t * 5.5 + i * 1.9));
            h *= 0.7f + 0.3f * Math.max(0.1f, fuelRatio);
            float baseY = lavaTop + (float) Math.sin(xi * 0.45f + t * 3.2) * 1.5f;
            float tipX  = cx + xi + wob * 2f;
            float tipY  = baseY - h;
            float halfW = 2.2f;

            float baseLX = cx + xi - halfW;
            float baseRX = cx + xi + halfW;
            float ctlLX  = cx + xi - halfW * 1.4f;
            float ctlRX  = cx + xi + halfW * 1.4f;
            float ctlY   = baseY - h * 0.5f;

            try (PathBuilder pb = new PathBuilder()) {
                pb.moveTo(baseLX, baseY);
                int steps = 8;
                // Left edge: base → tip via quadratic bezier (baseLX,baseY) (ctlLX,ctlY) (tipX,tipY)
                for (int k = 1; k <= steps; k++) {
                    float u = k / (float) steps;
                    float omu = 1f - u;
                    float px = omu * omu * baseLX + 2f * omu * u * ctlLX + u * u * tipX;
                    float py = omu * omu * baseY  + 2f * omu * u * ctlY  + u * u * tipY;
                    pb.lineTo(px, py);
                }
                // Right edge: tip → base via (tipX,tipY) (ctlRX,ctlY) (baseRX,baseY)
                for (int k = 1; k <= steps; k++) {
                    float u = k / (float) steps;
                    float omu = 1f - u;
                    float px = omu * omu * tipX + 2f * omu * u * ctlRX + u * u * baseRX;
                    float py = omu * omu * tipY + 2f * omu * u * ctlY  + u * u * baseY;
                    pb.lineTo(px, py);
                }
                pb.lineTo(baseLX, baseY);
                try (Path flame = pb.build();
                     Paint p = new Paint().setColor(flameCore).setAntiAlias(true)) {
                    canvas.drawPath(flame, p);
                }
            }
            try (Paint p = new Paint().setColor(flameTip)) {
                canvas.drawCircle(tipX, tipY + 0.5f, 1.2f, p);
            }
        }
    }

    /** Two concentric rings: outer = cook progress (full 360°), inner = fuel-left. */
    private void drawProgressRing(Canvas canvas, FurnaceLayout.Slots s) {
        float cookRatio = controller.getCookProgressRatio();
        float fuelRatio = controller.getFuelRatio();
        float cx = s.crucibleCenterX;
        float cy = s.crucibleCenterY;
        float outerR  = s.crucibleRadius + 8f;
        float innerR  = s.crucibleRadius + 4f;
        float strokeW = 3f;

        // Track (dim background full circle) for the cook ring
        try (Paint p = new Paint()
                .setColor(0x40000000)
                .setMode(PaintMode.STROKE)
                .setStrokeWidth(strokeW)) {
            canvas.drawCircle(cx, cy, outerR, p);
        }
        // Cook progress sweep — full 360° from 12 o'clock clockwise
        if (cookRatio > 0f) {
            float sweep = 360f * Math.min(cookRatio, 1f);
            try (Paint p = new Paint()
                    .setColor(PROGRESS_FILL)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(strokeW)) {
                canvas.drawArc(cx - outerR, cy - outerR, cx + outerR, cy + outerR,
                        -90f, sweep, false, p);
            }
        }
        // Fuel ring — inner concentric, full 360° driven by remaining-fuel-in-unit
        if (fuelRatio > 0f) {
            float sweep = 360f * Math.min(fuelRatio, 1f);
            try (Paint p = new Paint()
                    .setColor(0xFFFFC04A)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(2f)) {
                canvas.drawArc(cx - innerR, cy - innerR, cx + innerR, cy + innerR,
                        -90f, sweep, false, p);
            }
        }
    }

    private float animTime() {
        return (System.nanoTime() - ANIM_EPOCH) / 1_000_000_000f;
    }

    private static int withAlpha(int argb, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private int heatColor(float heat) {
        // Heat-only ramp: dark-red → orange → near-white.
        float h = Math.max(heat, 0.15f); // floor so a lit furnace always glows a little
        if (h >= 1f) h = 1f;
        // Lerp dark-red (0x50, 0x10, 0x05) → orange (0xE8, 0x7D, 0x1C) → near-white (0xFF, 0xE8, 0xB0).
        int r, g, b;
        if (h < 0.5f) {
            float t = h / 0.5f;
            r = (int) (0x50 + t * (0xE8 - 0x50));
            g = (int) (0x10 + t * (0x7D - 0x10));
            b = (int) (0x05 + t * (0x1C - 0x05));
        } else {
            float t = (h - 0.5f) / 0.5f;
            r = (int) (0xE8 + t * (0xFF - 0xE8));
            g = (int) (0x7D + t * (0xE8 - 0x7D));
            b = (int) (0x1C + t * (0xB0 - 0x1C));
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawInventorySection(InventoryLayoutCalculator.InventoryLayout layout,
                                      float mouseX, float mouseY) {
        int slotSize  = InventoryLayoutCalculator.getSlotSize();
        int padding   = InventoryLayoutCalculator.getSlotPadding();
        ItemStack[] mainSlots   = inventory.getMainInventorySlots();
        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        int selectedHotbar      = inventory.getSelectedHotbarSlotIndex();

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            float sx = layout.inventorySectionStartX + padding + col * (slotSize + padding);
            float sy = layout.mainInvContentStartY + padding + row * (slotSize + padding);
            drawSlot(sx, sy, slotSize, mouseX, mouseY, false);
            checkHover(mainSlots[i], sx, sy, slotSize, mouseX, mouseY);
        }

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            float sx = layout.inventorySectionStartX + padding + i * (slotSize + padding);
            float sy = layout.hotbarRowY;
            boolean selected = (i == selectedHotbar);
            drawSlot(sx, sy, slotSize, mouseX, mouseY, selected);
            checkHover(hotbarSlots[i], sx, sy, slotSize, mouseX, mouseY);
        }
    }

    private void drawSlot(float x, float y, int size, float mouseX, float mouseY,
                          boolean hotbarSelected) {
        MItemSlot slot = new MItemSlot()
                .hotbarSelected(hotbarSelected)
                .bounds(x, y, size, size);
        slot.updateHover(mouseX, mouseY);
        slot.render(ui);
    }

    /* ── Phase B – GL item icons ──────────────────────────── */

    private void renderItemIcons(InventoryLayoutCalculator.InventoryLayout layout) {
        int slotSize  = InventoryLayoutCalculator.getSlotSize();
        int padding   = InventoryLayoutCalculator.getSlotPadding();
        int iconInset = 3;
        int iconSize  = slotSize - iconInset * 2;

        FurnaceLayout.Slots s = FurnaceLayout.compute(layout);
        drawItemIcon(controller.getIngredientSlot(), s.ingredientX + iconInset, s.ingredientY + iconInset, iconSize);
        drawItemIcon(controller.getFuelSlot(),       s.fuelX + iconInset,       s.fuelY + iconInset,       iconSize);
        drawItemIcon(controller.getOutputSlot(),     s.outputX + iconInset,     s.outputY + iconInset,     iconSize);

        // Inventory
        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int sx = layout.inventorySectionStartX + padding + col * (slotSize + padding);
            int sy = layout.mainInvContentStartY + padding + row * (slotSize + padding);
            drawItemIcon(mainSlots[i], sx + iconInset, sy + iconInset, iconSize);
        }

        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int sx = layout.inventorySectionStartX + padding + i * (slotSize + padding);
            int sy = layout.hotbarRowY;
            drawItemIcon(hotbarSlots[i], sx + iconInset, sy + iconInset, iconSize);
        }
    }

    private void drawItemIcon(ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) return;
        Item item = stack.getItem();
        if (item == null || !item.hasIcon()) return;

        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y, size, size,
                    renderer.getBlockTextureArray());
        } else {
            uiRenderer.renderItemIcon(x, y, size, size, item, renderer.getBlockTextureArray());
        }
    }

    /* ── Phase C – count texts ────────────────────────────── */

    private void drawAllCountTexts(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        Font font       = ui.fonts().getScaled(MStyle.FONT_META);
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int padding     = InventoryLayoutCalculator.getSlotPadding();
        FurnaceLayout.Slots s = FurnaceLayout.compute(layout);
        drawCountText(canvas, font, controller.getIngredientSlot(), s.ingredientX, s.ingredientY, slotSize);
        drawCountText(canvas, font, controller.getFuelSlot(),       s.fuelX,       s.fuelY,       slotSize);
        drawCountText(canvas, font, controller.getOutputSlot(),     s.outputX,     s.outputY,     slotSize);

        // Inventory
        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            float sx = layout.inventorySectionStartX + padding + col * (slotSize + padding);
            float sy = layout.mainInvContentStartY + padding + row * (slotSize + padding);
            drawCountText(canvas, font, mainSlots[i], sx, sy, slotSize);
        }

        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            float sx = layout.inventorySectionStartX + padding + i * (slotSize + padding);
            float sy = layout.hotbarRowY;
            drawCountText(canvas, font, hotbarSlots[i], sx, sy, slotSize);
        }
    }

    private void drawCountText(Canvas canvas, Font font, ItemStack stack,
                               float slotX, float slotY, int slotSize) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 1) return;
        String countStr = String.valueOf(stack.getCount());
        float textX = slotX + slotSize - MPainter.measureWidth(font, countStr) - 2f;
        float textY = slotY + slotSize - 2f;
        MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    /* ── Utilities ───────────────────────────────────────── */

    private void checkHover(ItemStack stack, float sx, float sy, int size,
                            float mouseX, float mouseY) {
        if (stack == null || stack.isEmpty()) return;
        if (mouseX >= sx && mouseX <= sx + size && mouseY >= sy && mouseY <= sy + size) {
            controller.setHoveredItemStack(stack);
        }
    }
}
