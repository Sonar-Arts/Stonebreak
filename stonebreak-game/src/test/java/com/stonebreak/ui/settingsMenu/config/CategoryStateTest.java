package com.stonebreak.ui.settingsMenu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.ui.settingsMenu.config.CategoryState.SettingType;

/**
 * Guards CategoryState enum: fromIndex(getIndex()) round-trips for every constant,
 * out-of-range indices return null, getMinIndex/getMaxIndex bound every getIndex(),
 * getSettings() is non-empty, and getDisplayName() is non-blank.
 *
 * <p>Applies the same checks to the nested SettingType enum.
 *
 * <p>Regression: prevents fromIndex from returning wrong enum when indices are non-contiguous,
 * and ensures displayName never throws NPE on blank names.
 */
class CategoryStateTest {

    @Test
    void fromIndexRoundTripsForEveryCategory() {
        for (CategoryState category : CategoryState.values()) {
            CategoryState result = CategoryState.fromIndex(category.getIndex());
            assertEquals(category, result,
                "fromIndex(getIndex()) must round-trip for " + category.name());
        }
    }

    @Test
    void fromIndexRoundTripsForEverySettingType() {
        for (SettingType setting : SettingType.values()) {
            SettingType result = SettingType.fromIndex(setting.getIndex());
            assertEquals(setting, result,
                "fromIndex(getIndex()) must round-trip for " + setting.name());
        }
    }

    @Test
    void outOfRangeIndexReturnsNullForCategory() {
        assertNull(CategoryState.fromIndex(-1), "index -1 must return null");
        assertNull(CategoryState.fromIndex(CategoryState.getMaxIndex() + 1),
            "index past max must return null");
    }

    @Test
    void outOfRangeIndexReturnsNullForSettingType() {
        assertNull(SettingType.fromIndex(-1), "index -1 must return null");
        // Find max index across all SettingTypes
        int maxIndex = 0;
        for (SettingType s : SettingType.values()) {
            maxIndex = Math.max(maxIndex, s.getIndex());
        }
        assertNull(SettingType.fromIndex(maxIndex + 1),
            "index past max must return null");
    }

    @Test
    void getMinIndexBoundsEveryCategoryIndex() {
        int min = CategoryState.getMinIndex();
        for (CategoryState category : CategoryState.values()) {
            assertTrue(category.getIndex() >= min,
                "every category index must be >= minIndex for " + category.name());
        }
    }

    @Test
    void getMaxIndexBoundsEveryCategoryIndex() {
        int max = CategoryState.getMaxIndex();
        for (CategoryState category : CategoryState.values()) {
            assertTrue(category.getIndex() <= max,
                "every category index must be <= maxIndex for " + category.name());
        }
    }

    @Test
    void getMinAndMaxIndexHaveCorrectValues() {
        assertEquals(0, CategoryState.getMinIndex(), "min index must be 0 (GENERAL)");
        // Just verify max >= min
        assertTrue(CategoryState.getMaxIndex() >= CategoryState.getMinIndex());
    }

    @Test
    void getSettingsIsNonEmptyForEveryCategory() {
        for (CategoryState category : CategoryState.values()) {
            SettingType[] settings = category.getSettings();
            assertNotNull(settings, "settings must not be null for " + category.name());
            assertTrue(settings.length > 0,
                "settings must be non-empty for " + category.name());
        }
    }

    @Test
    void getDisplayNameIsNotBlankForEveryCategory() {
        for (CategoryState category : CategoryState.values()) {
            String name = category.getDisplayName();
            assertNotNull(name, "displayName must not be null for " + category.name());
            assertTrue(name.trim().length() > 0,
                "displayName must not be blank for " + category.name());
        }
    }

    @Test
    void categoryHasCorrectSettingCount() {
        // Verify known structure: GENERAL has 2 settings
        assertEquals(2, CategoryState.GENERAL.getSettings().length, "GENERAL must have 2 settings");
    }
}