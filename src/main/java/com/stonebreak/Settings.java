package com.stonebreak;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class Settings {
    private static Settings instance;
    private static final String SETTINGS_FILE = "settings.json";
    
    // Default settings
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private float masterVolume = 1.0f;
    
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
        loadSettings();
    }
    
    public static Settings getInstance() {
        if (instance == null) {
            instance = new Settings();
        }
        return instance;
    }
    
    public void saveSettings() {
        try {
            Map<String, Object> settingsMap = new HashMap<>();
            settingsMap.put("windowWidth", windowWidth);
            settingsMap.put("windowHeight", windowHeight);
            settingsMap.put("masterVolume", masterVolume);
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"windowWidth\": ").append(windowWidth).append(",\n");
            json.append("  \"windowHeight\": ").append(windowHeight).append(",\n");
            json.append("  \"masterVolume\": ").append(masterVolume).append("\n");
            json.append("}");
            
            Files.write(Paths.get(SETTINGS_FILE), json.toString().getBytes());
            System.out.println("Settings saved to " + SETTINGS_FILE);
        } catch (IOException e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }
    
    public void loadSettings() {
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
    
    // Getters
    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public float getMasterVolume() { return masterVolume; }
    
    // Setters
    public void setResolution(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }
    
    public void setMasterVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
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