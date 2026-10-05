package com.openmason.engine.ui.runtime.input;

/**
 * Timing and distance thresholds of a router (#288). Lengths are logical pixels (scaled with
 * the document), times seconds on the router's clock. A migrating screen sets what its legacy
 * version used, so behaviour and timing are preserved.
 *
 * @param doubleClickTime         max gap between clicks that count together
 * @param doubleClickDistance     max pointer travel between them
 * @param dragThreshold           pointer travel before a press on a draggable starts a drag
 * @param wheelStep               scroll distance per wheel notch
 * @param tooltipDelay            hover time before a tooltip shows (0 = at once)
 * @param controllerRepeatDelay   hold time before a controller action repeats; keyboard repeats
 *                                come from the platform, so the player's OS setting applies
 * @param controllerRepeatInterval time between controller repeats
 * @param caretBlinkInterval      text caret on/off period (0 or reduced motion = steady)
 */
public record InputSettings(double doubleClickTime, float doubleClickDistance, float dragThreshold, float wheelStep,
                            double tooltipDelay, double controllerRepeatDelay, double controllerRepeatInterval,
                            double caretBlinkInterval) {

    /** Legacy Masonry timings: 600 ms caret blink (MTextField), 40 px per wheel notch. */
    public static final InputSettings DEFAULTS = new InputSettings(0.4, 4f, 4f, 40f, 0.5, 0.4, 0.08, 0.6);

    public InputSettings withTooltipDelay(double seconds) {
        return new InputSettings(doubleClickTime, doubleClickDistance, dragThreshold, wheelStep, seconds,
            controllerRepeatDelay, controllerRepeatInterval, caretBlinkInterval);
    }

    public InputSettings withControllerRepeat(double delay, double interval) {
        return new InputSettings(doubleClickTime, doubleClickDistance, dragThreshold, wheelStep, tooltipDelay, delay,
            interval, caretBlinkInterval);
    }

    public InputSettings withWheelStep(float step) {
        return new InputSettings(doubleClickTime, doubleClickDistance, dragThreshold, step, tooltipDelay,
            controllerRepeatDelay, controllerRepeatInterval, caretBlinkInterval);
    }
}
