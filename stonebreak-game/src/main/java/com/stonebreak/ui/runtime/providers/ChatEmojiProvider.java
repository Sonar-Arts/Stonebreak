package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.ui.chat.emoji.EmojiImageCache;
import com.stonebreak.ui.chat.emoji.EmojiType;
import com.stonebreak.ui.chat.emoji.GifAnimationCache;
import com.stonebreak.ui.chat.emoji.GifEmojiType;
import com.stonebreak.ui.runtime.contracts.ChatContracts;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;

/**
 * {@value #ID}: one chat emoji ({@code params.emoji}, an id such as {@code banana}) as a square of the
 * element's height at its top-left, exactly as the legacy chat drew it (ledger hard visual 12): the PNG
 * from {@code EmojiImageCache}, or the animated GIF's current frame from {@code GifAnimationCache} on the
 * legacy wall clock. Pure Skia, so the editor preview draws it too.
 */
public final class ChatEmojiProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:chat-emoji";
    public static final int VERSION = 1;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        UiValue params = element.prop("params");
        String id = params instanceof UiValue.Obj o && o.get("emoji") instanceof UiValue.Str s ? s.value() : "";
        Image img = switch (ChatContracts.emoji(id)) {
            case GifEmojiType g -> GifAnimationCache.getCurrentFrame(g);
            case EmojiType e -> EmojiImageCache.get(e);
            case null -> null;
        };
        if (img != null) {
            MPainter.drawImage(canvas, img, rect.x(), rect.y(), rect.height(), rect.height());
        }
    }
}
