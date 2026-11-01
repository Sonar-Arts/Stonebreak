package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;

import java.util.List;

/**
 * Directional (controller/arrow) focus search over placed rects (#288).
 *
 * <p>A candidate qualifies when it lies further along the direction than the current element:
 * its near edge is past the current element's centre and its far edge past the current far edge
 * (so a wider element stacked above is not "to the right"). Its
 * score is the gap along the direction plus twice the gap across it, where an element that
 * overlaps the current one across the direction (it is "in the beam") has no cross gap. The
 * lowest score wins; ties go to the candidate whose centre is closest across the direction, then
 * to the earlier element in tree order. There is no wrap-around:
 * at an edge, focus stays.
 */
public final class SpatialNavigator {

    private SpatialNavigator() {
    }

    /** Best candidate from {@code from} towards {@code direction} (a {@code NAVIGATE_*} action), or null. */
    public static UiElement find(UiElement from, List<UiElement> candidates, UiAction direction) {
        UiRect f = from.rect();
        UiElement best = null;
        float bestScore = Float.MAX_VALUE;
        float bestOffset = Float.MAX_VALUE;
        for (UiElement c : candidates) {
            if (c == from) {
                continue;
            }
            float score = score(f, c.rect(), direction);
            if (score == Float.MAX_VALUE) {
                continue;
            }
            float offset = centreOffset(f, c.rect(), direction);
            if (score < bestScore || score == bestScore && offset < bestOffset) {
                bestScore = score;
                bestOffset = offset;
                best = c;
            }
        }
        return best;
    }

    /**
     * Distance between the centres across the direction: breaks ties between candidates that are
     * all "in the beam", such as grid cells whose edges merely touch the current one's, so focus
     * keeps its column (row) instead of drifting to the earliest neighbour in tree order.
     */
    static float centreOffset(UiRect f, UiRect r, UiAction direction) {
        return switch (direction) {
            case NAVIGATE_UP, NAVIGATE_DOWN -> Math.abs((r.x() + r.right()) - (f.x() + f.right())) / 2f;
            default -> Math.abs((r.y() + r.bottom()) - (f.y() + f.bottom())) / 2f;
        };
    }

    /** {@link Float#MAX_VALUE} when {@code r} is not in {@code direction} from {@code f}. */
    static float score(UiRect f, UiRect r, UiAction direction) {
        float fcx = f.x() + f.width() / 2f;
        float fcy = f.y() + f.height() / 2f;
        float along;
        float across;
        switch (direction) {
            case NAVIGATE_RIGHT -> {
                if (!(r.x() > fcx && r.right() > f.right())) {
                    return Float.MAX_VALUE;
                }
                along = Math.max(0, r.x() - f.right());
                across = gap(f.y(), f.bottom(), r.y(), r.bottom());
            }
            case NAVIGATE_LEFT -> {
                if (!(r.right() < fcx && r.x() < f.x())) {
                    return Float.MAX_VALUE;
                }
                along = Math.max(0, f.x() - r.right());
                across = gap(f.y(), f.bottom(), r.y(), r.bottom());
            }
            case NAVIGATE_DOWN -> {
                if (!(r.y() > fcy && r.bottom() > f.bottom())) {
                    return Float.MAX_VALUE;
                }
                along = Math.max(0, r.y() - f.bottom());
                across = gap(f.x(), f.right(), r.x(), r.right());
            }
            case NAVIGATE_UP -> {
                if (!(r.bottom() < fcy && r.y() < f.y())) {
                    return Float.MAX_VALUE;
                }
                along = Math.max(0, f.y() - r.bottom());
                across = gap(f.x(), f.right(), r.x(), r.right());
            }
            default -> {
                return Float.MAX_VALUE;
            }
        }
        return along + 2f * across;
    }

    /** Distance between two intervals, 0 when they overlap. */
    private static float gap(float a0, float a1, float b0, float b1) {
        return Math.max(0, Math.max(b0 - a1, a0 - b1));
    }
}
