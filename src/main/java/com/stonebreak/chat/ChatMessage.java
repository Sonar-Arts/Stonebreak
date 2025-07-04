package com.stonebreak.chat;

public class ChatMessage {
    private final String text;
    private final long timestamp;
    private final float[] color;
    private final float fadeStartTime;
    
    public static final float MESSAGE_DISPLAY_TIME = 10.0f; // 10 seconds like Minecraft
    public static final float MESSAGE_FADE_TIME = 2.0f; // 2 seconds fade out
    
    public ChatMessage(String text) {
        this(text, new float[]{1.0f, 1.0f, 1.0f, 1.0f}); // White by default
    }
    
    public ChatMessage(String text, float[] color) {
        this.text = text;
        this.timestamp = System.currentTimeMillis();
        this.color = color.clone();
        this.fadeStartTime = MESSAGE_DISPLAY_TIME - MESSAGE_FADE_TIME;
    }
    
    public String getText() {
        return text;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public float[] getColor() {
        return color.clone();
    }
    
    public float getAge() {
        return (System.currentTimeMillis() - timestamp) / 1000.0f;
    }
    
    public float getAlpha() {
        float age = getAge();
        
        if (age < fadeStartTime) {
            return color[3]; // Full alpha during display time
        } else if (age < MESSAGE_DISPLAY_TIME) {
            // Fade out over the last MESSAGE_FADE_TIME seconds
            float fadeProgress = (age - fadeStartTime) / MESSAGE_FADE_TIME;
            return color[3] * (1.0f - fadeProgress);
        } else {
            return 0.0f; // Fully faded
        }
    }
    
    public boolean shouldRemove() {
        return getAge() >= MESSAGE_DISPLAY_TIME;
    }
}