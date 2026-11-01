package com.stonebreak.openmason;

import com.openmason.api.prefs.PreferencePage;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.type.ImString;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.lwjgl.util.nfd.NativeFileDialog.NFD_CANCEL;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_FreePath;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_GetError;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY;
import static org.lwjgl.util.nfd.NativeFileDialog.NFD_PickFolder;

/**
 * Preferences > Stonebreak: the Stonebreak checkout the target reads and writes
 * ({@link StonebreakResources}). Strings stay ASCII (the editor's ImGui font has no em-dash).
 */
final class StonebreakPreferencePage implements PreferencePage {

    private static final Logger logger = LoggerFactory.getLogger(StonebreakPreferencePage.class);

    static final String ID = "stonebreak";

    private final StonebreakResources resources;
    private final ImString folder = new ImString(1024);
    private String applyError;

    StonebreakPreferencePage(StonebreakResources resources) {
        this.resources = resources;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Stonebreak";
    }

    @Override
    public int order() {
        return 650; // after Common (600), before Plugins (700)
    }

    @Override
    public void opened() {
        Path saved = resources.saved();
        folder.set(saved == null ? "" : saved.toString());
        applyError = null;
    }

    @Override
    public void render() {
        ThemedWidgets.sectionLabel("Stonebreak checkout");
        ImGui.textWrapped("The Stonebreak source checkout Open Mason works on. Its "
                + "stonebreak-game/src/main/resources folder is where assets are read from, where exports "
                + "and UI deploys are written, and what new numeric ids are checked against. Pick the "
                + "checkout's root, the stonebreak-game folder or the resources folder itself.");
        ImGui.spacing();

        float browseW = 90f;
        float clearW = 60f;
        float spacing = ImGui.getStyle().getItemSpacingX();
        ImGui.setNextItemWidth(Math.max(120f, ImGui.getContentRegionAvailX() - browseW - clearW - 2 * spacing));
        ImGui.inputTextWithHint("##stonebreakResources", "Not set: looked for from Open Mason's working folder", folder);
        ImGui.sameLine();
        if (ImGui.button("Browse...", browseW, 0f)) {
            pickFolder();
        }
        ImGui.sameLine();
        if (ImGui.button("Clear", clearW, 0f)) {
            folder.set("");
        }

        String typed = folder.get().trim();
        if (typed.isEmpty()) {
            ImGui.textDisabled("Not set.");
        } else {
            Path tree = StonebreakResources.normalize(typed);
            if (tree != null) {
                ThemedWidgets.statusTextWrapped(Tone.SUCCESS, "Stonebreak resources: " + tree);
            } else {
                ThemedWidgets.statusTextWrapped(Tone.ERROR, "Not a Stonebreak checkout: no sbo folder in it, "
                        + "its src/main/resources or its stonebreak-game/src/main/resources.");
            }
        }
        if (applyError != null) {
            ThemedWidgets.statusTextWrapped(Tone.ERROR, applyError);
        }

        ThemedWidgets.sectionLabel("In use");
        renderStatus(resources.status());
    }

    private void renderStatus(StonebreakResources.Status s) {
        switch (s.source()) {
            case COMMAND_LINE -> {
                ImGui.textWrapped(s.tree().toString());
                ThemedWidgets.statusTextWrapped(Tone.WARNING, "Set at launch with -D" + StonebreakResources.PROPERTY
                        + "; it overrides the folder above until Open Mason is started without it.");
            }
            case SETTING -> ImGui.textWrapped(s.tree() + "  (this setting)");
            case WORKING_DIRECTORY -> ImGui.textWrapped(s.tree() + "  (found from Open Mason's working folder)");
            case BUNDLED -> ThemedWidgets.statusTextWrapped(Tone.WARNING, "No Stonebreak checkout. Assets are read "
                    + "from the copy bundled with the plugin (read-only, as of the plugin's build): exports go to "
                    + "the project folder, UI deploy is unavailable and numeric ids are checked against the "
                    + "bundled copy.");
        }
    }

    @Override
    public void apply() {
        String typed = folder.get().trim();
        Path tree = typed.isEmpty() ? null : StonebreakResources.normalize(typed);
        if (!typed.isEmpty() && tree == null) {
            applyError = "Not applied: that folder is not a Stonebreak checkout.";
            return;
        }
        try {
            resources.save(tree);
            folder.set(tree == null ? "" : tree.toString());
            applyError = null;
        } catch (IOException e) {
            logger.error("[stonebreak] Cannot save the Stonebreak resources folder", e);
            applyError = "Not saved: " + e.getMessage();
        }
    }

    @Override
    public void cancel() {
        opened();
    }

    @Override
    public void resetToDefaults() {
        folder.set("");
        applyError = null;
    }

    /** The native folder picker (Open Mason initialised NFD at start-up for its own dialogs). */
    private void pickFolder() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.mallocPointer(1);
            String start = folder.get().trim();
            int result = NFD_PickFolder(out, start.isEmpty() ? null : start);
            if (result == NFD_OKAY) {
                String chosen = out.getStringUTF8(0);
                NFD_FreePath(out.get(0));
                Path tree = StonebreakResources.normalize(chosen);
                folder.set(tree != null ? tree.toString() : chosen);
            } else if (result != NFD_CANCEL) {
                applyError = "Folder picker failed: " + NFD_GetError() + ". Type the path instead.";
            }
        } catch (RuntimeException | LinkageError e) {
            logger.error("[stonebreak] Folder picker failed", e);
            applyError = "Folder picker unavailable. Type the path instead.";
        }
    }
}
