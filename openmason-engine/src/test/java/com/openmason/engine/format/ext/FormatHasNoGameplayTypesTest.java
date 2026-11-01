package com.openmason.engine.format.ext;

import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbo.SBOFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #383 acceptance: the SBO/SBE formats carry no game rule types; game data lives in namespaced
 * extensions owned by the game ({@code stonebreak:gameplay} → {@code com.stonebreak.gameplay}).
 */
@Tag("regression")
class FormatHasNoGameplayTypesTest {

    private static final Set<String> GAMEPLAY = Set.of("GameProperties", "RecipeData", "ShapedRecipe",
            "SmeltingRecipeData", "SmeltingRecipeEntry", "FuelData", "DropData", "DropEntry", "ToolDropOverride",
            "ToolData", "WeaponData", "EntityType");

    @Test
    void theFormatsDeclareNoGameplayTypes() {
        List<String> found = Arrays.stream(new Class<?>[]{SBOFormat.class, SBEFormat.class})
                .flatMap(c -> Arrays.stream(c.getDeclaredClasses()))
                .map(Class::getSimpleName)
                .filter(GAMEPLAY::contains)
                .toList();
        assertEquals(List.of(), found);
    }

    @Test
    void theDocumentsHaveNoGameplayComponents() {
        Set<String> gameplayFields = Set.of("gameProperties", "recipes", "smeltingRecipes", "fuel", "drops",
                "tool", "weapon");
        List<String> found = Arrays.stream(SBOFormat.Document.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .filter(gameplayFields::contains)
                .toList();
        assertEquals(List.of(), found);
    }
}
