package com.openmason.main.systems.uiEditor.view;

import com.openmason.main.systems.uiEditor.canvas.CanvasTransform;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-document editor view state: canvas transform, frame size and scales, and the
 * editor-only element flags (hidden in the designer, locked against canvas picking, expanded in
 * the hierarchy). None of it is source; it is stamped into {@code editor/workspace.json} on save
 * and restored on open.
 */
public final class DocumentViewState {

    public final CanvasTransform transform = new CanvasTransform();
    public int frameWidth = 1920;
    public int frameHeight = 1080;
    public float uiScale = 1f;
    public float pixelRatio = 1f;
    /** Fit the frame into the canvas on the next frame (new tab, resolution change). */
    public boolean fitPending = true;
    /** Keep refitting when the canvas resizes until the author pans or zooms (docking settles late). */
    public boolean autoFit = true;
    public final Set<String> hidden = new HashSet<>();
    public final Set<String> locked = new HashSet<>();
    public final Set<String> collapsed = new HashSet<>();
    /** Internal component structure shown in the hierarchy for these instance keys. */
    public final Set<String> showInternals = new HashSet<>();
    /** The Timeline's display state (#295): open clip, zoom, scroll, snap; {@code editor/timeline.json}. */
    public com.openmason.main.systems.uiEditor.timeline.TimelineViewState timeline =
        new com.openmason.main.systems.uiEditor.timeline.TimelineViewState();

    /** Device pixels per logical pixel. */
    public float scale() {
        return uiScale * pixelRatio;
    }
}
