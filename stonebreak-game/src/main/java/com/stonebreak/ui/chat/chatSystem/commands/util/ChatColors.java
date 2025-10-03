package com.stonebreak.ui.chat.chatSystem.commands.util;

/**
 * Centralized color definitions for chat messages.
 * Follows DRY principle by having colors defined in one place.
 */
public class ChatColors {
    public static final float[] WHITE = {1.0f, 1.0f, 1.0f, 1.0f};
    public static final float[] RED = {1.0f, 0.0f, 0.0f, 1.0f};
    public static final float[] GREEN = {0.0f, 1.0f, 0.0f, 1.0f};
    public static final float[] BLUE = {0.0f, 0.0f, 1.0f, 1.0f};
    public static final float[] YELLOW = {1.0f, 1.0f, 0.0f, 1.0f};
    public static final float[] ORANGE = {1.0f, 0.5f, 0.0f, 1.0f};
    public static final float[] CYAN = {0.0f, 1.0f, 1.0f, 1.0f};
    public static final float[] LIGHT_GRAY = {0.8f, 0.8f, 0.8f, 1.0f};
    public static final float[] LIGHT_GREEN = {0.7f, 1.0f, 0.7f, 1.0f};
    public static final float[] LIGHT_MAGENTA = {1.0f, 0.7f, 1.0f, 1.0f};

    private ChatColors() {
        // Utility class - no instantiation
    }
}
