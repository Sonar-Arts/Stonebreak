package com.openmason.engine.ui.runtime.input;

/**
 * Platform input and text abilities a host may or may not have (#288). Hosts declare theirs;
 * {@link UiInputGate} compares them with what a document needs, so a missing feature blocks the
 * screen's migration instead of silently degrading it.
 */
public enum InputCapability {
    POINTER,
    WHEEL,
    KEYBOARD,
    /** Committed text in the Basic Multilingual Plane. */
    TEXT_INPUT,
    /** Committed text beyond the BMP (emoji, rarer scripts) delivered as whole code points. */
    TEXT_INPUT_SUPPLEMENTARY,
    CLIPBOARD,
    /** In-progress IME text (preedit) with a caret; GLFW 3.4 has no source for it. */
    IME_COMPOSITION,
    /** Standard-layout controllers. */
    GAMEPAD,
    /** Right-to-left paragraphs painted and edited correctly. */
    RTL_TEXT,
    /** Shaped text (ligatures, combining marks, complex scripts) instead of glyph-by-glyph drawing. */
    COMPLEX_SHAPING,
    /** Glyphs the primary font lacks come from fallback fonts instead of drawing as boxes. */
    FONT_FALLBACK,
    /** A platform accessibility API (screen readers) consumes the semantic tree. */
    ACCESSIBILITY_BRIDGE
}
