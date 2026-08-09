package com.stonebreak.ui.recipeScreen.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.ItemStack;

/**
 * Guards the state management of the recipe book: scroll clamping, search text
 * editing, popup navigation bounds, and the composed {@link RecipeBookState}.
 *
 * <p>Regression: a change to {@code adjustScrollOffset} that allows negative scroll,
 * or a popup navigation that lets the index go past the variation list length,
 * would crash the recipe book UI at runtime.
 */
class RecipeBookStateTest {

    private UIState uiState;
    private SearchState searchState;
    private PopupState popupState;
    private RecipeBookState bookState;

    @BeforeEach
    void setUp() {
        uiState = new UIState();
        searchState = new SearchState();
        popupState = new PopupState();
        bookState = new RecipeBookState();
    }

    // ---- UIState scroll clamping --------------------------------------------------------------

    @Test
    void adjustScrollOffsetClampsAtZero() {
        uiState.setScrollOffset(0);
        uiState.adjustScrollOffset(-5);
        assertEquals(0, uiState.getScrollOffset(),
            "adjustScrollOffset by -5 from 0 must clamp at 0, not go negative");
    }

    @Test
    void adjustScrollOffsetIncreasesCorrectly() {
        uiState.setScrollOffset(0);
        uiState.adjustScrollOffset(3);
        assertEquals(3, uiState.getScrollOffset(),
            "adjustScrollOffset by +3 from 0 must yield 3");
    }

    @Test
    void limitScrollOffsetClampsWhenOver() {
        uiState.setScrollOffset(50);
        uiState.limitScrollOffset(30);
        assertEquals(30, uiState.getScrollOffset(),
            "limitScrollOffset(30) when at 50 must clamp to 30");
    }

    @Test
    void limitScrollOffsetDoesNothingWhenUnder() {
        uiState.setScrollOffset(10);
        uiState.limitScrollOffset(30);
        assertEquals(10, uiState.getScrollOffset(),
            "limitScrollOffset(30) when at 10 must leave scroll unchanged");
    }

    @Test
    void resetToDefaultsResetsScrollAndCategory() {
        uiState.setScrollOffset(42);
        uiState.setSelectedCategory("Tools");
        uiState.resetToDefaults();

        assertEquals(0, uiState.getScrollOffset(),
            "resetToDefaults must set scrollOffset to 0");
        assertEquals("All", uiState.getSelectedCategory(),
            "resetToDefaults must set category to \"All\"");
    }

    // ---- SearchState text editing -------------------------------------------------------------

    @Test
    void addCharacterThenRemoveLastCharacterRoundTrips() {
        searchState.addCharacter('H');
        searchState.addCharacter('e');
        searchState.addCharacter('l');
        searchState.addCharacter('l');
        searchState.addCharacter('o');
        assertEquals("Hello", searchState.getSearchText(),
            "search text must be \"Hello\" after 5 addCharacter calls");

        searchState.removeLastCharacter();
        searchState.removeLastCharacter();
        searchState.removeLastCharacter();
        searchState.removeLastCharacter();
        searchState.removeLastCharacter();
        assertEquals("", searchState.getSearchText(),
            "removing 5 characters from 5-character text must yield empty");
    }

    @Test
    void removeLastCharacterOnEmptyIsSafe() {
        // Must not throw
        searchState.removeLastCharacter();
        assertEquals("", searchState.getSearchText(),
            "removeLastCharacter on empty search text must be safe");
    }

    @Test
    void removeLastCharacterMoreThanAddedIsSafe() {
        searchState.addCharacter('A');
        searchState.removeLastCharacter();
        searchState.removeLastCharacter();
        searchState.removeLastCharacter();
        assertEquals("", searchState.getSearchText(),
            "removing more characters than added must leave text empty, not throw");
    }

    @Test
    void clearSearchResetsEverything() {
        searchState.addCharacter('X');
        searchState.setSearchActive(true);
        searchState.setTyping(true);
        searchState.clearSearch();

        assertEquals("", searchState.getSearchText());
        assertFalse(searchState.isSearchActive(),
            "clearSearch must deactivate search");
        assertFalse(searchState.isTyping(),
            "clearSearch must stop typing flag");
    }

    // ---- PopupState navigation ----------------------------------------------------------------

    private Recipe makeRecipe(String id) {
        return new Recipe(id,
            Arrays.asList(Arrays.asList(new ItemStack(BlockType.DIRT, 1))),
            new ItemStack(BlockType.STONE, 1));
    }

    @Test
    void openPopupSetsShowingFlagAndFindsIndex() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        List<Recipe> variations = Arrays.asList(r1, r2);

        popupState.openPopup(r2, variations);

        assertTrue(popupState.isShowingRecipePopup(),
            "openPopup must set showingRecipePopup to true");
        assertEquals(1, popupState.getCurrentVariationIndex(),
            "openPopup must find index 1 for r2 in [r1, r2]");
        assertEquals(r2, popupState.getSelectedRecipe());
    }

    @Test
    void navigateNextAdvancesIndex() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        Recipe r3 = makeRecipe("r3");
        List<Recipe> variations = Arrays.asList(r1, r2, r3);

        popupState.openPopup(r1, variations);
        assertEquals(0, popupState.getCurrentVariationIndex());

        popupState.navigateNext();
        assertEquals(1, popupState.getCurrentVariationIndex(),
            "navigateNext from index 0 must advance to 1");
        popupState.navigateNext();
        assertEquals(2, popupState.getCurrentVariationIndex());
    }

    @Test
    void canNavigateNextIsFalseAtEnd() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        List<Recipe> variations = Arrays.asList(r1, r2);

        popupState.openPopup(r2, variations); // index 1 (last)
        assertFalse(popupState.canNavigateNext(),
            "canNavigateNext must be false when at last variation");
    }

    @Test
    void navigateNextAtEndDoesNothing() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        List<Recipe> variations = Arrays.asList(r1, r2);

        popupState.openPopup(r2, variations);
        popupState.navigateNext(); // should be no-op
        assertEquals(1, popupState.getCurrentVariationIndex(),
            "navigateNext at last index must not change the index");
    }

    @Test
    void navigatePreviousAdvancesBackward() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        Recipe r3 = makeRecipe("r3");
        List<Recipe> variations = Arrays.asList(r1, r2, r3);

        popupState.openPopup(r3, variations); // index 2
        popupState.navigatePrevious();
        assertEquals(1, popupState.getCurrentVariationIndex(),
            "navigatePrevious from index 2 must go to 1");
    }

    @Test
    void canNavigatePreviousIsFalseAtStart() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        List<Recipe> variations = Arrays.asList(r1, r2);

        popupState.openPopup(r1, variations); // index 0
        assertFalse(popupState.canNavigatePrevious(),
            "canNavigatePrevious must be false when at first variation");
    }

    @Test
    void navigatePreviousAtStartDoesNothing() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        List<Recipe> variations = Arrays.asList(r1, r2);

        popupState.openPopup(r1, variations);
        popupState.navigatePrevious(); // should be no-op
        assertEquals(0, popupState.getCurrentVariationIndex(),
            "navigatePrevious at first index must not change the index");
    }

    @Test
    void hasMultipleVariationsIsFalseForSingleVariation() {
        Recipe r1 = makeRecipe("r1");
        popupState.openPopup(r1, Collections.singletonList(r1));

        assertFalse(popupState.hasMultipleVariations(),
            "single-variation popup must report hasMultipleVariations = false");
        assertFalse(popupState.canNavigateNext());
        assertFalse(popupState.canNavigatePrevious());
    }

    @Test
    void hasMultipleVariationsIsTrueForMultipleVariations() {
        Recipe r1 = makeRecipe("r1");
        Recipe r2 = makeRecipe("r2");
        popupState.openPopup(r1, Arrays.asList(r1, r2));

        assertTrue(popupState.hasMultipleVariations(),
            "two-variation popup must report hasMultipleVariations = true");
    }

    @Test
    void closePopupResetsAllState() {
        Recipe r1 = makeRecipe("r1");
        List<Recipe> variations = Collections.singletonList(r1);
        popupState.openPopup(r1, variations);

        popupState.closePopup();

        assertFalse(popupState.isShowingRecipePopup(),
            "closePopup must hide the popup");
        assertNull(popupState.getSelectedRecipe(),
            "closePopup must clear selectedRecipe");
        assertFalse(popupState.isPopupJustOpened(),
            "closePopup must clear popupJustOpened flag");
        assertEquals(0, popupState.getCurrentVariationIndex(),
            "closePopup must reset variation index to 0");
        assertTrue(popupState.getCurrentRecipeVariations().isEmpty(),
            "closePopup must clear variations list");
    }

    // ---- RecipeBookState composition ----------------------------------------------------------

    @Test
    void showMakesVisibleAndInitializes() {
        bookState.show();
        assertTrue(bookState.isVisible(),
            "show must make the recipe book visible");
    }

    @Test
    void hideMakesInvisible() {
        bookState.show();
        bookState.hide();
        assertFalse(bookState.isVisible(),
            "hide must make the recipe book invisible");
    }

    @Test
    void getRecipesReturnsEmptyListInitially() {
        assertTrue(bookState.getRecipes().isEmpty(),
            "new RecipeBookState must have an empty recipes list");
    }
}