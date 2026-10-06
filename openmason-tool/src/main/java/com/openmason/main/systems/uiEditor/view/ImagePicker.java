package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiSpriteRef;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiImageAssets;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;
import imgui.ImGui;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inspector's image field (#294) for {@code background-image} and {@code Image.source}:
 * free text plus a picker of the document's textures and every sprite and skin of its sprite
 * sheets ({@code sheet#name}), project textures and sheets to add, and buttons that open the
 * texture in the Texture Editor or the sheet in the Sprites panel.
 */
final class ImagePicker {

    private final UiEditorContext ctx;
    private Object choicesKey;
    private List<UiImageAssets.ImageChoice> choices = List.of();
    private long scannedAt;
    private List<UiImageAssets.ProjectImage> projectImages = List.of();

    ImagePicker(UiEditorContext ctx) {
        this.ctx = ctx;
    }

    /** The field; a pick that needs a new dependency row adds it first (its own undo step). */
    ValueFields.Result field(String id, UiValue authored, UiValue effective, float width) {
        String current = authored instanceof UiValue.Str s && authored != ValueFields.MIXED ? s.value() : null;
        String shown = current != null ? current : effective instanceof UiValue.Str e ? e.value() : "";
        float bw = ImGui.getFrameHeight();
        ValueFields.Result out = ValueFields.text(id, current, shown, width - bw - 2,
            v -> v.isBlank() ? null : UiValue.of(v.trim()));
        ImGui.sameLine(0, 2);
        if (ImGui.button("...##img" + id, bw, 0)) {
            ImGui.openPopup("##imgs" + id);
            choicesKey = null; // refresh on open
        }
        if (ImGui.beginPopup("##imgs" + id)) {
            ValueFields.Result picked = popup(current);
            if (picked != null) {
                out = picked;
            }
            ImGui.endPopup();
        }
        String ref = current != null ? current : shown;
        if (ref != null && !ref.isBlank() && !"none".equals(ref) && !ref.startsWith("var(")) {
            buttons(ref);
        }
        return out;
    }

    private void buttons(String ref) {
        if (ImGui.smallButton("Edit Texture##" + ref)) {
            ctx.editTexture(ref);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Open the texture in the Texture Editor; saving updates every document using it");
        }
        UiSpriteRef sprite = UiSpriteRef.parse(ref);
        if (sprite != null) {
            ImGui.sameLine();
            if (ImGui.smallButton("Edit Sprites##" + ref)) {
                ctx.editSheetRequest = ref; // opens the sheet with this sprite or skin selected
            }
        } else if (isTextureRow(ref)) {
            ImGui.sameLine();
            if (ImGui.smallButton("Slice into Sprites##" + ref)) {
                newSheet(ref);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Create a sprite sheet for this texture (regions, nine-slice, skins, frames)");
            }
        }
    }

    private boolean isTextureRow(String id) {
        UiEditorDocument d = ctx.doc();
        UiDependency row = d == null ? null : d.archive().dependencies().find(id);
        return row != null && (row.kind() == UiDependency.Kind.TEXTURE || row.kind() == UiDependency.Kind.IMAGE);
    }

    private ValueFields.Result popup(String current) {
        UiEditorDocument d = ctx.doc();
        if (d == null) {
            return null;
        }
        Object key = List.of(d.archive().dependencies(), ctx.assetEpoch());
        if (!key.equals(choicesKey)) {
            choicesKey = key;
            choices = UiImageAssets.choices(d.archive(), ctx.project.sources());
        }
        ValueFields.Result out = null;
        if (ImGui.selectable("none", "none".equals(current))) {
            out = pick("none");
        }
        Map<String, List<UiImageAssets.ImageChoice>> groups = new LinkedHashMap<>();
        for (UiImageAssets.ImageChoice c : choices) {
            groups.computeIfAbsent(c.group(), g -> new java.util.ArrayList<>()).add(c);
        }
        if (groups.isEmpty()) {
            ImGui.textDisabled("No textures or sprite sheets yet");
        }
        for (Map.Entry<String, List<UiImageAssets.ImageChoice>> g : groups.entrySet()) {
            if ("Textures".equals(g.getKey())) {
                ImGui.separatorText("Textures");
                for (UiImageAssets.ImageChoice c : g.getValue()) {
                    if (ImGui.selectable(c.label(), c.ref().equals(current))) {
                        out = pick(c.ref());
                    }
                }
            } else if (ImGui.beginMenu(g.getKey() + " (sprites)")) {
                for (UiImageAssets.ImageChoice c : g.getValue()) {
                    if (ImGui.menuItem(c.label() + (c.skin() ? "  [skin]" : ""), "", c.ref().equals(current))) {
                        out = pick(c.ref());
                    }
                }
                ImGui.endMenu();
            }
        }
        ImGui.separator();
        if (ImGui.beginMenu("Add from Project")) {
            long now = System.currentTimeMillis();
            if (now - scannedAt > 3000) {
                scannedAt = now;
                projectImages = UiImageAssets.projectImages(ctx.project);
            }
            if (projectImages.isEmpty()) {
                ImGui.textDisabled(ctx.project.root() == null ? "No project open" : "No .omt/.sbt/.png/.sprites.json files");
            }
            for (UiImageAssets.ProjectImage p : projectImages) {
                String kind = p.kind() == UiDependency.Kind.SPRITES ? "sheet" : p.kind().wire();
                if (ImGui.menuItem(p.relative() + "  [" + kind + "]")) {
                    out = add(p);
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Adds it as shared dependency " + p.id());
                }
            }
            ImGui.endMenu();
        }
        return out;
    }

    private ValueFields.Result add(UiImageAssets.ProjectImage p) {
        try {
            if (p.kind() == UiDependency.Kind.SPRITES) {
                if (ctx.actions.run(UiImageAssets.addSheet(ctx.project, p))) {
                    ctx.editSheetRequest = p.id();
                    ctx.doc().setLastMessage("Added sprite sheet " + p.id() + "; pick one of its sprites");
                }
                return null;
            }
            return ctx.actions.run(UiImageAssets.addImage(ctx.project, p)) ? pick(p.id()) : null;
        } catch (IOException | UiCommandException e) {
            ctx.doc().setLastMessage(e.getMessage());
            return null;
        }
    }

    private void newSheet(String textureId) {
        UiEditorDocument d = ctx.doc();
        try {
            if (ctx.actions.run(UiImageAssets.newSheet(d.archive(), ctx.project, textureId))) {
                ctx.editSheetRequest = textureId + "_sprites";
            }
        } catch (IOException | UiCommandException e) {
            d.setLastMessage(e.getMessage());
        }
    }

    private static ValueFields.Result pick(String ref) {
        return new ValueFields.Result(true, UiValue.of(ref), true);
    }
}
