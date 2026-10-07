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
    /** ImGui font atlas size (px) — the body size of all ImGui text at density 1.0. */
    public static final float FONT_SIZE = 16.0f;
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

        ToolInputTap.install(window); // first, so ImGui's backend chains to it
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

    /** A secondary window's pixels: RGBA rows, bottom row first (GL order). */
    public record ViewportPixels(String title, int width, int height, java.nio.ByteBuffer rgba) {
    }

    /**
     * Re-draws every secondary platform window (popped-out workspaces, editors, floating panels)
     * from this frame's draw data into an offscreen framebuffer on the current (main) context and
     * reads it back. Their own back buffers are already swapped by now, and textures are shared
     * between the contexts, so this yields what each window shows without touching the desktop.
     * Call after {@link #handleMultiViewport()}, with the main window's context current.
     */
    public java.util.List<ViewportPixels> captureSecondaryViewports() {
        java.util.List<ViewportPixels> out = new java.util.ArrayList<>();
        if (!ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable)) {
            return out;
        }
        imgui.ImGuiPlatformIO pio = ImGui.getPlatformIO();
        int mainId = ImGui.getMainViewport().getID();
        for (int i = 0; i < pio.getViewportsSize(); i++) {
            imgui.ImGuiViewport vp = pio.getViewports(i);
            imgui.ImDrawData dd = vp.getID() == mainId ? null : vp.getDrawData();
            if (dd == null || dd.getCmdListsCount() == 0) {
                continue;
            }
            int w = Math.round(dd.getDisplaySizeX() * dd.getFramebufferScaleX());
            int h = Math.round(dd.getDisplaySizeY() * dd.getFramebufferScaleY());
            if (w <= 0 || h <= 0) {
                continue;
            }
            String title = null;
            long handle = vp.getPlatformHandle();
            if (handle != 0) {
                try {
                    title = org.lwjgl.glfw.GLFW.glfwGetWindowTitle(handle);
                } catch (RuntimeException ignored) {
                    // no title on this platform; the caller falls back to the viewport id
                }
            }
            if (title == null || title.isBlank()) {
                title = String.format("viewport-%08X", vp.getID());
            }
            out.add(new ViewportPixels(title, w, h, renderOffscreen(dd, w, h)));
        }
        return out;
    }

    private java.nio.ByteBuffer renderOffscreen(imgui.ImDrawData dd, int w, int h) {
        int previousFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
        int fbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
        int rbo = org.lwjgl.opengl.GL30.glGenRenderbuffers();
        try {
            org.lwjgl.opengl.GL30.glBindRenderbuffer(org.lwjgl.opengl.GL30.GL_RENDERBUFFER, rbo);
            org.lwjgl.opengl.GL30.glRenderbufferStorage(org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                    org.lwjgl.opengl.GL11.GL_RGBA8, w, h);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fbo);
            org.lwjgl.opengl.GL30.glFramebufferRenderbuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL30.GL_RENDERBUFFER, rbo);
            org.lwjgl.opengl.GL11.glViewport(0, 0, w, h);
            org.lwjgl.opengl.GL11.glClearColor(0f, 0f, 0f, 1f);
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
            imGuiGl3.renderDrawData(dd);
            java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL11.GL_RGBA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
            return px;
        } finally {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, previousFbo);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
            org.lwjgl.opengl.GL30.glDeleteRenderbuffers(rbo);
        }
    }

    /** Shut down both backends and destroy the ImGui context. */
    public void shutdown() {
        imGuiGl3.shutdown();
        imGuiGlfw.shutdown();
        ImGui.destroyContext();
    }
}
