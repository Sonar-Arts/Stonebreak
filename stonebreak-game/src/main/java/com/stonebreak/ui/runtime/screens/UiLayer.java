package com.stonebreak.ui.runtime.screens;

/**
 * Where an open UI document sits in the game window's stack, bottom to top. Rendering and input
 * use one ordered list ({@code GameUiInput.views()}): a document is drawn after, and offered input
 * before, every document in a lower layer; inside a layer, the newer one is on top.
 *
 * <p>Within one document the {@code -sb-layer} style keeps lifting overlays, tooltips and the
 * cursor element above its own content; these layers order whole documents (the inventory
 * screen below the hotbar HUD below a toast, the dev overlay above all of them).
 */
public enum UiLayer {
    /** Full screens and in-world panels (pause, furnace, inventory). */
    SCREEN,
    /** Always-on gameplay HUD (hotbar, vitals) drawn over open screens. */
    HUD,
    /** Toasts, confirmations and developer overlays above every screen and HUD. */
    OVERLAY,
    /** Document-level tooltips shared across screens. */
    TOOLTIP,
    /** What follows the pointer (the carried item stack). */
    CURSOR
}
