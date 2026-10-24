package com.stonebreak.ui.saveChanges;

import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

/**
 * Modal "Save changes?" confirmation shown when the player tries to leave the
 * inventory/character sheet while holding unspent point allocations.
 *
 * <p>Yes commits the allocations; No discards them (reverting to the state the
 * screen opened with); Escape is No. Visibility
 * stays here; it shows as the shipped UI document {@value #DOCUMENT_ID}
 * ({@link #setPresentation}), whose buttons run {@code stonebreak:screen.save-changes.yes/no}.
 */
public class SaveChangesDialog {

    /** The shipped document's screen id ({@code ui/documents/save_changes.sbui}). */
    public static final String DOCUMENT_ID = "save_changes";

    private final PresentationSlot presentation = new PresentationSlot();

    /** Paints the dialog's presentation. */
    public void render(int windowWidth, int windowHeight) {
        presentation.paint(windowWidth, windowHeight);
    }

    public boolean isVisible() {
        return presentation.isVisible();
    }

    public void setVisible(boolean visible) {
        presentation.setVisible(visible);
    }

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
    }
}
