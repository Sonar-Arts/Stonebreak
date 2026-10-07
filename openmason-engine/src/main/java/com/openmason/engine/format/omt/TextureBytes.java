package com.openmason.engine.format.omt;

import com.openmason.engine.format.sbt.SBTParser;

/**
 * The one format probe for texture bytes a UI dependency may hold (#294): PNG, SBT (an OMT
 * wrapped with a manifest) or a bare OMT. Decoders ({@code MTexture.decode}) and headless size
 * checks ({@code TextureSizes}) both go through it, so they always agree on what a file is.
 */
public final class TextureBytes {

    private TextureBytes() {
    }

    /** PNG signature (8 bytes). */
    public static boolean isPng(byte[] b) {
        return b != null && b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    /** {width, height} from a PNG's IHDR, or null when the header is truncated or invalid. */
    public static int[] pngSize(byte[] b) {
        if (!isPng(b) || b.length < 24) {
            return null;
        }
        int w = readInt(b, 16);
        int h = readInt(b, 20);
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    /** The layer archive of SBT or OMT bytes (SBT tried first), or null for anything else (PNG included). */
    public static OMTArchive archive(byte[] b) {
        if (b == null || b.length == 0 || isPng(b)) {
            return null;
        }
        try {
            return new OMTReader().read(new SBTParser().read(b).omtBytes());
        } catch (Exception notSbt) {
            try {
                return new OMTReader().read(b);
            } catch (Exception notOmt) {
                return null;
            }
        }
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
