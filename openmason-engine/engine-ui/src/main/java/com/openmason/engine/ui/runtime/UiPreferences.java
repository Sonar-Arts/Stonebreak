package com.openmason.engine.ui.runtime;

/**
 * The player's accessibility preferences an instance honours (#288). They are settings, not
 * document data: the game persists them with its other settings; documents only react.
 *
 * @param reducedMotion steady caret, no tooltip or scroll easing, and (#295) transitions jump
 *                      to their end state; motion that carries meaning (a progress fill) stays
 * @param textScale     multiplies every font size on top of the UI scale, so text can grow
 *                      without growing the whole layout (clamped 0.5–3)
 */
public record UiPreferences(boolean reducedMotion, float textScale) {

    public static final UiPreferences DEFAULTS = new UiPreferences(false, 1f);

    public UiPreferences {
        if (!Float.isFinite(textScale)) {
            textScale = 1f;
        }
        textScale = Math.clamp(textScale, 0.5f, 3f);
    }
}
