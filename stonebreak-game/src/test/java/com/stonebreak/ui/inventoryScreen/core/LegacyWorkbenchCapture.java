package com.stonebreak.ui.inventoryScreen.core;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.input.InputHandler;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.inventoryScreen.renderers.WorkbenchRenderCoordinator;
import org.joml.Vector2f;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The legacy side of the workbench's fidelity gate (#300): the real {@link WorkbenchRenderCoordinator}
 * (panel, titles, 3x3 grid, arrow, output, buttons, inventory, counts, then the tooltip pass) on a
 * {@link LegacyUiRaster}, bound to a {@link WorkbenchFixtures} table. Its GL collaborators are mocks
 * that are never asked to draw anything visible (block icons, hard visual 2).
 *
 * <p>Variants: {@code empty}, {@code stocked}, and either with {@code -hover-<target>}: the pointer
 * over a slot ({@code craft4}, {@code output}, {@code mainN}, {@code hotN}) or a button
 * ({@code recipes}, {@code craftall}, {@code sort}). Must live in this package. Not a test class.
 */
public final class LegacyWorkbenchCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            WorkbenchFixtures.Table table = WorkbenchFixtures.table(WorkbenchFixtures.content(c.variant()));
            WorkbenchInputManager[] in = new WorkbenchInputManager[1];
            WorkbenchController controller = WorkbenchFixtures.controller(table, in);
            boolean craftAll = !in[0].getCraftingManager().getCraftingOutputSlot().isEmpty();
            Map<String, float[]> rects = WorkbenchFixtures.rects(w, h, craftAll);
            String hover = WorkbenchFixtures.hover(c.variant());
            float[] target = hover.isEmpty() ? null : rects.get(hover);
            if (!hover.isEmpty() && target == null) {
                throw new IllegalArgumentException("unknown workbench target " + hover);
            }
            Vector2f mouse = target == null ? new Vector2f(-100, -100)
                : new Vector2f(target[0] + target[2] / 2f, target[1] + target[3] / 2f);

            Renderer renderer = mock(Renderer.class);
            when(renderer.getSkijaBackend()).thenReturn(raster.backend());
            InputHandler input = mock(InputHandler.class);
            when(input.getMousePosition()).thenReturn(mouse);
            WorkbenchRenderCoordinator coordinator = new WorkbenchRenderCoordinator(mock(UIRenderer.class), renderer,
                input, table.inventory(), controller, in[0], in[0].getCraftingManager());
            coordinator.render(w, h);
            return new MigrationGate.Capture(raster.capture(), rects);
        }
    }
}
