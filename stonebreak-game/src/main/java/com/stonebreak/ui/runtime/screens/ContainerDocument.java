package com.stonebreak.ui.runtime.screens;

import com.openmason.engine.ui.runtime.input.InputSettings;

/**
 * A container screen (furnace, crafting table, inventory) shown as its shipped document (#298,
 * #300): bound to the {@code stonebreak:inventory}/{@code stonebreak:hotbar} contracts (and its own),
 * every slot press mapped by Lua code-behind to the {@code stonebreak:inventory.slot-*} actions,
 * which run the legacy screen's slot rules at the addressed slot. The legacy screen keeps the
 * lifecycle and paints it where it drew ({@code ownerPaints}): panel, slots, tooltip and the carried
 * stack in one paint, in that order.
 */
public class ContainerDocument extends PresentedDocument {

    /** The legacy container screens showed a slot's tooltip as soon as the pointer was over it. */
    public static final InputSettings SETTINGS = InputSettings.DEFAULTS.withTooltipDelay(0);

    public ContainerDocument(String id, DocumentScreenHost host) {
        super(id, host, DocumentScreen.Options.screen());
    }

    @Override
    protected void opened(DocumentScreen screen) {
        screen.view().input().setSettings(SETTINGS);
    }
}
