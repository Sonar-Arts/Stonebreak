package com.stonebreak.ui.furnace;

import com.openmason.engine.ui.runtime.input.InputSettings;
import com.stonebreak.ui.runtime.screens.ContainerDocument;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;

/**
 * The furnace screen as the shipped UI document {@code ui/documents/furnace.sbui} (#298): authored
 * in Open Mason, bound to the {@code stonebreak:furnace} / {@code stonebreak:inventory} /
 * {@code stonebreak:hotbar} contracts, its slots' pointer input mapped by Lua code-behind to the
 * {@code stonebreak:inventory.slot-*} actions, which run the {@code FurnaceInputManager} rules at
 * the addressed slot. No slot rule lives in the document.
 *
 * <p>{@link FurnaceScreen} keeps the lifecycle: right-clicking the block, Escape, the FURNACE_UI
 * state, binding to the furnace's state and closing (carried stack put back) stay where they were;
 * it opens this presentation when it opens and closes it when it closes. The screen is
 * {@code ownerPaints}: {@link #paint} draws it after the HUD, before the overlays, panel, slots,
 * tooltip and carried stack in one paint, in that order.
 */
public final class FurnaceDocument extends ContainerDocument implements FurnaceScreen.Presentation {

    public static final String ID = "furnace";

    /** A slot's tooltip shows as soon as the pointer is over it. */
    public static final InputSettings SETTINGS = ContainerDocument.SETTINGS;

    public FurnaceDocument(DocumentScreenHost host) {
        super(ID, host);
    }
}
