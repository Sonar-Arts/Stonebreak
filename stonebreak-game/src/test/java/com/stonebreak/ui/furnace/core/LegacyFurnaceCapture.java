package com.stonebreak.ui.furnace.core;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.furnace.renderers.FurnaceRenderCoordinator;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.support.UiTestFixtures;
import com.openmason.engine.util.BlockPos;
import org.joml.Vector2f;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The legacy side of the furnace's fidelity gate (#296, consumed by #298): the real
 * {@link FurnaceRenderCoordinator} on a {@link LegacyUiRaster} (crucible animation on the pinned
 * {@code LegacyUiClock}), bound to a {@link FurnaceState} built for the variant.
 *
 * <p>Slots are empty on purpose: item icons are the GL phase of the renderer (3D block icons,
 * ledger hard visual 2), which a CPU raster cannot draw; the chrome, crucible, progress rings,
 * slot frames, titles and hover are all here. Icons are a separate fidelity concern for #298's
 * host draw provider. The renderer's GL collaborators are mocks that are never asked to draw.
 *
 * <p>Variants: {@code unlit}; {@code lit} (fuel 75 %, cook 60 %); {@code lit-hover-<slot>} with the
 * pointer over {@code ingredient}, {@code fuel}, {@code output}, {@code mainN} or {@code hotN}.
 * Must live in this package for {@link FurnaceController#bind}. Not a test class.
 */
public final class LegacyFurnaceCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        String[] v = c.variant().split("-");
        boolean lit = switch (v[0]) {
            case "lit" -> true;
            case "unlit" -> false;
            default -> throw new IllegalArgumentException("furnace variant must start with lit or unlit: " + c.variant());
        };
        String hover = v.length >= 3 && v[1].equals("hover") ? v[2] : "";
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            Map<String, float[]> rects = rects(w, h);
            float[] target = hover.isEmpty() ? null : rects.get(hover);
            if (!hover.isEmpty() && target == null) {
                throw new IllegalArgumentException("unknown furnace slot " + hover);
            }
            Vector2f mouse = target == null ? new Vector2f(-100, -100)
                : new Vector2f(target[0] + target[2] / 2f, target[1] + target[3] / 2f);

            Renderer renderer = mock(Renderer.class);
            when(renderer.getSkijaBackend()).thenReturn(raster.backend());
            InputHandler input = mock(InputHandler.class);
            when(input.getMousePosition()).thenReturn(mouse);
            Inventory inventory = UiTestFixtures.emptyInventory();
            FurnaceController controller = new FurnaceController(null, inventory, null, null, null);
            controller.bind(state(lit));
            FurnaceRenderCoordinator coordinator = new FurnaceRenderCoordinator(mock(UIRenderer.class), renderer,
                input, inventory, controller, null, (SmeltingManager) null);
            coordinator.renderWithoutTooltips(w, h);
            return new MigrationGate.Capture(raster.capture(), rects);
        }
    }

    private static FurnaceState state(boolean lit) {
        if (!lit) {
            return new FurnaceState(new BlockPos(0, 64, 0));
        }
        int cook = SmeltingManager.TICKS_PER_SMELT * 3 / 5;
        return FurnaceState.fromStateString(new BlockPos(0, 64, 0),
            FurnaceState.STATE_PREFIX + "state=Lit;burn=1200;burnTotal=1600;cook=" + cook);
    }

    /**
     * The renderer's own layout maths at the current UI scale, named as in
     * {@code ui/fixtures/legacy-geometry.json}: {@code panel}, the three furnace slots,
     * {@code main0..26} and {@code hot0..8}.
     */
    public static Map<String, float[]> rects(int w, int h) {
        InventoryLayoutCalculator.InventoryLayout l = InventoryLayoutCalculator.calculateWorkbenchLayout(w, h);
        FurnaceLayout.Slots s = FurnaceLayout.compute(l);
        int ss = InventoryLayoutCalculator.getSlotSize();
        int pad = InventoryLayoutCalculator.getSlotPadding();
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("panel", new float[]{l.panelStartX, l.panelStartY, l.inventoryPanelWidth, l.inventoryPanelHeight});
        out.put("ingredient", new float[]{s.ingredientX, s.ingredientY, ss, ss});
        out.put("fuel", new float[]{s.fuelX, s.fuelY, ss, ss});
        out.put("output", new float[]{s.outputX, s.outputY, ss, ss});
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            out.put("main" + i, new float[]{l.inventorySectionStartX + pad + col * (ss + pad),
                l.mainInvContentStartY + pad + row * (ss + pad), ss, ss});
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            out.put("hot" + i, new float[]{l.inventorySectionStartX + pad + i * (ss + pad), l.hotbarRowY, ss, ss});
        }
        return out;
    }
}
