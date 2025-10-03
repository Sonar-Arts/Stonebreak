package com.stonebreak.ui.chat.chatSystem;

/**
 * Manages cursor blinking state and timing.
 * Follows Single Responsibility Principle by focusing solely on cursor state.
 */
public class ChatCursorState {
    private static final float BLINK_INTERVAL = 1.0f; // Blink every second

    private float blinkTimer;
    private boolean showCursor;

    public ChatCursorState() {
        this.blinkTimer = 0.0f;
        this.showCursor = true;
    }

    /**
     * Update cursor blink state
     */
    public void update(float deltaTime) {
        blinkTimer += deltaTime;
        if (blinkTimer >= BLINK_INTERVAL) {
            showCursor = !showCursor;
            blinkTimer = 0.0f;
        }
    }

    /**
     * Reset cursor to visible state
     */
    public void reset() {
        blinkTimer = 0.0f;
        showCursor = true;
    }

    /**
     * Check if cursor should be shown
     */
    public boolean shouldShowCursor() {
        return showCursor;
    }

    /**
     * Get display cursor (underscore character)
     */
    public String getDisplayCursor() {
        return showCursor ? "_" : "";
    }
}
