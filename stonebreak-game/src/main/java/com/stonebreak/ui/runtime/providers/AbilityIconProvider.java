package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.rpg.classes.AbilityIconCache;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;

/**
 * {@value #ID}: a class or ability icon filling the element ({@link AbilityIconCache}, the legacy screens'
 * loader; the cache is the retained bridge recorded in the ledger). Params: {@code path}, the icon's classpath
 * resource; nothing is drawn without one or when it does not load. Pure Skia (#301).
 */
public final class AbilityIconProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:ability-icon";
    public static final int VERSION = 1;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        if (!(element.prop("params") instanceof UiValue.Obj p) || !(p.get("path") instanceof UiValue.Str path)
                || path.value().isEmpty()) {
            return;
        }
        Image icon = AbilityIconCache.get(path.value());
        if (icon != null) {
            MPainter.drawImage(canvas, icon, rect.x(), rect.y(), rect.width(), rect.height());
        }
    }
}
