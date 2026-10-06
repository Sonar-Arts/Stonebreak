package com.openmason.engine.ui.runtime.layout;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;

import java.util.List;

/**
 * Pointer picking over laid-out geometry, the same rects and {@link PaintOrder} painting uses
 * (#287). Topmost wins: higher overlay layers first, then later siblings over earlier ones and
 * children over parents.
 *
 * <ul>
 *   <li>collapsed ({@code display: none}) subtrees are skipped entirely;</li>
 *   <li>{@code visibility: hidden} elements are not hit, but a visible descendant still is;</li>
 *   <li>{@code picking-mode: ignore} elements let hits through to what is below, while their
 *       children stay pickable (Unity semantics);</li>
 *   <li>{@code overflow: hidden} and scroll containers clip their descendants' hit area;
 *       overlays escape the clips of their ancestors;</li>
 *   <li>disabled elements are still returned; input routing (#288) decides what that means.</li>
 * </ul>
 * Edges are inclusive, matching today's Masonry input.
 */
public final class HitTester {

    private HitTester() {
    }

    /** @return the topmost pickable element under the point, or {@code null} */
    public static UiElement pick(UiElement root, float x, float y) {
        return pick(PaintOrder.of(root), x, y);
    }

    public static UiElement pick(PaintOrder order, float x, float y) {
        List<PaintOrder.Entry> entries = order.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            UiElement hit = pick(order, entries.get(i).root(), x, y, null);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Design-time pick (#293 editor canvas): the topmost visible element under the point whose
     * own rect contains it, ignoring {@code picking-mode} (a label the game never hits is still
     * selectable), restricted to elements {@code eligible} accepts. Clipping applies as for
     * {@link #pick}. An ineligible element is see-through: its descendants can still be picked.
     */
    public static UiElement pickDesign(PaintOrder order, float x, float y, java.util.function.Predicate<UiElement> eligible) {
        List<PaintOrder.Entry> entries = order.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            UiElement hit = pickDesign(order, entries.get(i).root(), x, y, null, eligible);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static UiElement pickDesign(PaintOrder order, UiElement el, float x, float y, UiRect clip,
                                        java.util.function.Predicate<UiElement> eligible) {
        if (el.computedStyle().collapsed() || clip != null && !clip.contains(x, y)) {
            return null;
        }
        UiRect r = el.rect();
        UiRect childClip = el.clipsChildren() ? intersect(clip, r) : clip;
        List<UiElement> children = el.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            UiElement c = children.get(i);
            if (order.isLifted(c)) {
                continue;
            }
            UiElement hit = pickDesign(order, c, x, y, childClip, eligible);
            if (hit != null) {
                return hit;
            }
        }
        boolean self = !el.computedStyle().hidden() && r.contains(x, y) && eligible.test(el);
        return self ? el : null;
    }

    private static UiElement pick(PaintOrder order, UiElement el, float x, float y, UiRect clip) {
        if (el.computedStyle().collapsed() || clip != null && !clip.contains(x, y)) {
            return null;
        }
        UiRect r = el.rect();
        UiRect childClip = el.clipsChildren() ? intersect(clip, r) : clip;
        List<UiElement> children = el.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            UiElement c = children.get(i);
            if (order.isLifted(c)) {
                continue;
            }
            UiElement hit = pick(order, c, x, y, childClip);
            if (hit != null) {
                return hit;
            }
        }
        boolean self = !el.computedStyle().hidden() && !el.computedStyle().pickingIgnored() && r.contains(x, y);
        return self ? el : null;
    }

    static UiRect intersect(UiRect a, UiRect b) {
        if (a == null) {
            return b;
        }
        float x = Math.max(a.x(), b.x());
        float y = Math.max(a.y(), b.y());
        float w = Math.min(a.right(), b.right()) - x;
        float h = Math.min(a.bottom(), b.bottom()) - y;
        return new UiRect(x, y, Math.max(w, -1), Math.max(h, -1));
    }
}
