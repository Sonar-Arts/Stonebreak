package com.stonebreak.ui.saveChanges;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;

/**
 * Modal "Save changes?" confirmation shown when the player tries to leave the
 * inventory/character sheet while holding unspent point allocations.
 *
 * <p>Yes commits the allocations; No discards them (reverting to the state the
 * screen opened with); pressing nothing and pressing Escape dismisses the
 * dialog and stays in the panel. Input is routed by the input layer; this class
 * only owns visibility, hover state and hit-tests, mirroring {@code PauseMenu}.
 */
public class SaveChangesDialog {

    private final SkijaSaveChangesDialogRenderer skijaRenderer;

    private boolean visible = false;
    private boolean yesHovered = false;
    private boolean noHovered = false;

    public SaveChangesDialog(SkijaUIBackend skijaBackend) {
        this.skijaRenderer = new SkijaSaveChangesDialogRenderer(skijaBackend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        skijaRenderer.render(windowWidth, windowHeight, yesHovered, noHovered);
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            yesHovered = false;
            noHovered = false;
        }
    }

    public boolean isYesButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && skijaRenderer.hitYes(mouseX, mouseY, windowWidth, windowHeight);
    }

    public boolean isNoButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && skijaRenderer.hitNo(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) {
            yesHovered = false;
            noHovered = false;
            return;
        }
        yesHovered = isYesButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
        noHovered  = isNoButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }
}
