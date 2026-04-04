package com.openmason.main.systems.menus.textureCreator.dialogs;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for creating a new texture with user-defined canvas dimensions.
 * Renders a compact, centered form with width/height inputs and a live canvas
 * aspect-ratio preview.
 */
public class NewTextureDialog {

    private static final Logger logger = LoggerFactory.getLogger(NewTextureDialog.class);

    // Dialog state
    private boolean isOpen = false;
    private TextureCreatorState.CanvasSize confirmedSelection = null;
    private boolean needsPositioning = false;

    // Input fields
    private final ImInt inputWidth = new ImInt(16);
    private final ImInt inputHeight = new ImInt(16);

    // Validation
    private static final int MIN_DIMENSION = 1;
    private static final int MAX_DIMENSION = 4096;

    // Layout
    private static final float DIALOG_WIDTH = 360.0f;
    private static final float DIALOG_HEIGHT = 280.0f;
    private static final float LABEL_COL = 70.0f;
    private static final float FIELD_WIDTH = 140.0f;
    private static final float PREVIEW_SIZE = 80.0f;

    public NewTextureDialog() {
        logger.debug("New texture dialog created");
    }

    /**
     * Show the dialog (opens modal popup).
     */
    public void show() {
        isOpen = true;
        confirmedSelection = null;
        needsPositioning = true;
        inputWidth.set(16);
        inputHeight.set(16);
        logger.debug("New texture dialog opened");
    }

    /**
     * Check if dialog is currently open.
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Get confirmed canvas size selection.
     * Returns the selected size if user clicked "Create", null otherwise.
     * Resets to null after being read.
     */
    public TextureCreatorState.CanvasSize getSelectedCanvasSize() {
        TextureCreatorState.CanvasSize result = confirmedSelection;
        confirmedSelection = null;
        return result;
    }

    /**
     * Render the dialog.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        if (needsPositioning) {
            ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
            ImGui.setNextWindowPos(
                    ImGui.getMainViewport().getSizeX() / 2.0f - DIALOG_WIDTH / 2.0f,
                    ImGui.getMainViewport().getSizeY() / 2.0f - DIALOG_HEIGHT / 2.0f
            );
            needsPositioning = false;
        }

        int flags = ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoScrollbar;
        if (ImGui.beginPopupModal("New Texture", flags)) {

            float winW = ImGui.getWindowWidth();

            // ── Header ──────────────────────────────────────────────
            ImGui.spacing();
            renderCenteredText("New Texture", winW, true);
            ImGui.dummy(0, 2);
            renderCenteredText("Set the canvas dimensions for your new texture.", winW, false);
            ImGui.dummy(0, 6);

            renderAccentSeparator(winW);
            ImGui.dummy(0, 8);

            // ── Form + Preview side by side ─────────────────────────
            float formWidth = LABEL_COL + FIELD_WIDTH + 16;
            float totalContentWidth = formWidth + 20 + PREVIEW_SIZE;
            float startX = (winW - totalContentWidth) / 2.0f;

            // Form fields
            ImGui.setCursorPosX(startX);
            ImGui.beginGroup();

            ImGui.textDisabled("Width");
            ImGui.sameLine(LABEL_COL);
            ImGui.pushItemWidth(FIELD_WIDTH);
            ImGui.inputInt("##tex_w", inputWidth, 1, 16);
            ImGui.popItemWidth();

            ImGui.dummy(0, 4);

            ImGui.textDisabled("Height");
            ImGui.sameLine(LABEL_COL);
            ImGui.pushItemWidth(FIELD_WIDTH);
            ImGui.inputInt("##tex_h", inputHeight, 1, 16);
            ImGui.popItemWidth();

            ImGui.dummy(0, 8);

            // Pixel count info
            int w = clampDimension(inputWidth.get());
            int h = clampDimension(inputHeight.get());
            inputWidth.set(w);
            inputHeight.set(h);

            String info = w + " x " + h + "  (" + (w * h) + " px)";
            ImGui.textDisabled(info);

            ImGui.endGroup();

            // Preview
            ImGui.sameLine(0, 20);
            ImGui.beginGroup();
            renderCanvasPreview(w, h);
            ImGui.endGroup();

            ImGui.dummy(0, 8);
            renderAccentSeparator(winW);
            ImGui.dummy(0, 6);

            // ── Buttons ─────────────────────────────────────────────
            float btnW = 100.0f;
            float btnH = 26.0f;
            float btnSpacing = 10.0f;
            float totalBtnW = btnW * 2 + btnSpacing;
            float btnStartX = (winW - totalBtnW) / 2.0f;

            ImGui.setCursorPosX(btnStartX);

            // Create button — accent-styled
            ImVec4 accent = getAccentColor();
            ImGui.pushStyleColor(ImGuiCol.Button,
                    accent.x, accent.y, accent.z, 0.20f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    accent.x, accent.y, accent.z, 0.35f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                    accent.x, accent.y, accent.z, 0.50f);
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 6.0f);

            if (ImGui.button("Create", btnW, btnH)) {
                confirmedSelection = new TextureCreatorState.CanvasSize(w, h);
                isOpen = false;
                ImGui.closeCurrentPopup();
                logger.info("Created new texture: {}x{}", w, h);
            }

            ImGui.popStyleVar();
            ImGui.popStyleColor(3);

            ImGui.sameLine(0, btnSpacing);

            // Cancel button — subtle
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 6.0f);
            if (ImGui.button("Cancel", btnW, btnH)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }
            ImGui.popStyleVar();

            // ESC to close
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }

        if (isOpen && !ImGui.isPopupOpen("New Texture")) {
            ImGui.openPopup("New Texture");
        }
    }

    // ========================================================================
    // Rendering helpers
    // ========================================================================

    /**
     * Renders a live aspect-ratio preview of the canvas dimensions.
     */
    private void renderCanvasPreview(int w, int h) {
        ImDrawList dl = ImGui.getWindowDrawList();
        ImVec2 pos = ImGui.getCursorScreenPos();

        // Fit into PREVIEW_SIZE box while preserving aspect ratio
        float scale = Math.min(PREVIEW_SIZE / w, PREVIEW_SIZE / h);
        float pw = w * scale;
        float ph = h * scale;

        // Center within the preview area
        float ox = pos.x + (PREVIEW_SIZE - pw) / 2.0f;
        float oy = pos.y + (PREVIEW_SIZE - ph) / 2.0f;

        // Checkerboard background
        ImVec4 frameBg = ImGui.getStyle().getColor(ImGuiCol.FrameBg);
        int checker1 = ImColor.rgba(frameBg.x + 0.05f, frameBg.y + 0.05f, frameBg.z + 0.05f, 1.0f);
        int checker2 = ImColor.rgba(frameBg.x + 0.12f, frameBg.y + 0.12f, frameBg.z + 0.12f, 1.0f);

        float checkSize = 6.0f;
        for (float cy = oy; cy < oy + ph; cy += checkSize) {
            for (float cx = ox; cx < ox + pw; cx += checkSize) {
                int col = ((int) ((cx - ox) / checkSize) + (int) ((cy - oy) / checkSize)) % 2 == 0
                        ? checker1 : checker2;
                dl.addRectFilled(cx, cy,
                        Math.min(cx + checkSize, ox + pw),
                        Math.min(cy + checkSize, oy + ph), col);
            }
        }

        // Border
        ImVec4 accent = getAccentColor();
        dl.addRect(ox, oy, ox + pw, oy + ph,
                ImColor.rgba(accent.x, accent.y, accent.z, 0.5f), 0, 0, 1.0f);

        // Dimension label below
        ImGui.dummy(PREVIEW_SIZE, PREVIEW_SIZE);
        String dimLabel = w + "x" + h;
        ImVec2 labelSize = ImGui.calcTextSize(dimLabel);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (PREVIEW_SIZE - labelSize.x) / 2.0f);
        ImGui.textDisabled(dimLabel);
    }

    /**
     * Renders text centered within the given width.
     */
    private void renderCenteredText(String text, float availWidth, boolean bold) {
        ImVec2 size = ImGui.calcTextSize(text);
        float x = (availWidth - size.x) / 2.0f;
        ImGui.setCursorPosX(x);

        if (bold) {
            // Bold via double-pass offset
            ImVec2 pos = ImGui.getCursorScreenPos();
            ImGui.text(text);
            ImGui.getWindowDrawList().addText(pos.x + 0.5f, pos.y,
                    ImGui.getColorU32(ImGuiCol.Text), text);
        } else {
            ImGui.textDisabled(text);
        }
    }

    /**
     * Renders a subtle accent-colored separator line with gradient fade.
     */
    private void renderAccentSeparator(float windowWidth) {
        ImDrawList dl = ImGui.getWindowDrawList();
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImVec4 accent = getAccentColor();

        float left = pos.x;
        float right = pos.x + windowWidth - ImGui.getStyle().getWindowPaddingX() * 2;
        float center = (left + right) / 2.0f;
        float y = pos.y;

        int bright = ImColor.rgba(accent.x, accent.y, accent.z, 0.30f);
        int fade = ImColor.rgba(accent.x, accent.y, accent.z, 0.0f);

        dl.addRectFilledMultiColor(left, y, center, y + 1, fade, bright, bright, fade);
        dl.addRectFilledMultiColor(center, y, right, y + 1, bright, fade, fade, bright);

        ImGui.dummy(0, 1);
    }

    /**
     * Get the accent color from the current theme.
     */
    private ImVec4 getAccentColor() {
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        if (accent == null || (accent.x == 0 && accent.y == 0 && accent.z == 0)) {
            accent = new ImVec4(0.36f, 0.61f, 0.84f, 1.0f);
        }
        return accent;
    }

    /**
     * Clamps a dimension value to valid range.
     */
    private int clampDimension(int value) {
        return Math.max(MIN_DIMENSION, Math.min(MAX_DIMENSION, value));
    }

    /**
     * Close the dialog.
     */
    public void close() {
        isOpen = false;
        confirmedSelection = null;
    }
}
