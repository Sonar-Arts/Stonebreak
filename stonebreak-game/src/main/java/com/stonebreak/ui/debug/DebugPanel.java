package com.stonebreak.ui.debug;

import com.stonebreak.rendering.UI.masonryUI.MStatPanel;

/**
 * One card of the F3 debug overlay. Implementations gather their numbers and
 * return a freshly built {@link MStatPanel}; the overlay decides how often to
 * rebuild each card and where to draw it.
 */
public interface DebugPanel {

    /** Gathers current values and builds the card. */
    MStatPanel build();
}
