package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.TextureBytes;

/**
 * Pixel size of texture bytes without decoding pixels or touching a GPU (#294): an SBT's or
 * OMT's canvas, or a PNG's header, recognised by the same {@link TextureBytes} probe the runtime
 * decoder uses. Used to check sprite regions in headless tools (export planning, editors).
 */
public final class TextureSizes {

    private TextureSizes() {
    }

    /** @return {width, height}, or null when the bytes are not a texture this engine reads */
    public static int[] of(byte[] bytes) {
        if (TextureBytes.isPng(bytes)) {
            return TextureBytes.pngSize(bytes);
        }
        OMTArchive omt = TextureBytes.archive(bytes);
        return omt == null ? null : new int[]{omt.canvasSize().width(), omt.canvasSize().height()};
    }
}
