package com.openmason.main.systems.menus.dialogs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModalDialogsTest {

    @Test
    void buttonWidthIsStandardUnlessLabelIsLong() {
        assertEquals(ModalDialogs.BTN_W, ModalDialogs.widthFor(40f));
        assertTrue(ModalDialogs.widthFor(300f) > ModalDialogs.BTN_W);
    }

    @Test
    void popupIdConventions() {
        assertTrue(ModalDialogs.isValidPopupId("Delete Project##deleteProject"));
        assertTrue(ModalDialogs.isValidPopupId("Return to Home Screen##home"));
        assertFalse(ModalDialogs.isValidPopupId("Delete Project?##x"));
        assertFalse(ModalDialogs.isValidPopupId("Agent request##x"));
        assertFalse(ModalDialogs.isValidPopupId("Unsaved Changes"));
        assertFalse(ModalDialogs.isValidPopupId("##only"));
    }
}
