package com.stonebreak.ui;

import static org.lwjgl.glfw.GLFW.*;
import org.lwjgl.system.MemoryStack;
import com.stonebreak.rendering.UI.UIRenderer;

/**
 * Reusable text input field component with full keyboard and mouse support
 */
public class TextInputField {
    private String text = "";
    private boolean focused = false;
    private int cursorPosition = 0;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    
    // Visual properties
    private float x, y, width, height;
    private String placeholder = "";
    private int maxLength = Integer.MAX_VALUE;
    private String iconType = null; // "world", "seed", or null
    private boolean showValidationIndicator = false;
    private String fieldLabel = null;
    
    // Cursor blinking and animations
    private long lastBlinkTime = 0;
    private boolean cursorVisible = true;
    private static final long BLINK_INTERVAL = 600_000_000L; // 600ms for smoother blinking
    
    // Focus animation
    private long focusTransitionStartTime = 0;
    private boolean focusTransitionActive = false;
    private static final long FOCUS_TRANSITION_DURATION = 200_000_000L; // 200ms
    
    // Error shake animation
    private long errorShakeStartTime = 0;
    private boolean errorShakeActive = false;
    private static final long ERROR_SHAKE_DURATION = 300_000_000L; // 300ms
    
    // Input validation
    private InputValidator validator = null;
    
    public interface InputValidator {
        boolean isValid(String text);
        String getErrorMessage();
    }
    
    public TextInputField() {
        updateBlinkState();
    }
    
    public TextInputField(String placeholder) {
        this.placeholder = placeholder;
        updateBlinkState();
    }
    
    /**
     * Sets the position and size of this text field
     */
    public void setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
    
    /**
     * Gets the current text content
     */
    public String getText() {
        return text;
    }
    
    /**
     * Sets the text content and moves cursor to end
     */
    public void setText(String text) {
        this.text = text != null ? text : "";
        cursorPosition = Math.min(this.text.length(), cursorPosition);
        clearSelection();
    }
    
    /**
     * Sets the placeholder text shown when field is empty
     */
    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder != null ? placeholder : "";
    }
    
    /**
     * Sets the maximum allowed text length
     */
    public void setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
    }
    
    /**
     * Sets the input validator
     */
    public void setValidator(InputValidator validator) {
        this.validator = validator;
    }
    
    /**
     * Sets the icon type for this text field
     */
    public void setIconType(String iconType) {
        this.iconType = iconType;
    }
    
    /**
     * Sets whether to show validation indicator
     */
    public void setShowValidationIndicator(boolean show) {
        this.showValidationIndicator = show;
    }
    
    /**
     * Sets the field label
     */
    public void setFieldLabel(String label) {
        this.fieldLabel = label;
    }
    
    /**
     * Gets the focused state
     */
    public boolean isFocused() {
        return focused;
    }
    
    /**
     * Sets the focused state with smooth transition animation
     */
    public void setFocused(boolean focused) {
        if (this.focused != focused) {
            this.focused = focused;
            // Start focus transition animation
            focusTransitionStartTime = System.nanoTime();
            focusTransitionActive = true;
            
            if (focused) {
                updateBlinkState();
            }
            if (!focused) {
                clearSelection();
            }
        }
    }
    
    /**
     * Handles mouse click events
     */
    public boolean handleMouseClick(double mouseX, double mouseY) {
        boolean wasInBounds = isPointInBounds(mouseX, mouseY);
        
        if (wasInBounds) {
            setFocused(true);
            
            // Calculate cursor position based on click location
            float relativeX = (float)(mouseX - x - 10); // Account for padding
            cursorPosition = calculateCursorPositionFromX(relativeX);
            clearSelection();
            updateBlinkState();
            return true;
        } else {
            setFocused(false);
            return false;
        }
    }
    
    /**
     * Handles character input
     */
    public void handleCharacterInput(char character) {
        if (!focused) return;
        
        if (Character.isDefined(character) && !Character.isISOControl(character)) {
            if (hasSelection()) {
                deleteSelection();
            }
            
            if (text.length() < maxLength) {
                text = text.substring(0, cursorPosition) + character + text.substring(cursorPosition);
                cursorPosition++;
                updateBlinkState();
            }
        }
    }
    
    /**
     * Handles key input (backspace, delete, arrow keys, etc.)
     */
    public void handleKeyInput(int key, int action, int mods) {
        if (!focused) return;
        
        if (action == GLFW_PRESS || action == GLFW_REPEAT) {
            boolean shift = (mods & GLFW_MOD_SHIFT) != 0;
            boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
            
            switch (key) {
                case GLFW_KEY_BACKSPACE:
                    if (hasSelection()) {
                        deleteSelection();
                    } else if (cursorPosition > 0) {
                        text = text.substring(0, cursorPosition - 1) + text.substring(cursorPosition);
                        cursorPosition--;
                    }
                    updateBlinkState();
                    break;
                    
                case GLFW_KEY_DELETE:
                    if (hasSelection()) {
                        deleteSelection();
                    } else if (cursorPosition < text.length()) {
                        text = text.substring(0, cursorPosition) + text.substring(cursorPosition + 1);
                    }
                    updateBlinkState();
                    break;
                    
                case GLFW_KEY_LEFT:
                    if (shift && selectionStart == -1) {
                        startSelection();
                    }
                    if (cursorPosition > 0) {
                        cursorPosition--;
                    }
                    if (shift) {
                        updateSelection();
                    } else {
                        clearSelection();
                    }
                    updateBlinkState();
                    break;
                    
                case GLFW_KEY_RIGHT:
                    if (shift && selectionStart == -1) {
                        startSelection();
                    }
                    if (cursorPosition < text.length()) {
                        cursorPosition++;
                    }
                    if (shift) {
                        updateSelection();
                    } else {
                        clearSelection();
                    }
                    updateBlinkState();
                    break;
                    
                case GLFW_KEY_HOME:
                    if (shift && selectionStart == -1) {
                        startSelection();
                    }
                    cursorPosition = 0;
                    if (shift) {
                        updateSelection();
                    } else {
                        clearSelection();
                    }
                    updateBlinkState();
                    break;
                    
                case GLFW_KEY_END:
                    if (shift && selectionStart == -1) {
                        startSelection();
                    }
                    cursorPosition = text.length();
                    if (shift) {
                        updateSelection();
                    } else {
                        clearSelection();
                    }
                    updateBlinkState();
                    break;
                    
                case GLFW_KEY_A:
                    if (ctrl) {
                        selectAll();
                    }
                    break;
                    
                case GLFW_KEY_C:
                    if (ctrl && hasSelection()) {
                        copySelection();
                    }
                    break;
                    
                case GLFW_KEY_V:
                    if (ctrl) {
                        pasteFromClipboard();
                    }
                    break;
                    
                case GLFW_KEY_X:
                    if (ctrl && hasSelection()) {
                        cutSelection();
                    }
                    break;
            }
        }
    }
    
    /**
     * Renders the text input field
     */
    public void render(UIRenderer uiRenderer, MemoryStack stack) {
        updateCursorBlink();
        
        // Calculate animation offsets
        float animationOffset = calculateAnimationOffset();
        float focusAlpha = getFocusTransitionAlpha();
        
        // Apply animation offset to position
        float renderX = x + animationOffset;
        float renderY = y;
        
        // Draw field label if specified
        if (fieldLabel != null && !fieldLabel.isEmpty()) {
            uiRenderer.drawText(fieldLabel, renderX, renderY - 25, "sans", 14.0f, 0.8f, 0.8f, 0.8f, 1.0f);
        }
        
        // Calculate text area accounting for icon space
        float textAreaX = renderX + 10;
        float iconSize = 16;
        float iconPadding = 8;
        
        if (iconType != null) {
            textAreaX += iconSize + iconPadding;
        }
        
        // Draw background with animated position
        uiRenderer.drawTextInputBackground(renderX, renderY, width, height, focused, stack);
        
        // Draw border with animated position
        uiRenderer.drawTextInputBorder(renderX, renderY, width, height, focused, stack);
        
        // Draw icon if specified
        if (iconType != null) {
            float iconX = renderX + 8;
            float iconY = renderY + (height - iconSize) / 2;
            uiRenderer.drawTextInputIcon(iconX, iconY, iconSize, iconType, stack);
        }
        
        // Draw selection if any
        if (hasSelection()) {
            drawSelection(uiRenderer, stack, textAreaX);
        }
        
        // Draw text or placeholder
        String displayText = text.isEmpty() ? placeholder : text;
        boolean isPlaceholder = text.isEmpty() && !placeholder.isEmpty();
        
        if (!displayText.isEmpty()) {
            uiRenderer.drawTextInputText(textAreaX, renderY + height/2, displayText, isPlaceholder, stack);
        }
        
        // Draw cursor with enhanced visibility and animation
        if (focused && cursorVisible) {
            float cursorX = textAreaX + getCursorXPosition();
            // Enhance cursor visibility during focus transition
            float cursorAlpha = Math.max(0.7f, focusAlpha);
            uiRenderer.drawTextInputCursor(cursorX, renderY + 8, renderY + height - 8, stack);
        }
        
        // Draw validation indicator with animated position
        if (showValidationIndicator && validator != null) {
            float indicatorSize = 16;
            float indicatorX = renderX + width - indicatorSize - 8;
            float indicatorY = renderY + (height - indicatorSize) / 2;
            uiRenderer.drawValidationIndicator(indicatorX, indicatorY, indicatorSize, isValid(), stack);
        }
    }
    
    /**
     * Validates the current text content
     */
    public boolean isValid() {
        return validator == null || validator.isValid(text);
    }
    
    /**
     * Gets the validation error message, if any
     */
    public String getValidationError() {
        if (validator != null && !validator.isValid(text)) {
            return validator.getErrorMessage();
        }
        return null;
    }
    
    // Private helper methods
    
    private boolean isPointInBounds(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
    
    private int calculateCursorPositionFromX(float relativeX) {
        // Account for icon space
        float adjustedX = relativeX - 10;
        if (iconType != null) {
            adjustedX -= 24; // icon size + padding
        }
        
        if (text.isEmpty() || adjustedX <= 0) return 0;
        
        // Simple approximation - in a real implementation you'd use font metrics
        float charWidth = 8.0f; // Approximate character width for font size 16
        int position = Math.round(adjustedX / charWidth);
        return Math.max(0, Math.min(text.length(), position));
    }
    
    private float getCursorXPosition() {
        if (cursorPosition == 0) return 0;
        
        // Simple approximation - in a real implementation you'd use font metrics  
        float charWidth = 8.0f; // Approximate character width for font size 14
        return cursorPosition * charWidth;
    }
    
    private void updateBlinkState() {
        lastBlinkTime = System.nanoTime();
        cursorVisible = true;
    }
    
    private void updateCursorBlink() {
        long currentTime = System.nanoTime();
        if (currentTime - lastBlinkTime > BLINK_INTERVAL) {
            cursorVisible = !cursorVisible;
            lastBlinkTime = currentTime;
        }
    }
    
    /**
     * Triggers error shake animation
     */
    public void triggerErrorShake() {
        errorShakeStartTime = System.nanoTime();
        errorShakeActive = true;
    }
    
    /**
     * Calculates animation offset for positioning
     */
    private float calculateAnimationOffset() {
        float offset = 0;
        
        // Error shake animation
        if (errorShakeActive) {
            long currentTime = System.nanoTime();
            long elapsed = currentTime - errorShakeStartTime;
            
            if (elapsed < ERROR_SHAKE_DURATION) {
                // Sine wave shake with decreasing amplitude
                float progress = (float) elapsed / ERROR_SHAKE_DURATION;
                float amplitude = 3.0f * (1.0f - progress); // Diminishing shake
                float frequency = 15.0f; // Shake speed
                offset = amplitude * (float) Math.sin(progress * frequency * Math.PI * 2);
            } else {
                errorShakeActive = false;
            }
        }
        
        return offset;
    }
    
    /**
     * Gets the focus transition alpha for smooth transitions
     */
    private float getFocusTransitionAlpha() {
        if (!focusTransitionActive) {
            return focused ? 1.0f : 0.0f;
        }
        
        long currentTime = System.nanoTime();
        long elapsed = currentTime - focusTransitionStartTime;
        
        if (elapsed >= FOCUS_TRANSITION_DURATION) {
            focusTransitionActive = false;
            return focused ? 1.0f : 0.0f;
        }
        
        // Smooth easing transition
        float progress = (float) elapsed / FOCUS_TRANSITION_DURATION;
        float easedProgress = (float) (1 - Math.cos(progress * Math.PI)) / 2; // Sine easing
        
        return focused ? easedProgress : (1.0f - easedProgress);
    }
    
    private boolean hasSelection() {
        return selectionStart != -1 && selectionEnd != -1 && selectionStart != selectionEnd;
    }
    
    private void startSelection() {
        selectionStart = cursorPosition;
        selectionEnd = cursorPosition;
    }
    
    private void updateSelection() {
        if (selectionStart != -1) {
            selectionEnd = cursorPosition;
        }
    }
    
    private void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
    }
    
    private void selectAll() {
        selectionStart = 0;
        selectionEnd = text.length();
        cursorPosition = text.length();
        updateBlinkState();
    }
    
    private void deleteSelection() {
        if (hasSelection()) {
            int start = Math.min(selectionStart, selectionEnd);
            int end = Math.max(selectionStart, selectionEnd);
            
            text = text.substring(0, start) + text.substring(end);
            cursorPosition = start;
            clearSelection();
        }
    }
    
    private void copySelection() {
        if (hasSelection()) {
            int start = Math.min(selectionStart, selectionEnd);
            int end = Math.max(selectionStart, selectionEnd);
            String selectedText = text.substring(start, end);
            
            // In a real implementation, you'd use GLFW clipboard functions
            // glfwSetClipboardString(window, selectedText);
            System.out.println("Would copy to clipboard: " + selectedText);
        }
    }
    
    private void pasteFromClipboard() {
        // In a real implementation, you'd use GLFW clipboard functions
        // String clipboardText = glfwGetClipboardString(window);
        String clipboardText = ""; // Placeholder
        
        if (clipboardText != null && !clipboardText.isEmpty()) {
            if (hasSelection()) {
                deleteSelection();
            }
            
            // Insert clipboard text at cursor position
            String newText = text.substring(0, cursorPosition) + clipboardText + text.substring(cursorPosition);
            if (newText.length() <= maxLength) {
                text = newText;
                cursorPosition += clipboardText.length();
                updateBlinkState();
            }
        }
    }
    
    private void cutSelection() {
        if (hasSelection()) {
            copySelection();
            deleteSelection();
        }
    }
    
    private void drawSelection(UIRenderer uiRenderer, MemoryStack stack, float textAreaX) {
        if (!hasSelection()) return;
        
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        
        float startX = textAreaX + (start * 8.0f); // Approximate character width
        float endX = textAreaX + (end * 8.0f);
        
        // TODO: Implement text selection highlighting using NanoVG
        // For now, text selection functionality works but visual highlight is not shown
    }
}