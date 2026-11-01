package com.stonebreak.openmason.assets;

import com.stonebreak.gameplay.SboGameplay;
import com.openmason.engine.format.sbo.SBOFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Data-contract tests for {@link SBORecipeSection}: the load/save conversions
 * between {@link SboGameplay.RecipeData} and the section's 3×3 working buffer
 * must be lossless without any rendering. (The section's constructor and data
 * methods are headless by design — no ImGui/GL calls.)
 */
@Tag("regression")
class SBORecipeSectionDataTest {

    @Test
    void emptySectionReturnsNullSoManifestFieldStaysAbsent() {
        SBORecipeSection section = new SBORecipeSection(() -> {});
        assertNull(section.toRecipeData());

        section.setFromRecipeData(null);
        assertNull(section.toRecipeData());
    }

    @Test
    void fullGridRoundTrips() {
        SboGameplay.ShapedRecipe recipe = new SboGameplay.ShapedRecipe(3, 3,
                List.of("a", "b", "c", "d", "", "e", "f", "g", "h"), 2);
        SBORecipeSection section = new SBORecipeSection(() -> {});
        section.setFromRecipeData(new SboGameplay.RecipeData(List.of(recipe)));

        SboGameplay.RecipeData out = section.toRecipeData();
        assertEquals(1, out.shaped().size());
        assertEquals(recipe, out.shaped().get(0));
    }

    @Test
    void subGridPatternRoundTripsThroughTheWorkingBuffer() {
        // 2x2 door-style corner: pattern is row-major width*height, and must
        // survive the trip through the section's 3x3 slot buffer.
        SboGameplay.ShapedRecipe recipe = new SboGameplay.ShapedRecipe(2, 2,
                List.of("plank", "plank", "plank", "stick"), 1);
        SBORecipeSection section = new SBORecipeSection(() -> {});
        section.setFromRecipeData(new SboGameplay.RecipeData(List.of(recipe)));

        SboGameplay.RecipeData out = section.toRecipeData();
        assertEquals(recipe, out.shaped().get(0));
    }

    @Test
    void multipleRecipesKeepTheirOrder() {
        SboGameplay.ShapedRecipe first = new SboGameplay.ShapedRecipe(1, 1, List.of("x"), 1);
        SboGameplay.ShapedRecipe second = new SboGameplay.ShapedRecipe(1, 2, List.of("y", "z"), 3);
        SBORecipeSection section = new SBORecipeSection(() -> {});
        section.setFromRecipeData(new SboGameplay.RecipeData(List.of(first, second)));

        SboGameplay.RecipeData out = section.toRecipeData();
        assertEquals(List.of(first, second), out.shaped());
    }

    @Test
    void clearHookEmptiesTheSection() {
        SBORecipeSection section = new SBORecipeSection(() -> {});
        section.setFromRecipeData(new SboGameplay.RecipeData(List.of(
                new SboGameplay.ShapedRecipe(1, 1, List.of("x"), 1))));
        section.clear(null);
        assertNull(section.toRecipeData());
    }
}
