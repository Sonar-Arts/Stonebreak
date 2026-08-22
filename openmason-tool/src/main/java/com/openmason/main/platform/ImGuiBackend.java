package com.openmason.main.platform;

import com.openmason.main.systems.services.drop.ViewportDropCallbackManager;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;

import java.io.File;

import static org.lwjgl.glfw.GLFW.glfwGetCurrentContext;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;

/**
 * ImGui context plus its GLFW/OpenGL3 backends: creation and IO configuration, font loading,
 * per-frame begin/end, multi-viewport platform-window updates, and shutdown.
 */
public final class ImGuiBackend {

    private static final String FONT_PATH = "openmason-tool/src/main/resources/masonFonts/";
    private static final float FONT_SIZE = 16.0f;
    private static final String INI_FILE_PATH = "openmason-tool/imgui.ini";

    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    /**
     * Initialize ImGui context and rendering backend.
     */
    public void initialize(long window) {
        ImGui.createContext();

        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(INI_FILE_PATH);
        io.setConfigWindowsMoveFromTitleBarOnly(true);
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);

        loadFonts(io);

        imGuiGlfw.init(window, true);
        imGuiGl3.init("#version 330 core");

        // In imgui-java 1.87+, OpenGL device objects are lazily created on the first
        // newFrame() call rather than during init(). Trigger creation before main loop.
        imGuiGl3.newFrame();
    }

    /**
     * Load JetBrains Mono fonts (Regular, Bold, Medium).
     * Fails fast if fonts are not found - no fallback to defaults.
     */
    private void loadFonts(ImGuiIO io) {
        String[] fontVariants = {"JetBrainsMono-Regular.ttf", "JetBrainsMono-Bold.ttf", "JetBrainsMono-Medium.ttf"};

        for (String fontFile : fontVariants) {
            File font = new File(FONT_PATH + fontFile);
            if (!font.exists()) {
                throw new IllegalStateException("Required font not found: " + font.getAbsolutePath());
            }
            io.getFonts().addFontFromFileTTF(font.getPath(), FONT_SIZE);
        }

        io.getFonts().build();
    }

    /** Start a new ImGui frame (backend + core). */
    public void beginFrame() {
        imGuiGlfw.newFrame();
        ImGui.newFrame();
    }

    /** Finalize the ImGui frame and draw it into the current GL context. */
    public void endFrame() {
        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    /** Update and render detached platform windows when multi-viewport is enabled. */
    public void handleMultiViewport() {
        if (ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            long backupContext = glfwGetCurrentContext();
            ImGui.updatePlatformWindows();

            // Register drop callbacks on any new platform windows (for floating ImGui windows)
            ViewportDropCallbackManager.updateDropCallbacks();

            ImGui.renderPlatformWindowsDefault();
            glfwMakeContextCurrent(backupContext);
        }
    }

    /** Shut down both backends and destroy the ImGui context. */
    public void shutdown() {
        imGuiGl3.shutdown();
        imGuiGlfw.shutdown();
        ImGui.destroyContext();
    }
}
