package com.stonebreak.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Settings {
    private static final Logger logger = LoggerFactory.getLogger(Settings.class);

    private static Settings instance;
    private static final Path SETTINGS_FILE = Paths.get("settings.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    // Default settings
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private float masterVolume = 1.0f;
    private float musicVolume = 0.6f;
    private boolean musicEnabled = true;

    // Player model settings
    private String armModelType = "REGULAR"; // "REGULAR" or "SLIM"

    // Cosmetic hat mounted on the player model's hat socket ("NONE" = bare head).
    // Valid ids are defined by com.stonebreak.player.PlayerLooks.HAT_OPTIONS;
    // unknown ids fall back to NONE at apply time.
    private String selectedHat = "NONE";

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
    private boolean waterShaderEnabled = true;
    private boolean cloudsEnabled = true;
    private boolean godRaysEnabled = true;
    private boolean shadowsEnabled = true;

    // HUD / multiplayer — show floating username tags above remote players' heads.
    private boolean playerNameTagsEnabled = true;

    // Lighting quality — shadow map tier ("LOW"/"MEDIUM"/"HIGH"), shadow reach in
    // blocks, and baked smooth lighting (per-vertex AO + soft sky gradients).
    // Shadow quality/distance are read live by the shadow pass each frame; smooth
    // lighting is pushed to the engine mesh sampler and needs a chunk rebuild.
    public static final int MIN_SHADOW_DISTANCE = 48;
    public static final int MAX_SHADOW_DISTANCE = 160;
    private String shadowQuality = "MEDIUM";
    private int shadowDistance = 100;
    private boolean smoothLightingEnabled = true;

    // Performance + advanced settings — defaults sourced from WorldConfiguration to avoid drift.
    private int renderDistance = com.stonebreak.world.operations.WorldConfiguration.DEFAULT_RENDER_DISTANCE;
    private int lodDistance = com.stonebreak.world.operations.WorldConfiguration.DEFAULT_LOD_RANGE;
    private boolean lodEnabled = com.stonebreak.world.operations.WorldConfiguration.DEFAULT_LOD_ENABLED;

    // VSync — when true, GLFW caps to display refresh and the manual FPS
    // limiter is bypassed. Default ON: most users expect tear-free output and
    // ZGC keeps frame pacing stable.
    private boolean vsyncEnabled = true;

    // Max FPS — upper bound enforced by the manual frame limiter, independent of
    // VSync (the lowest active cap wins). A value of MAX_MAX_FPS means "Unlimited"
    // (no cap). Default 240 keeps high-refresh displays fast without running wild.
    public static final int MIN_MAX_FPS = 30;
    public static final int MAX_MAX_FPS = 260; // sentinel: this value means Unlimited
    private int maxFps = 240;

    // UI scaling factor applied to all HUD and menu elements.
    private float uiScale = 1.0f;

    // Multiplayer settings
    private int multiplayerPort = 25565;
    private String lastJoinHost = "localhost";
    private String multiplayerUsername = "Player";
    
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
    
    /** Package-private so tests can build a defaults-only instance without touching disk. */
    Settings() {
    }

    // No synchronization needed: getInstance() is always called from the main thread during startup,
    // before any background threads are spawned. Post-init access is read-only.
    public static Settings getInstance() {
        if (instance == null) {
            instance = new Settings();
            instance.loadSettingsInternal();
        }
        return instance;
    }
    
    // ─── Persistence ──────────────────────────────────────────────────────────
    //
    // One declarative table drives both save and load, so adding a setting means
    // adding exactly one row. Reads route through the setters wherever one
    // exists, so a hand-edited file gets the same clamping, validation and side
    // effects as a change made through the settings menu.

    private record Field(String key,
                         BiConsumer<Settings, ObjectNode> write,
                         BiConsumer<Settings, JsonNode> read) {}

    private static final List<Field> PERSISTED = List.of(
            intField("windowWidth", s -> s.windowWidth, (s, v) -> s.windowWidth = v),
            intField("windowHeight", s -> s.windowHeight, (s, v) -> s.windowHeight = v),
            floatField("masterVolume", Settings::getMasterVolume, Settings::setMasterVolume),
            floatField("musicVolume", Settings::getMusicVolume, Settings::setMusicVolume),
            boolField("musicEnabled", Settings::getMusicEnabled, Settings::setMusicEnabled),
            stringField("armModelType", Settings::getArmModelType, Settings::setArmModelType),
            stringField("selectedHat", Settings::getSelectedHat, Settings::setSelectedHat),
            stringField("crosshairStyle", Settings::getCrosshairStyle, Settings::setCrosshairStyle),
            floatField("crosshairSize", Settings::getCrosshairSize, Settings::setCrosshairSize),
            floatField("crosshairThickness", Settings::getCrosshairThickness, Settings::setCrosshairThickness),
            floatField("crosshairGap", Settings::getCrosshairGap, Settings::setCrosshairGap),
            floatField("crosshairOpacity", Settings::getCrosshairOpacity, Settings::setCrosshairOpacity),
            floatField("crosshairColorR", s -> s.crosshairColorR, (s, v) -> s.crosshairColorR = v),
            floatField("crosshairColorG", s -> s.crosshairColorG, (s, v) -> s.crosshairColorG = v),
            floatField("crosshairColorB", s -> s.crosshairColorB, (s, v) -> s.crosshairColorB = v),
            boolField("crosshairOutline", Settings::getCrosshairOutline, Settings::setCrosshairOutline),
            boolField("leafTransparency", Settings::getLeafTransparency, Settings::setLeafTransparency),
            boolField("waterShaderEnabled", Settings::getWaterShaderEnabled, Settings::setWaterShaderEnabled),
            boolField("cloudsEnabled", Settings::getCloudsEnabled, Settings::setCloudsEnabled),
            boolField("godRaysEnabled", Settings::getGodRaysEnabled, Settings::setGodRaysEnabled),
            boolField("shadowsEnabled", Settings::getShadowsEnabled, Settings::setShadowsEnabled),
            boolField("playerNameTagsEnabled", Settings::getPlayerNameTagsEnabled, Settings::setPlayerNameTagsEnabled),
            stringField("shadowQuality", Settings::getShadowQuality, Settings::setShadowQuality),
            intField("shadowDistance", Settings::getShadowDistance, Settings::setShadowDistance),
            boolField("smoothLightingEnabled", Settings::getSmoothLightingEnabled, Settings::setSmoothLightingEnabled),
            intField("renderDistance", Settings::getRenderDistance, Settings::setRenderDistance),
            intField("lodDistance", Settings::getLodDistance, Settings::setLodDistance),
            boolField("lodEnabled", Settings::getLodEnabled, Settings::setLodEnabled),
            boolField("vsyncEnabled", Settings::isVsyncEnabled, Settings::setVsyncEnabled),
            intField("maxFps", Settings::getMaxFps, Settings::setMaxFps),
            intField("multiplayerPort", Settings::getMultiplayerPort, Settings::setMultiplayerPort),
            stringField("lastJoinHost", Settings::getLastJoinHost, Settings::setLastJoinHost),
            stringField("multiplayerUsername", Settings::getMultiplayerUsername, Settings::setMultiplayerUsername),
            floatField("uiScale", Settings::getUiScale, Settings::setUiScale));

    private static Field intField(String key, Function<Settings, Integer> get, BiConsumer<Settings, Integer> set) {
        return new Field(key, (s, node) -> node.put(key, get.apply(s)),
                reader(key, n -> n.canConvertToInt() ? n.intValue() : null, set));
    }

    private static Field floatField(String key, Function<Settings, Float> get, BiConsumer<Settings, Float> set) {
        return new Field(key, (s, node) -> node.put(key, get.apply(s)),
                reader(key, n -> n.isNumber() ? n.floatValue() : null, set));
    }

    private static Field boolField(String key, Function<Settings, Boolean> get, BiConsumer<Settings, Boolean> set) {
        return new Field(key, (s, node) -> node.put(key, get.apply(s)),
                reader(key, n -> n.isBoolean() ? n.booleanValue() : null, set));
    }

    private static Field stringField(String key, Function<Settings, String> get, BiConsumer<Settings, String> set) {
        return new Field(key, (s, node) -> node.put(key, get.apply(s)),
                reader(key, n -> n.isTextual() ? n.textValue() : null, set));
    }

    /** Wraps a typed parse + setter pair, skipping (and reporting) values of the wrong JSON type. */
    private static <T> BiConsumer<Settings, JsonNode> reader(String key,
                                                             Function<JsonNode, T> parse,
                                                             BiConsumer<Settings, T> set) {
        return (settings, node) -> {
            T value = parse.apply(node);
            if (value == null) {
                logger.warn("Ignoring invalid value for setting '{}': {}", key, node);
            } else {
                set.accept(settings, value);
            }
        };
    }

    /** Serialises the current values — exactly what {@link #saveSettings()} writes to disk. */
    ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        PERSISTED.forEach(field -> field.write().accept(this, root));
        return root;
    }

    /** Applies every recognised key present in {@code root}; absent and unknown keys are left alone. */
    void apply(JsonNode root) {
        if (root == null || !root.isObject()) {
            logger.warn("Settings file is not a JSON object; keeping defaults");
            return;
        }
        for (Field field : PERSISTED) {
            JsonNode node = root.get(field.key());
            if (node != null && !node.isNull()) {
                field.read().accept(this, node);
            }
        }
    }

    public void saveSettings() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_FILE.toFile(), toJson());
            logger.info("Settings saved to {}", SETTINGS_FILE);
        } catch (IOException e) {
            logger.error("Failed to save settings to {}", SETTINGS_FILE, e);
        }
    }

    public void loadSettings() {
        loadSettingsInternal();
    }

    private void loadSettingsInternal() {
        if (!Files.exists(SETTINGS_FILE)) {
            logger.info("Settings file not found, using defaults");
            return;
        }
        try {
            apply(MAPPER.readTree(SETTINGS_FILE.toFile()));
            logger.info("Settings loaded from {}", SETTINGS_FILE);
        } catch (IOException e) {
            logger.error("Failed to load settings from {}; keeping defaults", SETTINGS_FILE, e);
        }
    }

    // Getters
    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public float getMasterVolume() { return masterVolume; }
    public float getMusicVolume() { return musicVolume; }
    public boolean getMusicEnabled() { return musicEnabled; }

    // Player model getters
    public String getArmModelType() { return armModelType; }
    public boolean isSlimArms() { return "SLIM".equals(armModelType); }
    public String getSelectedHat() { return selectedHat; }
    
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
    public boolean getWaterShaderEnabled() { return waterShaderEnabled; }
    public boolean getCloudsEnabled() { return cloudsEnabled; }
    public boolean getGodRaysEnabled() { return godRaysEnabled; }
    public boolean getShadowsEnabled() { return shadowsEnabled; }
    public boolean getPlayerNameTagsEnabled() { return playerNameTagsEnabled; }
    public String getShadowQuality() { return shadowQuality; }
    public int getShadowDistance() { return shadowDistance; }
    public boolean getSmoothLightingEnabled() { return smoothLightingEnabled; }

    // Performance / advanced getters
    public int getRenderDistance() { return renderDistance; }
    public int getLodDistance() { return lodDistance; }
    public boolean getLodEnabled() { return lodEnabled; }
    public boolean isVsyncEnabled() { return vsyncEnabled; }

    // Multiplayer getters/setters
    public int getMultiplayerPort() { return multiplayerPort; }
    public void setMultiplayerPort(int port) { this.multiplayerPort = port; }
    public String getLastJoinHost() { return lastJoinHost; }
    public void setLastJoinHost(String host) { this.lastJoinHost = host == null ? "" : host; }
    public String getMultiplayerUsername() { return multiplayerUsername; }
    public void setMultiplayerUsername(String name) {
        this.multiplayerUsername = (name == null || name.isBlank()) ? "Player" : name;
    }
    
    // Setters
    public void setResolution(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }
    
    public void setMasterVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public void setMusicVolume(float volume) {
        this.musicVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public void setMusicEnabled(boolean enabled) {
        this.musicEnabled = enabled;
    }

    // Player model setters
    public void setArmModelType(String armModelType) {
        if ("REGULAR".equals(armModelType) || "SLIM".equals(armModelType)) {
            this.armModelType = armModelType;
        } else {
            logger.warn("Invalid arm model type '{}'; defaulting to REGULAR", armModelType);
            this.armModelType = "REGULAR";
        }
    }
    
    public void setSlimArms(boolean slim) {
        this.armModelType = slim ? "SLIM" : "REGULAR";
    }

    public void setSelectedHat(String hatId) {
        this.selectedHat = (hatId == null || hatId.isBlank()) ? "NONE" : hatId;
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

    public void setWaterShaderEnabled(boolean waterShaderEnabled) {
        this.waterShaderEnabled = waterShaderEnabled;
    }

    public void setCloudsEnabled(boolean cloudsEnabled) {
        this.cloudsEnabled = cloudsEnabled;
    }

    public void setGodRaysEnabled(boolean godRaysEnabled) {
        this.godRaysEnabled = godRaysEnabled;
    }

    public void setShadowsEnabled(boolean shadowsEnabled) {
        this.shadowsEnabled = shadowsEnabled;
    }

    public void setPlayerNameTagsEnabled(boolean playerNameTagsEnabled) {
        this.playerNameTagsEnabled = playerNameTagsEnabled;
    }

    /** Shadow map quality tier; invalid values fall back to MEDIUM. Read live by the shadow pass. */
    public void setShadowQuality(String quality) {
        if ("LOW".equals(quality) || "MEDIUM".equals(quality) || "HIGH".equals(quality)) {
            this.shadowQuality = quality;
        } else {
            logger.warn("Invalid shadow quality '{}'; defaulting to MEDIUM", quality);
            this.shadowQuality = "MEDIUM";
        }
    }

    /** Shadow reach in blocks, clamped to [MIN_SHADOW_DISTANCE, MAX_SHADOW_DISTANCE]. Applies live. */
    public void setShadowDistance(int value) {
        this.shadowDistance = Math.max(MIN_SHADOW_DISTANCE, Math.min(MAX_SHADOW_DISTANCE, value));
    }

    /**
     * Toggles baked smooth lighting (per-vertex AO + soft sky gradients). Pushes the
     * flag to the engine mesh sampler immediately; already-built chunk meshes keep
     * their old lighting until rebuilt (the settings menu triggers that rebuild).
     */
    public void setSmoothLightingEnabled(boolean value) {
        this.smoothLightingEnabled = value;
        com.openmason.engine.voxel.lighting.VertexLightSampler.setSmoothLightingEnabled(value);
    }

    public void setRenderDistance(int value) {
        this.renderDistance = Math.max(com.stonebreak.world.operations.WorldConfiguration.MIN_RENDER_DISTANCE,
                Math.min(com.stonebreak.world.operations.WorldConfiguration.MAX_RENDER_DISTANCE, value));
    }

    public void setLodDistance(int value) {
        this.lodDistance = Math.max(com.stonebreak.world.operations.WorldConfiguration.MIN_LOD_RANGE,
                Math.min(com.stonebreak.world.operations.WorldConfiguration.MAX_LOD_RANGE, value));
    }

    public void setLodEnabled(boolean value) {
        this.lodEnabled = value;
    }

    /**
     * Enables or disables VSync. Takes effect on the next call to
     * {@link com.stonebreak.core.Main#applyVsyncSetting()} (i.e. live).
     */
    public void setVsyncEnabled(boolean value) {
        this.vsyncEnabled = value;
    }

    /** Max FPS cap. {@link #MAX_MAX_FPS} means Unlimited (no cap). */
    public int getMaxFps() { return maxFps; }

    /** True when the Max FPS setting is at its Unlimited sentinel value. */
    public boolean isMaxFpsUnlimited() { return maxFps >= MAX_MAX_FPS; }

    /**
     * Sets the Max FPS cap, clamped to [{@link #MIN_MAX_FPS}, {@link #MAX_MAX_FPS}].
     * The value {@link #MAX_MAX_FPS} is treated as Unlimited. The manual frame
     * limiter picks up the new value on the next frame, so this applies live.
     */
    public void setMaxFps(int value) {
        this.maxFps = Math.max(MIN_MAX_FPS, Math.min(MAX_MAX_FPS, value));
    }

    public float getUiScale() { return uiScale; }

    public void setUiScale(float scale) {
        this.uiScale = Math.max(0.5f, Math.min(2.0f, scale));
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