package com.stonebreak.config;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Settings {
    private static Settings instance;
    private static final String SETTINGS_FILE = "settings.json";
    
    // Default settings
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private float masterVolume = 1.0f;
    
    // Player model settings
    private String armModelType = "REGULAR"; // "REGULAR" or "SLIM"
    
    // Crosshair settings
    private String crosshairStyle = "SIMPLE_CROSS";
    private float crosshairSize = 16.0f;
    private float crosshairThickness = 2.0f;
    private float crosshairGap = 4.0f;
    private float crosshairOpacity = 1.0f;
    private float crosshairColorR = 1.0f;
    private float crosshairColorG = 1.0f;
    private float crosshairColorB = 1.0f;
    private boolean crosshairOutline = true;

    // Quality settings
    private boolean leafTransparency = true;
    
    // Available resolutions (ordered smallest to largest by total pixels)
    private static final int[][] RESOLUTIONS = {
        {1024, 768},     // 786,432 pixels
        {1280, 720},     // 921,600 pixels  
        {1366, 768},     // 1,049,088 pixels
        {1600, 900},     // 1,440,000 pixels
        {1920, 1080},    // 2,073,600 pixels
        {2560, 1440},    // 3,686,400 pixels
        {3840, 2160}     // 8,294,400 pixels
    };
    
    private Settings() {
        loadSettingsInternal();
    }
    
    public static Settings getInstance() {
        if (instance == null) {
            instance = new Settings();
        }
        return instance;
    }
    
    public void saveSettings() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"windowWidth\": ").append(windowWidth).append(",\n");
            json.append("  \"windowHeight\": ").append(windowHeight).append(",\n");
            json.append("  \"masterVolume\": ").append(masterVolume).append(",\n");
            json.append("  \"armModelType\": \"").append(armModelType).append("\",\n");
            json.append("  \"crosshairStyle\": \"").append(crosshairStyle).append("\",\n");
            json.append("  \"crosshairSize\": ").append(crosshairSize).append(",\n");
            json.append("  \"crosshairThickness\": ").append(crosshairThickness).append(",\n");
            json.append("  \"crosshairGap\": ").append(crosshairGap).append(",\n");
            json.append("  \"crosshairOpacity\": ").append(crosshairOpacity).append(",\n");
            json.append("  \"crosshairColorR\": ").append(crosshairColorR).append(",\n");
            json.append("  \"crosshairColorG\": ").append(crosshairColorG).append(",\n");
            json.append("  \"crosshairColorB\": ").append(crosshairColorB).append(",\n");
            json.append("  \"crosshairOutline\": ").append(crosshairOutline).append(",\n");
            json.append("  \"leafTransparency\": ").append(leafTransparency).append("\n");
            json.append("}");
            
            Files.write(Paths.get(SETTINGS_FILE), json.toString().getBytes());
            System.out.println("Settings saved to " + SETTINGS_FILE);
        } catch (IOException e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }
    
    public void loadSettings() {
        loadSettingsInternal();
    }
    
    private void loadSettingsInternal() {
        try {
            if (!Files.exists(Paths.get(SETTINGS_FILE))) {
                System.out.println("Settings file not found, using defaults");
                return;
            }
            
            String content = new String(Files.readAllBytes(Paths.get(SETTINGS_FILE)));
            parseSettings(content);
            System.out.println("Settings loaded from " + SETTINGS_FILE);
        } catch (IOException e) {
            System.err.println("Failed to load settings: " + e.getMessage());
        }
    }
    
    private void parseSettings(String json) {
        // Simple JSON parsing for our specific format
        String[] lines = json.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("windowWidth")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        windowWidth = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid windowWidth value: " + value);
                    }
                }
            } else if (line.contains("windowHeight")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        windowHeight = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid windowHeight value: " + value);
                    }
                }
            } else if (line.contains("masterVolume")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        masterVolume = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid masterVolume value: " + value);
                    }
                }
            } else if (line.contains("armModelType")) {
                String value = extractStringValue(line);
                if (value != null) {
                    armModelType = value;
                }
            } else if (line.contains("crosshairStyle")) {
                String value = extractStringValue(line);
                if (value != null) {
                    crosshairStyle = value;
                }
            } else if (line.contains("crosshairSize")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairSize = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairSize value: " + value);
                    }
                }
            } else if (line.contains("crosshairThickness")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairThickness = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairThickness value: " + value);
                    }
                }
            } else if (line.contains("crosshairGap")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairGap = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairGap value: " + value);
                    }
                }
            } else if (line.contains("crosshairOpacity")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairOpacity = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairOpacity value: " + value);
                    }
                }
            } else if (line.contains("crosshairColorR")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairColorR = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairColorR value: " + value);
                    }
                }
            } else if (line.contains("crosshairColorG")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairColorG = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairColorG value: " + value);
                    }
                }
            } else if (line.contains("crosshairColorB")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairColorB = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairColorB value: " + value);
                    }
                }
            } else if (line.contains("crosshairOutline")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        crosshairOutline = Boolean.parseBoolean(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid crosshairOutline value: " + value);
                    }
                }
            } else if (line.contains("leafTransparency")) {
                String value = extractValue(line);
                if (value != null) {
                    try {
                        leafTransparency = Boolean.parseBoolean(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid leafTransparency value: " + value);
                    }
                }
            }
        }
    }
    
    private String extractValue(String line) {
        int colonIndex = line.indexOf(':');
        if (colonIndex == -1) return null;
        
        String value = line.substring(colonIndex + 1).trim();
        value = value.replaceAll("[,}]", "").trim();
        return value;
    }
    
    private String extractStringValue(String line) {
        int colonIndex = line.indexOf(':');
        if (colonIndex == -1) return null;
        
        String value = line.substring(colonIndex + 1).trim();
        value = value.replaceAll("[,}]", "").trim();
        value = value.replaceAll("\"", "").trim();
        return value;
    }
    
    // Getters
    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public float getMasterVolume() { return masterVolume; }
    
    // Player model getters
    public String getArmModelType() { return armModelType; }
    public boolean isSlimArms() { return "SLIM".equals(armModelType); }
    
    // Crosshair getters
    public String getCrosshairStyle() { return crosshairStyle; }
    public float getCrosshairSize() { return crosshairSize; }
    public float getCrosshairThickness() { return crosshairThickness; }
    public float getCrosshairGap() { return crosshairGap; }
    public float getCrosshairOpacity() { return crosshairOpacity; }
    public float getCrosshairColorR() { return crosshairColorR; }
    public float getCrosshairColorG() { return crosshairColorG; }
    public float getCrosshairColorB() { return crosshairColorB; }
    public boolean getCrosshairOutline() { return crosshairOutline; }

    // Quality getters
    public boolean getLeafTransparency() { return leafTransparency; }
    
    // Setters
    public void setResolution(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }
    
    public void setMasterVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }
    
    // Player model setters
    public void setArmModelType(String armModelType) {
        if ("REGULAR".equals(armModelType) || "SLIM".equals(armModelType)) {
            this.armModelType = armModelType;
        } else {
            System.err.println("Invalid arm model type: " + armModelType + ". Defaulting to REGULAR.");
            this.armModelType = "REGULAR";
        }
    }
    
    public void setSlimArms(boolean slim) {
        this.armModelType = slim ? "SLIM" : "REGULAR";
    }
    
    // Crosshair setters
    public void setCrosshairStyle(String style) {
        this.crosshairStyle = style;
    }
    
    public void setCrosshairSize(float size) {
        this.crosshairSize = Math.max(4.0f, Math.min(64.0f, size));
    }
    
    public void setCrosshairThickness(float thickness) {
        this.crosshairThickness = Math.max(1.0f, Math.min(8.0f, thickness));
    }
    
    public void setCrosshairGap(float gap) {
        this.crosshairGap = Math.max(0.0f, Math.min(16.0f, gap));
    }
    
    public void setCrosshairOpacity(float opacity) {
        this.crosshairOpacity = Math.max(0.1f, Math.min(1.0f, opacity));
    }
    
    public void setCrosshairColor(float r, float g, float b) {
        this.crosshairColorR = Math.max(0.0f, Math.min(1.0f, r));
        this.crosshairColorG = Math.max(0.0f, Math.min(1.0f, g));
        this.crosshairColorB = Math.max(0.0f, Math.min(1.0f, b));
    }
    
    public void setCrosshairOutline(boolean outline) {
        this.crosshairOutline = outline;
    }

    // Quality setters
    public void setLeafTransparency(boolean leafTransparency) {
        this.leafTransparency = leafTransparency;
    }
    
    // Helper methods
    public static int[][] getAvailableResolutions() {
        return RESOLUTIONS;
    }
    
    public String getCurrentResolutionString() {
        return windowWidth + "x" + windowHeight;
    }
    
    public int getCurrentResolutionIndex() {
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (RESOLUTIONS[i][0] == windowWidth && RESOLUTIONS[i][1] == windowHeight) {
                return i;
            }
        }
        return 0; // Default to first resolution if not found
    }
    
    public void setResolutionByIndex(int index) {
        if (index >= 0 && index < RESOLUTIONS.length) {
            windowWidth = RESOLUTIONS[index][0];
            windowHeight = RESOLUTIONS[index][1];
        }
    }
}