package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.palette.PaletteLibrary;
import com.openmason.main.systems.menus.textureCreator.palette.PaletteSwatchGrid;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Saved Palettes section of the color panel: a palette selector combo with
 * square create/delete buttons, plus the active palette's swatch grid.
 * Create and delete use inline rows (text field / confirm buttons appearing
 * under the selector) rather than popups — deterministic inside the chrome
 * column and keeps keyboard focus local. Swatch editing (left-click pick,
 * right-click replace/remove, "+" add current color) comes from the shared
 * {@link PaletteSwatchGrid}.
 */
public final class PaletteSectionView {

    private static final float SWATCH_SIZE = 24.0f;

    private enum RowMode {
        NORMAL,
        CREATING,
        CONFIRM_DELETE
    }

    private final PaletteLibrary library;
    private final PaletteSwatchGrid grid = new PaletteSwatchGrid(SWATCH_SIZE);
    private final ImString newPaletteName = new ImString(64);

    private RowMode mode = RowMode.NORMAL;
    private boolean focusNameField = false;
    private String createError = null;

    public PaletteSectionView(PaletteLibrary library) {
        this.library = library;
    }

    public void render(IntSupplier currentColorSupplier, IntConsumer onSwatchPicked) {
        switch (mode) {
            case CREATING -> renderCreateRow();
            case CONFIRM_DELETE -> renderDeleteRow();
            default -> renderSelectorRow();
        }
        ImGui.spacing();
        grid.render(library.getActiveModel(), currentColorSupplier, onSwatchPicked);
    }

    /** Normal state: [combo][+][−] on one row. */
    private void renderSelectorRow() {
        float buttonSize = ImGui.getFrameHeight(); // square, matches combo height
        float spacing = 4f;

        ImGui.pushItemWidth(Math.max(80f, ImGui.getContentRegionAvailX() - 2 * (buttonSize + spacing)));
        if (ImGui.beginCombo("##palette_select", library.getActiveName())) {
            for (String name : library.getNames()) {
                boolean selected = name.equals(library.getActiveName());
                if (ImGui.selectable(name, selected)) {
                    library.switchTo(name);
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();

        ImGui.sameLine(0, spacing);
        if (ImGui.button("+##pal_new", buttonSize, buttonSize)) {
            newPaletteName.set("");
            createError = null;
            focusNameField = true;
            mode = RowMode.CREATING;
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("New palette");
        }

        ImGui.sameLine(0, spacing);
        boolean canDelete = library.size() > 1;
        if (!canDelete) {
            ImGui.beginDisabled();
        }
        if (ImGui.button("-##pal_delete", buttonSize, buttonSize)) {
            mode = RowMode.CONFIRM_DELETE;
        }
        if (!canDelete) {
            ImGui.endDisabled();
        } else if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Delete palette \"" + library.getActiveName() + "\"");
        }
    }

    /** Create state: [name field][Create][Cancel]; Enter submits, Esc cancels. */
    private void renderCreateRow() {
        float createWidth = ImGui.calcTextSize("Create").x + 16f;
        float cancelWidth = ImGui.calcTextSize("Cancel").x + 16f;
        float spacing = 4f;

        if (focusNameField) {
            ImGui.setKeyboardFocusHere();
            focusNameField = false;
        }
        ImGui.pushItemWidth(Math.max(80f,
                ImGui.getContentRegionAvailX() - createWidth - cancelWidth - 2 * spacing));
        // No EnterReturnsTrue: with that flag the binding only copies the
        // native edit buffer into the ImString when the widget returns true,
        // so reading the text on a Create-button click would see stale/empty
        // content. A plain input syncs on every keystroke; Enter is detected
        // via item deactivation + key state instead.
        ImGui.inputTextWithHint("##pal_name", "Palette name", newPaletteName);
        boolean submitted = ImGui.isItemDeactivated()
                && (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter)
                || ImGui.isKeyPressed(imgui.flag.ImGuiKey.KeypadEnter));
        ImGui.popItemWidth();

        ImGui.sameLine(0, spacing);
        submitted |= ImGui.button("Create##pal_create");

        ImGui.sameLine(0, spacing);
        boolean cancelled = ImGui.button("Cancel##pal_create_cancel")
                || ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape);

        if (submitted) {
            String name = newPaletteName.get().trim();
            if (name.isBlank()) {
                createError = "Enter a palette name";
                focusNameField = true;
            } else if (library.getNames().contains(name)) {
                createError = "\"" + name + "\" already exists";
                focusNameField = true;
            } else if (library.createPalette(name)) {
                createError = null;
                mode = RowMode.NORMAL;
            }
        }
        if (cancelled) {
            createError = null;
            mode = RowMode.NORMAL;
        }

        if (createError != null) {
            ImGui.textColored(0.95f, 0.45f, 0.45f, 1.0f, createError);
        }
    }

    /** Delete state: confirmation inline. */
    private void renderDeleteRow() {
        ImGui.textDisabled("Delete \"" + library.getActiveName() + "\"?");
        ImGui.sameLine(0, 8);
        if (ImGui.button("Delete##pal_delete_yes")) {
            library.deleteActivePalette();
            mode = RowMode.NORMAL;
        }
        ImGui.sameLine(0, 4);
        if (ImGui.button("Cancel##pal_delete_no") || ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) {
            mode = RowMode.NORMAL;
        }
    }
}
