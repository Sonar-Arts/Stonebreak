package com.stonebreak.ui.recipeScreen.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.input.InputHandler;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.logic.RecipeSearchService;
import com.stonebreak.ui.recipeScreen.renderers.RecipeRenderCoordinator;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;
import org.joml.Vector2f;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The legacy side of the recipe book's gate (#301): the real {@link RecipeRenderCoordinator} (chrome, the
 * count pass, then the tooltip pass) on a {@link LegacyUiRaster}. Its GL collaborators are mocks: with no
 * block texture array it skips the GL icon pass. Not a test class.
 */
public final class LegacyRecipeBookCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            RecipeBookState state = RecipeBookFixtures.state(c.variant());
            Map<String, float[]> rects = RecipeBookFixtures.rects(w, h, state);
            String hover = RecipeBookFixtures.hover(c.variant());
            float[] t = hover.isEmpty() ? null : rects.get(hover);
            if (!hover.isEmpty() && t == null) {
                throw new IllegalArgumentException("unknown recipe book target " + hover);
            }
            Vector2f mouse = t == null ? new Vector2f(-100, -100) : new Vector2f(t[0] + t[2] / 2f, t[1] + t[3] / 2f);
            Renderer renderer = mock(Renderer.class);
            when(renderer.getSkijaBackend()).thenReturn(raster.backend());
            InputHandler input = mock(InputHandler.class);
            when(input.getMousePosition()).thenReturn(mouse);
            RecipeRenderCoordinator coordinator = new RecipeRenderCoordinator(state,
                new RecipeFilterService(new RecipeSearchService()), mock(UIRenderer.class), renderer, input);
            coordinator.renderWithoutTooltips(w, h);
            coordinator.renderTooltipsOnly(w, h);
            return new MigrationGate.Capture(raster.capture(), rects);
        }
    }
}
