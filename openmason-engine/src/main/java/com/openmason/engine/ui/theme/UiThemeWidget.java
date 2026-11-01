package com.openmason.engine.ui.theme;

import com.openmason.engine.format.omui.UiWidgets;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import io.github.humbleui.skija.Canvas;

/**
 * A widget a game adds to the runtime (#384): its namespaced descriptor, how it paints, its
 * accessibility role and whether its {@code provider} prop names a host draw provider the
 * painter prepares and draws (an item slot). Legacy bare names are format aliases
 * ({@link com.openmason.engine.format.omui.UiVocabularyProvider}).
 *
 * @param accessibleRole  role when the element sets none ({@code "none"} for no role)
 * @param drawsProvider   the element's {@code provider} prop is a draw provider id
 */
public record UiThemeWidget(WidgetDescriptor descriptor, Painter painter, String accessibleRole,
                            boolean drawsProvider) {

    public UiThemeWidget {
        if (!UiWidgets.isNamespaced(descriptor.type())) {
            throw new IllegalArgumentException("a game widget is namespaced: " + descriptor.type());
        }
        accessibleRole = accessibleRole == null || accessibleRole.isEmpty() ? "none" : accessibleRole;
    }

    public String type() {
        return descriptor.type();
    }

    /** Paints the widget's own look (between the element's background and its border). */
    @FunctionalInterface
    public interface Painter {
        void paint(Paint paint);
    }

    /**
     * One element's paint call.
     *
     * @param rect     the element's device-pixel rect
     * @param scale    device pixels per logical pixel
     * @param provider draws the element's host draw provider (placeholder + diagnostic when missing)
     */
    public record Paint(MasonryUI masonry, Canvas canvas, UiElement element, UiRect rect, float scale,
                        Runnable provider) {
    }
}
