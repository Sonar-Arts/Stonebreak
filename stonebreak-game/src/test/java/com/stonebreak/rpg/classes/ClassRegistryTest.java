package com.stonebreak.rpg.classes;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The static class catalog every class-select UI and save file leans on. Ids must be unique and
 * stable (they are what saves record), every class needs castable content, and every icon path
 * that is declared must actually resolve on the game classpath — a typo'd path renders as a
 * missing icon in the class-select screen and nothing else reports it.
 */
class ClassRegistryTest {

    @Test
    void theCatalogIsNonEmptyWithUniqueStableIds() {
        assertFalse(ClassRegistry.ALL.isEmpty());

        Set<String> ids = new HashSet<>();
        for (PlayerClassDefinition def : ClassRegistry.ALL) {
            assertNotNull(def.id());
            assertFalse(def.id().isBlank());
            assertTrue(ids.add(def.id()), "duplicate class id: " + def.id());
            assertFalse(def.name().isBlank(), def.id() + " needs a display name");
            assertFalse(def.description().isBlank(), def.id() + " needs a description");
        }
    }

    @Test
    void lookupFindsEveryCatalogEntryAndNothingElse() {
        for (PlayerClassDefinition def : ClassRegistry.ALL) {
            assertTrue(ClassRegistry.findById(def.id()).isPresent());
        }
        assertTrue(ClassRegistry.findById("no_such_class").isEmpty());
    }

    @Test
    void everyClassHasAbilitiesWorthUnlocking() {
        for (PlayerClassDefinition def : ClassRegistry.ALL) {
            assertFalse(def.abilities().isEmpty(), def.id() + " has no abilities");
            for (ClassAbility ability : def.abilities()) {
                assertFalse(ability.name().isBlank(),
                        def.id() + " has an unnamed ability");
                assertTrue(ability.cpCost() > 0,
                        def.id() + "/" + ability.name() + " must cost class points, was " + ability.cpCost());
            }
        }
    }

    @Test
    void everyDeclaredIconResolvesOnTheGameClasspath() {
        for (PlayerClassDefinition def : ClassRegistry.ALL) {
            assertResourceExists(def.iconPath(), def.id() + " class icon");
            for (ClassAbility ability : def.abilities()) {
                assertResourceExists(ability.iconPath(), def.id() + "/" + ability.name() + " icon");
            }
        }
    }

    private static void assertResourceExists(String path, String what) {
        if (path == null) {
            return; // no icon yet is allowed; a broken path is not
        }
        try (InputStream in = ClassRegistryTest.class.getResourceAsStream(path)) {
            assertNotNull(in, what + " points at a missing resource: " + path);
        } catch (Exception e) {
            throw new AssertionError(what + " could not be opened: " + path, e);
        }
    }
}
