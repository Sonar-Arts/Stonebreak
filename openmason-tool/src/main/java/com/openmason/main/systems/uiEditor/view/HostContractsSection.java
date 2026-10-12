package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import imgui.ImGui;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The document's host contracts and draw providers in the Details panel (#297): what it declares,
 * a remove button per row, and an add picker over what the game host implements (at the host's
 * newest version). A document reads host data and calls host actions only under declared contracts.
 */
final class HostContractsSection {

    private static UiHostProfile gameProfile;

    private HostContractsSection() {
    }

    static void draw(UiEditorContext ctx, UiManifest m) {
        if (m.hostApis().isEmpty() && m.providers().isEmpty()) {
            ImGui.textDisabled("None declared");
        }
        rows(ctx, m.hostApis(), false);
        rows(ctx, m.providers(), true);
        UiHostProfile game = gameProfile();
        if (game != null) {
            picker(ctx, "+ host API##addHostApi", game.hostApis(), m.hostApis(), false);
            ImGui.sameLine();
            picker(ctx, "+ provider##addProvider", game.providers(), m.providers(), true);
        }
    }

    private static void rows(UiEditorContext ctx, List<UiManifest.HostRequirement> list, boolean provider) {
        for (UiManifest.HostRequirement h : list) {
            if (ImGui.smallButton("x##host" + h.id())) {
                ctx.actions.run(DocumentCommands.setHostRequirement(provider, h.id(), null, false));
            }
            ImGui.sameLine();
            ImGui.text(h.id() + " v" + h.version() + (h.optional() ? " (optional)" : "")
                + (provider ? " (provider)" : ""));
        }
    }

    private static void picker(UiEditorContext ctx, String label, Map<String, Integer> offered,
                               List<UiManifest.HostRequirement> declared, boolean provider) {
        Map<String, Integer> open = new TreeMap<>(offered);
        declared.forEach(h -> open.remove(h.id()));
        if (open.isEmpty()) {
            return;
        }
        if (ImGui.button(label)) {
            ImGui.openPopup(label);
        }
        if (ImGui.beginPopup(label)) {
            open.forEach((id, version) -> {
                if (ImGui.selectable(id + " v" + version)) {
                    ctx.actions.run(DocumentCommands.setHostRequirement(provider, id, version, false));
                }
            });
            ImGui.endPopup();
        }
    }

    /** The game host's contracts, built once (headless: no game is running in the editor). */
    private static UiHostProfile gameProfile() {
        if (gameProfile == null) {
            try {
                gameProfile = com.stonebreak.ui.runtime.GameUiHost.declaredProfile();
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }
        return gameProfile;
    }
}
