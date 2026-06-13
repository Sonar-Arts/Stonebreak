package com.openmason.main.systems.menus.mainHub.components;

import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.services.TemplateService;
import com.openmason.main.systems.menus.mainHub.state.HubState;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.parts.MortarCard;
import com.openmason.main.systems.mortar.parts.MortarSectionLabel;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

import java.util.List;

/**
 * Templates grid (the secondary section on the Home landing), painted as a
 * single Skija {@link MortarRegion} of compact {@link MortarCard}s with a
 * responsive column count. Selecting a card sets the hub's selected template,
 * which opens the contextual preview.
 */
public class TemplatesPanel {

    private static final float MIN_CARD_W = 200f;
    private static final float CARD_H = 122f;
    private static final float GAP = 12f;
    private static final float LABEL_H = 18f;
    private static final float LABEL_GAP = 10f;

    private final HubState hubState;
    private final TemplateService templateService;
    private final MortarRegion region = new MortarRegion();

    private List<ProjectTemplate> frameTemplates = List.of();

    public TemplatesPanel(ThemeManager themeManager, HubState hubState, TemplateService templateService) {
        this.hubState = hubState;
        this.templateService = templateService;
    }

    public void render() {
        String query = hubState.getSearchQuery();
        List<ProjectTemplate> templates = query.isEmpty()
                ? templateService.getAllTemplates()
                : templateService.search(query);
        this.frameTemplates = templates;

        if (templates.isEmpty()) {
            renderEmptyState(query);
            return;
        }

        float availW = ImGui.getContentRegionAvailX();
        int cols = Math.max(1, (int) ((availW + GAP) / (MIN_CARD_W + GAP)));
        float cardW = (availW - GAP * (cols - 1)) / cols;
        int rows = (templates.size() + cols - 1) / cols;

        float gridTop = LABEL_H + LABEL_GAP;
        float height = gridTop + rows * (CARD_H + GAP) - GAP;

        region.begin(availW, height);
        region.add("label", 0f, 0f, availW, LABEL_H, new MortarSectionLabel("Templates"));

        for (int i = 0; i < templates.size(); i++) {
            ProjectTemplate t = templates.get(i);
            int col = i % cols;
            int row = i / cols;
            float x = col * (cardW + GAP);
            float y = gridTop + row * (CARD_H + GAP);
            boolean selected = t.equals(hubState.getSelectedTemplate());
            // Title wraps (no truncation); description is the "simple" wrapped
            // blurb, category sits in the footer. Full description is in preview.
            MortarCard card = new MortarCard(t.getName(), t.getDescription(), t.getCategory());
            region.add("template." + i, x, y, cardW, CARD_H, selected, card);
        }

        MortarFrameResult input = region.render();
        ProjectTemplate clicked = resolve(input.clicked());
        if (clicked != null) {
            if (clicked.equals(hubState.getSelectedTemplate())) {
                hubState.setSelectedTemplate(null); // toggle off
            } else {
                hubState.setSelectedRecentProject(null);
                hubState.setSelectedTemplate(clicked);
            }
        }
    }

    private ProjectTemplate resolve(String partId) {
        if (partId == null || !partId.startsWith("template.")) {
            return null;
        }
        try {
            int index = Integer.parseInt(partId.substring("template.".length()));
            if (index >= 0 && index < frameTemplates.size()) {
                return frameTemplates.get(index);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private void renderEmptyState(String query) {
        ImGui.dummy(0, 6f);
        String message = query.isEmpty() ? "No templates available"
                : "No templates match your search";
        float w = ImGui.getContentRegionAvailX();
        float tw = ImGui.calcTextSize(message).x;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0f, (w - tw) / 2f));
        var c = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.Text, c.x, c.y, c.z, 0.6f);
        ImGui.text(message);
        ImGui.popStyleColor();
    }

    public void update(float deltaTime) {
        region.update(deltaTime);
    }

    public void dispose() {
        region.close();
    }
}
