package com.stonebreak.ui.crosshair;

import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.PresentedDocument;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

/**
 * The crosshair's presentation (#301): the shipped {@code ui/documents/crosshair.sbui} while it is
 * visible. The frame renderer decides visibility every frame (PLAYING with no container screen open)
 * and paints it at the crosshair's place in the HUD's composite order. The document reads the applied
 * {@link CrosshairSettings} from {@code stonebreak:crosshair}.
 */
public final class Crosshair {

    /** The shipped document: {@code ui/documents/crosshair.sbui}. */
    public static final String DOCUMENT_ID = "crosshair";

    private final PresentationSlot slot = new PresentationSlot();
    private boolean installed;

    /** Installs (or, with null, removes) the presentation; tests install their own before the first sync. */
    public void setPresentation(ScreenPresentation p) {
        installed = true;
        slot.install(p);
    }

    /** Main thread, every frame: shows or hides the presentation on real changes only. */
    public void sync(boolean visible) {
        if (visible && !installed) { // the document host is first used on the first visible frame
            setPresentation(new PresentedDocument(DOCUMENT_ID, DocumentScreenHost.get(), DocumentScreen.Options.hud()));
        }
        slot.setVisible(visible);
    }

    /** @return true when the document painted the crosshair */
    public boolean paint(int windowWidth, int windowHeight) {
        return slot.paint(windowWidth, windowHeight);
    }

    /** True while the document shows the crosshair. */
    public boolean showing() {
        return slot.showing();
    }
}
