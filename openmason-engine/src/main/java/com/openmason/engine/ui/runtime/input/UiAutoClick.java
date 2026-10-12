package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Development hook (#292): clicks elements of a hosted document at set times, through the real
 * router, so a screenshot script can show a document's interaction (code-behind handlers, awaited
 * host actions) in the game window or the editor preview without a human. Spec:
 * {@code key@seconds[,key@seconds...]}, e.g. {@code resync@2,resume@6.5}; a key starting with
 * {@code #} is a selector by name ({@code #multiplayer@1.5}); times count from the first {@link #tick}.
 */
public final class UiAutoClick {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiAutoClick.class);

    private record Step(String key, double at) {
    }

    private final List<Step> steps = new ArrayList<>();
    private int next;
    private double elapsed;

    private UiAutoClick() {
    }

    /** @return the plan, or null for a blank spec */
    public static UiAutoClick parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        UiAutoClick a = new UiAutoClick();
        for (String part : spec.split(",")) {
            int at = part.lastIndexOf('@');
            if (at <= 0) {
                throw new IllegalArgumentException("autoclick step '" + part + "' is not key@seconds");
            }
            a.steps.add(new Step(part.substring(0, at).trim(), Double.parseDouble(part.substring(at + 1).trim())));
        }
        a.steps.sort((x, y) -> Double.compare(x.at, y.at));
        return a;
    }

    /** Advances by {@code dt} seconds and clicks every element now due (primary button, rect centre). */
    public void tick(UiDocumentInstance ui, UiInputRouter router, double dt) {
        elapsed += dt;
        while (next < steps.size() && steps.get(next).at <= elapsed) {
            Step s = steps.get(next++);
            UiElement el = s.key.startsWith("#") ? ui.q(s.key) : ui.find(s.key);
            if (el == null) {
                LOGGER.warn("[autoclick] no element {}", s.key);
                continue;
            }
            UiRect r = el.rect();
            float x = r.x() + r.width() / 2f;
            float y = r.y() + r.height() / 2f;
            router.pointerMove(x, y);
            router.pointerDown(x, y, PointerEvent.PRIMARY, 0);
            router.pointerUp(x, y, PointerEvent.PRIMARY, 0);
            LOGGER.info(String.format(Locale.ROOT, "[autoclick] %.2fs clicked %s at (%.0f, %.0f)", elapsed, s.key, x, y));
        }
    }
}
