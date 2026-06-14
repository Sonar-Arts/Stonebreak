package com.openmason.main.systems.mortar.core;

/**
 * Input outcome for one {@link MortarRegion#render()} call. Ids refer to the
 * part under the mouse this frame; any field may be {@code null} when nothing
 * applies. Click/double-click/right-click are computed against the region's
 * single ImGui item, so only one part can report each per frame.
 */
public final class MortarFrameResult {

    public static final MortarFrameResult NONE = new MortarFrameResult(null, null, null, null);

    private final String hoveredId;
    private final String clickedId;
    private final String doubleClickedId;
    private final String rightClickedId;

    public MortarFrameResult(String hoveredId, String clickedId,
                             String doubleClickedId, String rightClickedId) {
        this.hoveredId = hoveredId;
        this.clickedId = clickedId;
        this.doubleClickedId = doubleClickedId;
        this.rightClickedId = rightClickedId;
    }

    /** Part under the mouse, or null. */
    public String hovered() {
        return hoveredId;
    }

    /** Part left-pressed this frame, or null. */
    public String clicked() {
        return clickedId;
    }

    /** Part left-double-clicked this frame, or null. */
    public String doubleClicked() {
        return doubleClickedId;
    }

    /** Part right-clicked this frame, or null. */
    public String rightClicked() {
        return rightClickedId;
    }

    public boolean isClicked(String id) {
        return id != null && id.equals(clickedId);
    }

    public boolean isDoubleClicked(String id) {
        return id != null && id.equals(doubleClickedId);
    }

    public boolean isRightClicked(String id) {
        return id != null && id.equals(rightClickedId);
    }
}
