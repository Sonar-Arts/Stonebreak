package com.openmason.api.viewport;

/**
 * Non-model content the Model Editor viewport can show (a game block, an item sprite...). The
 * editable model always draws itself; otherwise the viewport draws the first provider, in
 * registration order, that is {@link #ready()}.
 */
public interface ViewportContentProvider {

    /** Unique id. */
    String id();

    /** Human-readable name. */
    String label();

    /** True when this provider has something selected to draw this frame. */
    boolean ready();

    /** Draws the content with the frame's matrices; runs on the GL thread inside the viewport pass. */
    void render(ViewportContentFrame frame);
}
