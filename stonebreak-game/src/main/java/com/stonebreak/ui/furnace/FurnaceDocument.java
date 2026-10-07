package com.stonebreak.ui.furnace;

import com.openmason.engine.ui.runtime.input.InputSettings;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;

/**
 * The furnace screen as the shipped UI document {@code ui/documents/furnace.sbui} (#298): authored
 * in Open Mason, bound to the {@code stonebreak:furnace} / {@code stonebreak:inventory} /
 * {@code stonebreak:hotbar} contracts, its slots' pointer input mapped by Lua code-behind to the
 * {@code stonebreak:inventory.slot-*} actions, which run the legacy {@code FurnaceInputManager}
 * rules at the addressed slot. No slot rule lives in the document.
 *
 * <p>{@link FurnaceScreen} keeps the lifecycle: right-clicking the block, Escape, the FURNACE_UI
 * state, binding to the furnace's state and closing (carried stack put back) stay where they were;
 * it opens this presentation when it opens and closes it when it closes. The screen is
 * {@code ownerPaints}: {@link #paint} draws it where the legacy screen drew (after the HUD, before
 * the overlays), panel, slots, tooltip and carried stack in one paint, in that order.
 *
 * <p>Falls back to the legacy screen whenever the document is not showing: not shipped, rolled back
 * ({@code -Dstonebreak.ui.legacy=furnace}), refused by a gate, or closed after a failing frame.
 */
public final class FurnaceDocument extends PresentedDocument implements FurnaceScreen.Presentation {

    public static final String ID = "furnace";

    /** The legacy furnace showed a slot's tooltip as soon as the pointer was over it. */
    public static final InputSettings SETTINGS = InputSettings.DEFAULTS.withTooltipDelay(0);

    public FurnaceDocument(DocumentScreenHost host) {
        super(ID, host, DocumentScreen.Options.screen());
    }

    @Override
    protected void opened(DocumentScreen screen) {
        screen.view().input().setSettings(SETTINGS);
    }
}
