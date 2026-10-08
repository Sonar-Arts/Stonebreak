package com.stonebreak.ui.inventoryScreen.core;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.input.InputHandler;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.inventoryScreen.renderers.InventoryRenderCoordinator;
import org.joml.Vector2f;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The legacy side of the inventory screen's fidelity gate (#300): the real
 * {@link InventoryRenderCoordinator} (tab strip, the three-column panel, the left column's level,
 * equipment, vitals and status, the crafting grid, buttons, inventory, counts, the right column's
 * attributes and placeholders), then its tooltip pass, on a {@link LegacyUiRaster}, bound to an
 * {@link InventoryFixtures} player. GL collaborators are mocks that never draw anything visible.
 *
 * <p>Variants: {@code empty}, {@code stocked}, either with {@code -hover-<target>} (a slot, a button,
 * {@code tabN}, {@code equipN}, {@code statusN}). Not a test class.
 */
public final class LegacyInventoryCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            InventoryFixtures.Player3 p = InventoryFixtures.player(InventoryFixtures.content(c.variant()));
            InventoryInputManager input = InventoryFixtures.input(p);
            InventoryController controller = new InventoryController(p.inventory(), input, p.grid(), null);
            controller.setVisible(true);
            boolean craftAll = !p.grid().getCraftingOutputSlot().isEmpty();
            Map<String, float[]> rects = InventoryFixtures.rects(w, h, craftAll);
            String hover = InventoryFixtures.hover(c.variant());
            float[] target = hover.isEmpty() ? null : rects.get(hover);
            if (!hover.isEmpty() && target == null) {
                throw new IllegalArgumentException("unknown inventory target " + hover);
            }
            Vector2f mouse = target == null ? new Vector2f(-100, -100)
                : new Vector2f(target[0] + target[2] / 2f, target[1] + target[3] / 2f);

            Renderer renderer = mock(Renderer.class);
            when(renderer.getSkijaBackend()).thenReturn(raster.backend());
            InputHandler inputHandler = mock(InputHandler.class);
            when(inputHandler.getMousePosition()).thenReturn(mouse);
            InventoryRenderCoordinator coordinator = new InventoryRenderCoordinator(mock(UIRenderer.class), renderer,
                inputHandler, p.inventory(), controller, input, p.grid(), p.stats());
            controller.setRenderCoordinator(coordinator);
            coordinator.render(w, h);
            coordinator.renderTooltipsOnly(w, h);
            return new MigrationGate.Capture(raster.capture(), rects);
        }
    }
}
