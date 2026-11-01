package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

/**
 * One incremental edit of a collection (#289), in the order it was applied: a list view
 * replays these instead of rebuilding its rows, so row state (focus, scroll, an open tooltip)
 * survives unrelated changes. Indices refer to the list as it was just before the change.
 */
public sealed interface ListChange {

    record Inserted(int index, UiValue item) implements ListChange {
    }

    record Removed(int index, String id) implements ListChange {
    }

    /** The item at {@code index} kept its identity but its content changed. */
    record Updated(int index, UiValue item) implements ListChange {
    }

    record Moved(int from, int to) implements ListChange {
    }

    /** Too much changed to describe incrementally; re-read the whole list. */
    record Reset() implements ListChange {
    }

    ListChange RESET = new Reset();
}
