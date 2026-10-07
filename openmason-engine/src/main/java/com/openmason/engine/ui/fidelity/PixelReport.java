package com.openmason.engine.ui.fidelity;

/**
 * Outcome of comparing a capture with its baseline (#296).
 *
 * @param mismatched pixels outside every allowance of the tolerance
 * @param shifted    pixels that matched only through the ±texel shift allowance
 * @param worstDelta largest per-channel difference over all pixels (before shift allowances)
 * @param bounds     smallest region holding every mismatched pixel, or null when none
 * @param diff       visual diff: baseline dimmed to grey, mismatches red, shift matches amber;
 *                   null when the comparison passed or the sizes differ
 * @param message    why it failed, or {@code ""}
 * @param largestCluster       biggest 8-connected group of mismatched pixels
 * @param worstMismatchDelta   largest per-channel difference among the mismatched pixels
 */
public record PixelReport(boolean passed, int width, int height, int mismatched, int shifted, int worstDelta,
                          PixelTolerance.Region bounds, FidelityImage diff, String message,
                          int largestCluster, int worstMismatchDelta) {

    public PixelReport(boolean passed, int width, int height, int mismatched, int shifted, int worstDelta,
                       PixelTolerance.Region bounds, FidelityImage diff, String message) {
        this(passed, width, height, mismatched, shifted, worstDelta, bounds, diff, message, 0, 0);
    }

    public String summary() {
        if (!message.isEmpty()) {
            return message;
        }
        String where = bounds == null ? "" : " in [" + bounds.x() + "," + bounds.y() + " "
            + bounds.width() + "x" + bounds.height() + "]";
        return mismatched + " of " + (width * height) + " pixels mismatched" + where + ", " + shifted
            + " shift-matched, worst channel delta " + worstDelta
            + (mismatched > 0 ? ", largest mismatch clump " + largestCluster + " px, worst mismatch delta "
                + worstMismatchDelta : "");
    }
}
