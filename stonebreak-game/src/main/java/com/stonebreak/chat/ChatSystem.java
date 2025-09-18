package com.stonebreak.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

import com.stonebreak.core.Game;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

public class ChatSystem {
    private static final int MAX_MESSAGES = 100; // Like Minecraft
    private static final int MAX_VISIBLE_MESSAGES = 10; // Messages shown at once
    
    private final List<ChatMessage> messages;
    private boolean isOpen;
    private final StringBuilder currentInput;
    private float blinkTimer;
    private boolean showCursor;
    
    public ChatSystem() {
        this.messages = new ArrayList<>();
        this.isOpen = false;
        this.currentInput = new StringBuilder();
        this.blinkTimer = 0.0f;
        this.showCursor = true;
    }
    
    public void addMessage(String text) {
        addMessage(text, new float[]{1.0f, 1.0f, 1.0f, 1.0f});
    }
    
    public void addMessage(String text, float[] color) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        
        // Split long messages into multiple lines if needed
        String[] lines = wrapText(text, 60); // Approximate character limit per line
        
        for (String line : lines) {
            messages.add(new ChatMessage(line, color));
        }
        
        // Remove old messages if we exceed the limit
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }
    
    public void openChat() {
        if (!isOpen) {
            isOpen = true;
            currentInput.setLength(0);
            blinkTimer = 0.0f;
            showCursor = true;
            
            // Update mouse capture state when chat opens - force immediate update
            MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
            if (mouseCaptureManager != null) {
                mouseCaptureManager.forceUpdate();
            }
        }
    }
    
    public void closeChat() {
        isOpen = false;
        currentInput.setLength(0);
        
        // Update mouse capture state when chat closes - force immediate update
        MouseCaptureManager mouseCaptureManager = Game.getInstance().getMouseCaptureManager();
        if (mouseCaptureManager != null) {
            mouseCaptureManager.forceUpdate();
        }
    }
    
    public boolean isOpen() {
        return isOpen;
    }
    
    public void update(float deltaTime) {
        // Remove expired messages
        Iterator<ChatMessage> iterator = messages.iterator();
        while (iterator.hasNext()) {
            ChatMessage message = iterator.next();
            if (message.shouldRemove()) {
                iterator.remove();
            }
        }
        
        // Update cursor blink
        if (isOpen) {
            blinkTimer += deltaTime;
            if (blinkTimer >= 1.0f) { // Blink every second
                showCursor = !showCursor;
                blinkTimer = 0.0f;
            }
        }
    }
    
    public void handleCharInput(char character) {
        if (!isOpen) {
            return;
        }
        
        // Filter out control characters
        if (character >= 32 && character <= 126) {
            currentInput.append(character);
        }
    }
    
    public void handleBackspace() {
        if (!isOpen || currentInput.length() == 0) {
            return;
        }

        currentInput.setLength(currentInput.length() - 1);
    }

    public void handlePaste() {
        if (!isOpen) {
            return;
        }

        try {
            long window = Game.getInstance().getWindow();
            if (window != 0) {
                String clipboardText = glfwGetClipboardString(window);
                if (clipboardText != null && !clipboardText.isEmpty()) {
                    // Filter clipboard text to only allow valid characters
                    StringBuilder filteredText = new StringBuilder();
                    for (char c : clipboardText.toCharArray()) {
                        if (c >= 32 && c <= 126) { // Same filter as handleCharInput
                            filteredText.append(c);
                        }
                    }

                    // Limit total input length to prevent spam
                    String textToAdd = filteredText.toString();
                    int maxLength = 256; // Reasonable chat message limit
                    int availableSpace = maxLength - currentInput.length();

                    if (availableSpace > 0) {
                        if (textToAdd.length() > availableSpace) {
                            textToAdd = textToAdd.substring(0, availableSpace);
                        }
                        currentInput.append(textToAdd);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to paste from clipboard: " + e.getMessage());
        }
    }

    public void handleCopy() {
        if (!isOpen) {
            return;
        }

        // Copy current input text to clipboard
        String currentText = currentInput.toString();
        if (!currentText.isEmpty()) {
            copyMessageToClipboard(currentText);
        }
    }

    public void copyMessageToClipboard(String message) {
        try {
            long window = Game.getInstance().getWindow();
            if (window != 0 && message != null && !message.isEmpty()) {
                glfwSetClipboardString(window, message);
            }
        } catch (Exception e) {
            System.err.println("Failed to copy to clipboard: " + e.getMessage());
        }
    }
    
    public void handleEnter() {
        if (!isOpen) {
            return;
        }
        
        String message = currentInput.toString().trim();
        if (!message.isEmpty()) {
            if (message.startsWith("/")) {
                processCommand(message);
            } else {
                addMessage("<Player> " + message);
            }
        }
        
        closeChat();
    }
    
    private void processCommand(String command) {
        String[] parts = command.split(" ");
        String commandName = parts[0].toLowerCase();
        
        switch (commandName) {
            case "/cheats" -> {
                if (parts.length >= 2) {
                    try {
                        int value = Integer.parseInt(parts[1]);
                        switch (value) {
                            case 1 -> {
                                Game.getInstance().setCheatsEnabled(true);
                                addMessage("Cheats enabled!", new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green
                            }
                            case 0 -> {
                                Game.getInstance().setCheatsEnabled(false);
                                addMessage("Cheats disabled!", new float[]{1.0f, 0.5f, 0.0f, 1.0f}); // Orange
                            }
                            default -> addMessage("Usage: /cheats <1|0>", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                        }
                    } catch (NumberFormatException e) {
                        addMessage("Usage: /cheats <1|0>", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    }
                } else {
                    // Toggle cheats if no parameter provided
                    boolean currentState = Game.getInstance().isCheatsEnabled();
                    Game.getInstance().setCheatsEnabled(!currentState);
                    if (!currentState) {
                        addMessage("Cheats enabled!", new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green
                    } else {
                        addMessage("Cheats disabled!", new float[]{1.0f, 0.5f, 0.0f, 1.0f}); // Orange
                    }
                }
            }
            case "/fly" -> {
                if (!Game.getInstance().isCheatsEnabled()) {
                    addMessage("Cheats must be enabled first! Use /cheats", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }
                
                Player player = Game.getPlayer();
                if (player == null) {
                    addMessage("Player not found!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }
                
                if (parts.length >= 2) {
                    try {
                        int value = Integer.parseInt(parts[1]);
                        switch (value) {
                            case 1 -> {
                                player.setFlightEnabled(true);
                                addMessage("Flight enabled! Double-tap space to fly", new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green
                            }
                            case 0 -> {
                                player.setFlightEnabled(false);
                                addMessage("Flight disabled!", new float[]{1.0f, 0.5f, 0.0f, 1.0f}); // Orange
                            }
                            default -> addMessage("Usage: /fly <1|0>", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                        }
                    } catch (NumberFormatException e) {
                        addMessage("Usage: /fly <1|0>", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    }
                } else {
                    // Toggle flight if no parameter provided
                    boolean currentState = player.isFlightEnabled();
                    player.setFlightEnabled(!currentState);
                    if (!currentState) {
                        addMessage("Flight enabled! Double-tap space to fly", new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green
                    } else {
                        addMessage("Flight disabled!", new float[]{1.0f, 0.5f, 0.0f, 1.0f}); // Orange
                    }
                }
            }
            case "/spawn_soundemit" -> {
                if (!Game.getInstance().isCheatsEnabled()) {
                    addMessage("Cheats must be enabled first! Use /cheats", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }

                // Check if world is loaded
                if (Game.getWorld() == null) {
                    addMessage("Cannot spawn sound emitter - no world loaded!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }

                Player player = Game.getPlayer();
                if (player == null) {
                    addMessage("Player not found!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }

                // Get player position and spawn sound emitter there
                org.joml.Vector3f playerPos = player.getPosition();
                org.joml.Vector3f spawnPos = new org.joml.Vector3f(playerPos.x, playerPos.y + 1.0f, playerPos.z); // Slightly above player

                com.stonebreak.audio.emitters.SoundEmitterManager emitterManager = Game.getSoundEmitterManager();
                if (emitterManager != null) {
                    com.stonebreak.audio.emitters.types.BlockPickupSoundEmitter emitter =
                        emitterManager.spawnBlockPickupEmitter(spawnPos);

                    addMessage(String.format("Sound emitter spawned at (%.1f, %.1f, %.1f)",
                        spawnPos.x, spawnPos.y, spawnPos.z), new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green
                    addMessage("Enable debug mode to see the yellow wireframe triangle!",
                        new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow
                } else {
                    addMessage("Sound emitter system not available!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                }
            }
            case "/test_3d_audio" -> {
                if (!Game.getInstance().isCheatsEnabled()) {
                    addMessage("Cheats must be enabled first! Use /cheats", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }

                // Check if world is loaded
                if (Game.getWorld() == null) {
                    addMessage("Cannot test 3D audio - no world loaded!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }

                Player player = Game.getPlayer();
                if (player == null) {
                    addMessage("Player not found!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }

                addMessage("Starting 3D audio test with sounds at 2, 10, 25, and 60 blocks...",
                    new float[]{0.0f, 1.0f, 1.0f, 1.0f}); // Cyan
                addMessage("Check console for detailed debug information!",
                    new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow

                // Run the test in a separate thread to avoid blocking the chat
                new Thread(() -> {
                    Game.getSoundSystem().test3DAudio();
                }).start();
            }
            case "/test_3d_near" -> {
                if (!Game.getInstance().isCheatsEnabled()) {
                    addMessage("Cheats must be enabled first! Use /cheats", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }
                if (Game.getWorld() == null || Game.getPlayer() == null) {
                    addMessage("World and player required!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }
                addMessage("Testing 3D audio at 2 blocks distance...", new float[]{0.0f, 1.0f, 1.0f, 1.0f}); // Cyan
                Game.getSoundSystem().testSingle3DAudio(2.0f);
            }
            case "/test_3d_far" -> {
                if (!Game.getInstance().isCheatsEnabled()) {
                    addMessage("Cheats must be enabled first! Use /cheats", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }
                if (Game.getWorld() == null || Game.getPlayer() == null) {
                    addMessage("World and player required!", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }
                addMessage("Testing 3D audio at 60 blocks distance...", new float[]{0.0f, 1.0f, 1.0f, 1.0f}); // Cyan
                Game.getSoundSystem().testSingle3DAudio(60.0f);
            }
            case "/diagnose_openal" -> {
                if (!Game.getInstance().isCheatsEnabled()) {
                    addMessage("Cheats must be enabled first! Use /cheats", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }
                addMessage("Running OpenAL 3D audio diagnosis...", new float[]{0.0f, 1.0f, 1.0f, 1.0f}); // Cyan
                addMessage("Check console for detailed information!", new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow
                Game.getSoundSystem().diagnoseOpenAL3D();
            }
            case "/voxeladj" -> {
                if (!Game.getInstance().isCheatsEnabled()) {
                    addMessage("Cheats must be enabled first! Use /cheats", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                    return;
                }

                if (parts.length >= 4 && parts.length <= 8) {
                    try {
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        float z = Float.parseFloat(parts[3]);

                        Vector3f currentRotAdjustment = VoxelizedSpriteRenderer.getRotationAdjustment();
                        float currentScaleAdjustment = VoxelizedSpriteRenderer.getScaleAdjustment();

                        if (parts.length == 8) {
                            // Full 7-parameter mode: x y z rotX rotY rotZ scale
                            float rotX = Float.parseFloat(parts[4]);
                            float rotY = Float.parseFloat(parts[5]);
                            float rotZ = Float.parseFloat(parts[6]);
                            float scale = Float.parseFloat(parts[7]);

                            // Apply the adjustment
                            VoxelizedSpriteRenderer.adjustTransform(x, y, z, rotX, rotY, rotZ, scale);

                            addMessage(String.format("Voxel transform adjusted: pos(%.3f, %.3f, %.3f) rot(%.1f°, %.1f°, %.1f°) scale(%.2f)",
                                x, y, z, rotX, rotY, rotZ, scale), new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green

                        } else if (parts.length == 7) {
                            // 6-parameter mode: x y z rotX rotY rotZ (no scale change)
                            float rotX = Float.parseFloat(parts[4]);
                            float rotY = Float.parseFloat(parts[5]);
                            float rotZ = Float.parseFloat(parts[6]);

                            // Apply the adjustment
                            VoxelizedSpriteRenderer.adjustTransformNoScale(x, y, z, rotX, rotY, rotZ);

                            addMessage(String.format("Voxel transform adjusted: pos(%.3f, %.3f, %.3f) rot(%.1f°, %.1f°, %.1f°)",
                                x, y, z, rotX, rotY, rotZ), new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green

                        } else if (parts.length == 5) {
                            // 4-parameter mode: x y z rotY (backward compatibility)
                            float rotY = Float.parseFloat(parts[4]);
                            VoxelizedSpriteRenderer.adjustTransformSingleRotation(x, y, z, rotY);

                            addMessage(String.format("Voxel transform adjusted: pos(%.3f, %.3f, %.3f) rotY(%.1f°)",
                                x, y, z, rotY), new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green

                        } else {
                            // 3-parameter mode: x y z only (keep current rotation and scale)
                            VoxelizedSpriteRenderer.adjustTranslation(x, y, z);

                            addMessage(String.format("Voxel position adjusted: (%.3f, %.3f, %.3f)", x, y, z),
                                new float[]{0.0f, 1.0f, 0.0f, 1.0f}); // Green
                        }

                        // Get values for output
                        Vector3f baseTranslation = VoxelizedSpriteRenderer.getBaseTranslation();
                        Vector3f adjustment = VoxelizedSpriteRenderer.getTranslationAdjustment();
                        Vector3f finalTranslation = VoxelizedSpriteRenderer.getFinalTranslation();
                        Vector3f baseRotation = VoxelizedSpriteRenderer.getBaseRotation();
                        Vector3f rotationAdjustment = VoxelizedSpriteRenderer.getRotationAdjustment();
                        Vector3f finalRotation = VoxelizedSpriteRenderer.getFinalRotation();
                        float baseScale = VoxelizedSpriteRenderer.getBaseScale();
                        float scaleAdjustment = VoxelizedSpriteRenderer.getScaleAdjustment();
                        float finalScale = VoxelizedSpriteRenderer.getFinalScale();

                        // Translation info
                        addMessage(String.format("Base translation: (%.3f, %.3f, %.3f)",
                            baseTranslation.x, baseTranslation.y, baseTranslation.z),
                            new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                        addMessage(String.format("Translation adjustment: (%.3f, %.3f, %.3f)",
                            adjustment.x, adjustment.y, adjustment.z),
                            new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow
                        addMessage(String.format("Final translation: (%.3f, %.3f, %.3f)",
                            finalTranslation.x, finalTranslation.y, finalTranslation.z),
                            new float[]{0.0f, 1.0f, 1.0f, 1.0f}); // Cyan

                        // Rotation info
                        addMessage(String.format("Base rotation: (%.1f°, %.1f°, %.1f°)",
                            baseRotation.x, baseRotation.y, baseRotation.z),
                            new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                        addMessage(String.format("Rotation adjustment: (%.1f°, %.1f°, %.1f°)",
                            rotationAdjustment.x, rotationAdjustment.y, rotationAdjustment.z),
                            new float[]{1.0f, 0.7f, 1.0f, 1.0f}); // Light magenta
                        addMessage(String.format("Final rotation: (%.1f°, %.1f°, %.1f°)",
                            finalRotation.x, finalRotation.y, finalRotation.z),
                            new float[]{0.7f, 1.0f, 0.7f, 1.0f}); // Light green

                        // Scale info
                        addMessage(String.format("Base scale: %.2f | Scale adjustment: %.2f | Final scale: %.2f",
                            baseScale, scaleAdjustment, finalScale),
                            new float[]{1.0f, 0.5f, 0.2f, 1.0f}); // Orange

                        // Debug output to console
                        System.out.println("=== VOXELADJ DEBUG OUTPUT ===");
                        System.out.printf("Command parameters: %d%n", parts.length);
                        for (int i = 0; i < parts.length; i++) {
                            System.out.printf("  [%d]: %s%n", i, parts[i]);
                        }
                        System.out.printf("Translation - Base: (%.3f, %.3f, %.3f) | Adjustment: (%.3f, %.3f, %.3f) | Final: (%.3f, %.3f, %.3f)%n",
                            baseTranslation.x, baseTranslation.y, baseTranslation.z,
                            x, y, z,
                            finalTranslation.x, finalTranslation.y, finalTranslation.z);
                        System.out.printf("Rotation - Base: (%.1f°, %.1f°, %.1f°) | Adjustment: (%.1f°, %.1f°, %.1f°) | Final: (%.1f°, %.1f°, %.1f°)%n",
                            baseRotation.x, baseRotation.y, baseRotation.z,
                            rotationAdjustment.x, rotationAdjustment.y, rotationAdjustment.z,
                            finalRotation.x, finalRotation.y, finalRotation.z);
                        System.out.printf("Scale - Base: %.3f | Adjustment: %.3f | Final: %.3f%n",
                            baseScale, scaleAdjustment, finalScale);
                        System.out.println("==============================");

                    } catch (NumberFormatException e) {
                        addMessage("Usage: /voxeladj <x> <y> <z> [rotY] OR <x> <y> <z> <rotX> <rotY> <rotZ> [scale]", new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
                        addMessage("Example: /voxeladj 0.1 -0.05 0.2 15.0 10.0 -5.0 1.5", new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                    }
                } else {
                    // Show current values if no parameters provided
                    Vector3f baseTranslation = VoxelizedSpriteRenderer.getBaseTranslation();
                    Vector3f adjustment = VoxelizedSpriteRenderer.getTranslationAdjustment();
                    Vector3f finalTranslation = VoxelizedSpriteRenderer.getFinalTranslation();
                    Vector3f baseRotation = VoxelizedSpriteRenderer.getBaseRotation();
                    Vector3f rotationAdjustment = VoxelizedSpriteRenderer.getRotationAdjustment();
                    Vector3f finalRotation = VoxelizedSpriteRenderer.getFinalRotation();
                    float baseScale = VoxelizedSpriteRenderer.getBaseScale();
                    float scaleAdjustment = VoxelizedSpriteRenderer.getScaleAdjustment();
                    float finalScale = VoxelizedSpriteRenderer.getFinalScale();

                    addMessage("Current voxel transform values:", new float[]{1.0f, 1.0f, 1.0f, 1.0f}); // White

                    // Translation info
                    addMessage(String.format("Base translation: (%.3f, %.3f, %.3f)",
                        baseTranslation.x, baseTranslation.y, baseTranslation.z),
                        new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                    addMessage(String.format("Current translation adjustment: (%.3f, %.3f, %.3f)",
                        adjustment.x, adjustment.y, adjustment.z),
                        new float[]{1.0f, 1.0f, 0.0f, 1.0f}); // Yellow
                    addMessage(String.format("Final translation: (%.3f, %.3f, %.3f)",
                        finalTranslation.x, finalTranslation.y, finalTranslation.z),
                        new float[]{0.0f, 1.0f, 1.0f, 1.0f}); // Cyan

                    // Rotation info
                    addMessage(String.format("Base rotation: (%.1f°, %.1f°, %.1f°)",
                        baseRotation.x, baseRotation.y, baseRotation.z),
                        new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                    addMessage(String.format("Current rotation adjustment: (%.1f°, %.1f°, %.1f°)",
                        rotationAdjustment.x, rotationAdjustment.y, rotationAdjustment.z),
                        new float[]{1.0f, 0.7f, 1.0f, 1.0f}); // Light magenta
                    addMessage(String.format("Final rotation: (%.1f°, %.1f°, %.1f°)",
                        finalRotation.x, finalRotation.y, finalRotation.z),
                        new float[]{0.7f, 1.0f, 0.7f, 1.0f}); // Light green

                    // Scale info
                    addMessage(String.format("Base scale: %.2f | Current scale adjustment: %.2f | Final scale: %.2f",
                        baseScale, scaleAdjustment, finalScale),
                        new float[]{1.0f, 0.5f, 0.2f, 1.0f}); // Orange

                    addMessage("Usage options:", new float[]{1.0f, 1.0f, 1.0f, 1.0f}); // White
                    addMessage("  /voxeladj <x> <y> <z>                       - Position only", new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                    addMessage("  /voxeladj <x> <y> <z> <rotY>                - Position + Y rotation", new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                    addMessage("  /voxeladj <x> <y> <z> <rotX> <rotY> <rotZ>   - Position + XYZ rotation", new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                    addMessage("  /voxeladj <x> <y> <z> <rotX> <rotY> <rotZ> <scale> - Full transform", new float[]{0.8f, 0.8f, 0.8f, 1.0f}); // Light gray
                }
            }
            default -> addMessage("Unknown command: " + commandName, new float[]{1.0f, 0.0f, 0.0f, 1.0f}); // Red
        }
    }
    
    public String getCurrentInput() {
        return currentInput.toString();
    }
    
    public String getDisplayInput() {
        String input = getCurrentInput();
        if (isOpen && showCursor) {
            return input + "_";
        }
        return input;
    }
    
    public List<ChatMessage> getVisibleMessages() {
        List<ChatMessage> visible = new ArrayList<>();
        
        if (isOpen) {
            // When chat is open, show all recent messages (up to MAX_VISIBLE_MESSAGES)
            int startIndex = Math.max(0, messages.size() - MAX_VISIBLE_MESSAGES);
            for (int i = startIndex; i < messages.size(); i++) {
                visible.add(messages.get(i));
            }
        } else {
            // When chat is closed, only show messages that haven't faded yet
            for (ChatMessage message : messages) {
                if (message.getAlpha() > 0.0f) {
                    visible.add(message);
                }
            }
            
            // Limit to most recent visible messages
            if (visible.size() > MAX_VISIBLE_MESSAGES) {
                visible = visible.subList(visible.size() - MAX_VISIBLE_MESSAGES, visible.size());
            }
        }
        
        return visible;
    }
    
    private String[] wrapText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return new String[]{text};
        }
        
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        
        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > maxLength) {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                }
                
                // Handle very long words
                if (word.length() > maxLength) {
                    while (word.length() > maxLength) {
                        lines.add(word.substring(0, maxLength));
                        word = word.substring(maxLength);
                    }
                    if (!word.isEmpty()) {
                        currentLine.append(word);
                    }
                } else {
                    currentLine.append(word);
                }
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
        }
        
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        
        return lines.toArray(String[]::new);
    }
}