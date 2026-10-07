package com.stonebreak.ui.runtime.screens;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-screen rollback switch of the migrations. */
class DocumentScreenPolicyTest {

    private static final Set<String> SHIPPED = Set.of("pause", "furnace");

    @Test
    void aScreenIsADocumentOnlyWhenItsExportShips() {
        DocumentScreenPolicy p = new DocumentScreenPolicy(null, SHIPPED::contains);
        assertTrue(p.useDocument("pause"));
        assertFalse(p.useDocument("inventory"), "not migrated yet: legacy by default");
        assertFalse(p.useDocument(""));
        assertFalse(p.useDocument(null));
    }

    @Test
    void theSystemPropertyRollsScreensBack() {
        DocumentScreenPolicy some = new DocumentScreenPolicy(" pause , x", SHIPPED::contains);
        assertFalse(some.useDocument("pause"));
        assertTrue(some.useDocument("furnace"));
        DocumentScreenPolicy all = new DocumentScreenPolicy("all", SHIPPED::contains);
        assertFalse(all.useDocument("pause"));
        assertFalse(all.useDocument("furnace"));
    }

    @Test
    void aRuntimeOverrideWinsButNeverInventsADocument() {
        DocumentScreenPolicy p = new DocumentScreenPolicy("pause", SHIPPED::contains);
        p.setOverride("pause", false);
        assertTrue(p.useDocument("pause"), "forced back to the document");
        p.setOverride("furnace", true);
        assertFalse(p.useDocument("furnace"), "forced to legacy");
        p.setOverride("inventory", false);
        assertFalse(p.useDocument("inventory"), "nothing ships for it");
        p.setOverride("pause", null);
        assertFalse(p.useDocument("pause"), "default again: the property's rollback");
    }

    @Test
    void shippedScreensLiveUnderUiDocuments() {
        assertEquals("ui/documents/pause.sbui", DocumentScreenPolicy.resourcePath("pause"));
    }
}
