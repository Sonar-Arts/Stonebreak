package com.stonebreak.ui.chest;

import com.stonebreak.ui.runtime.screens.ContainerDocument;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;

/**
 * The chest screen as the shipped UI document {@code ui/documents/chest.sbui}: authored in Open
 * Mason from the furnace document, bound to the {@code stonebreak:chest} / {@code stonebreak:inventory}
 * / {@code stonebreak:hotbar} contracts, its slots' presses mapped to the
 * {@code stonebreak:inventory.slot-*} actions, which run {@link ChestInputManager}'s rules at the
 * addressed slot. No slot rule lives in the document.
 */
public final class ChestDocument extends ContainerDocument implements ChestScreen.Presentation {

    public static final String ID = "chest";

    public ChestDocument(DocumentScreenHost host) {
        super(ID, host);
    }
}
