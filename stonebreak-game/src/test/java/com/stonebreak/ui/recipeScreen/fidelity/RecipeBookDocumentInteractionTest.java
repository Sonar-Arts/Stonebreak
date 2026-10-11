package com.stonebreak.ui.recipeScreen.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recipe book document's pointer input (#301) runs the book's click rules: a category press selects it,
 * a press on the search field focuses it and anywhere else unfocuses it, a recipe cell selects the recipe
 * with its variants, Prev/Next cycle them (a disabled one falls through as a plain press), and the wheel
 * scrolls the grid by whole rows, clamped.
 */
@Tag("regression")
class RecipeBookDocumentInteractionTest {

    private static SbuiArchive shipped;

    @BeforeAll
    static void load() throws Exception {
        ChatDocumentGateTest.requireLua();
        shipped = GameUiDocuments.readScreen(RecipeScreen.DOCUMENT_ID);
    }

    private static void click(DocumentStage stage, String target) {
        UiRect r = DocumentRecipeBookCapture.element(stage, target).rect();
        stage.clickAt(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
        stage.host.recipeBook().poll();
        stage.settle();
    }

    @Test
    void pressesRunTheLegacyRules() throws Exception {
        RecipeScreen screen = DocumentRecipeBookCapture.screen("empty");
        RecipeBookState s = screen.state();
        try (DocumentStage stage = DocumentRecipeBookCapture.stage(shipped, screen, 1920, 1080, 1f)) {
            click(stage, "search");
            assertTrue(s.getSearchState().isSearchActive(), "the field takes focus");

            click(stage, "cell0");
            assertFalse(s.getSearchState().isSearchActive(), "a press elsewhere unfocuses it");
            assertEquals("planks", s.getPopupState().getSelectedRecipe().getId());
            assertEquals(2, s.getPopupState().getCurrentRecipeVariations().size());
            s.getPopupState().updateAfterFrame(); // the selecting press's frame ends

            click(stage, "next");
            assertEquals(1, s.getPopupState().getCurrentVariationIndex(), "Next cycles");
            click(stage, "search");
            click(stage, "next");
            assertEquals(1, s.getPopupState().getCurrentVariationIndex(), "a disabled Next does nothing ...");
            assertFalse(s.getSearchState().isSearchActive(), "... but still unfocuses the search");
            click(stage, "prev");
            assertEquals(0, s.getPopupState().getCurrentVariationIndex(), "Prev cycles back");

            click(stage, "cat2");
            assertEquals("Tools", s.getUiState().getSelectedCategory());
            assertEquals(0, stage.view.instance().qAll(".grid-slot").size(), "no tools in the table");
            assertEquals("planks", s.getPopupState().getSelectedRecipe().getId(), "the selection stays");
            click(stage, "cat1");
            assertEquals("Building", s.getUiState().getSelectedCategory());
            assertEquals(screen.window(1920, 1080).size(), stage.view.instance().qAll(".grid-slot").size());
        }
    }

    @Test
    void theWheelScrollsWholeRowsClamped() throws Exception {
        RecipeScreen screen = DocumentRecipeBookCapture.screen("scrolled");
        RecipeBookState s = screen.state();
        try (DocumentStage stage = DocumentRecipeBookCapture.stage(shipped, screen, 640, 360, 1f)) {
            assertEquals(2, s.getUiState().getScrollOffset());
            UiRect grid = stage.q("#grid").rect();
            float x = grid.x() + 10;
            float y = grid.y() + 10;
            stage.view.input().wheel(x, y, 0, -1, 0);
            stage.settle();
            assertEquals(3, s.getUiState().getScrollOffset(), "down one row");
            stage.view.input().wheel(x, y, 0, 10, 0);
            stage.settle();
            assertEquals(0, s.getUiState().getScrollOffset(), "clamped at the top");
            stage.host.recipeBook().poll();
            stage.settle();
            assertEquals("cell:0", ((com.openmason.engine.format.omui.UiValue.Obj) stage.view.instance()
                .qAll(".grid-slot").getFirst().prop("stack")).get("slot") instanceof
                com.openmason.engine.format.omui.UiValue.Str str ? str.value() : null);
        }
    }

    @Test
    void actionsAreRefusedWhileTheBookIsClosed() throws Exception {
        RecipeScreen screen = DocumentRecipeBookCapture.screen("empty");
        try (DocumentStage stage = DocumentRecipeBookCapture.stage(shipped, screen, 1920, 1080, 1f)) {
            screen.state().hide();
            click(stage, "cat4");
            assertEquals("All", screen.state().getUiState().getSelectedCategory());
            assertNull(screen.state().getPopupState().getSelectedRecipe());
        }
    }
}
