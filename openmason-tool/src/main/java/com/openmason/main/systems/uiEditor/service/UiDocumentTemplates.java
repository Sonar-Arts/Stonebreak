package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.main.systems.uiEditor.document.Nodes;

import java.util.List;
import java.util.Map;

import static com.openmason.main.systems.uiEditor.document.UiTree.map;

/**
 * Starting points for new documents. Each is a valid document the runtime can lay out and
 * paint immediately; nothing here is special-cased by the editor afterwards.
 */
public enum UiDocumentTemplates {

    BLANK_SCREEN("Blank Screen", "A full-window root, centred", UiManifest.DocumentKind.SCREEN),
    MENU_SCREEN("Menu Screen", "Stone panel with a title and three buttons", UiManifest.DocumentKind.SCREEN),
    BLANK_COMPONENT("Blank Component", "An empty reusable component", UiManifest.DocumentKind.COMPONENT),
    BUTTON_COMPONENT("Button Component", "A button with a label parameter", UiManifest.DocumentKind.COMPONENT);

    private final String title;
    private final String description;
    private final UiManifest.DocumentKind kind;

    UiDocumentTemplates(String title, String description, UiManifest.DocumentKind kind) {
        this.title = title;
        this.description = description;
        this.kind = kind;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public UiManifest.DocumentKind kind() {
        return kind;
    }

    /** A new document with {@code documentId} and {@code displayName} built from this template. */
    public OmuiArchive create(String documentId, String displayName) {
        UiManifest manifest = UiManifest.create(documentId, kind, displayName);
        return switch (this) {
            case BLANK_SCREEN -> OmuiArchive.of(manifest, doc(fullRoot(List.of()), null));
            case MENU_SCREEN -> menu(manifest);
            case BLANK_COMPONENT -> OmuiArchive.of(manifest, doc(Nodes.withName(Nodes.create("root", "Box"), "root"),
                new UiDocument.ComponentDef(List.of(), List.of(), List.of(), Map.of())));
            case BUTTON_COMPONENT -> button(manifest);
        };
    }

    private static UiDocument doc(UiNode root, UiDocument.ComponentDef component) {
        return new UiDocument(root, List.of(), null, component, Map.of());
    }

    private static UiNode fullRoot(List<UiNode> children) {
        UiNode root = Nodes.withName(Nodes.create("root", "Box"), "root");
        root = Nodes.withStyle(root, map("width", "100%", "height", "100%", "align-items", "center",
            "justify-content", "center"));
        return root.withChildren(children);
    }

    private static OmuiArchive menu(UiManifest manifest) {
        UiNode title = Nodes.withProps(Nodes.withClasses(Nodes.withName(Nodes.create("title", "Label"), "title"),
            List.of("title")), map("text", "Title"));
        List<UiNode> buttons = new java.util.ArrayList<>();
        String[] labels = {"Play", "Settings", "Quit"};
        for (String l : labels) {
            String id = l.toLowerCase();
            UiNode label = Nodes.withProps(Nodes.create(id + "_label", "Label"), map("text", l));
            UiNode b = Nodes.withClasses(Nodes.withName(Nodes.create(id, "Button"), id), List.of("menu-button"))
                .withChildren(List.of(label));
            buttons.add(b);
        }
        List<UiNode> panelKids = new java.util.ArrayList<>();
        panelKids.add(title);
        panelKids.addAll(buttons);
        UiNode panel = Nodes.withStyle(Nodes.withClasses(Nodes.withName(Nodes.create("panel", "Box"), "panel"),
            List.of("panel")), map("flex-direction", "column", "align-items", "center", "row-gap", 16,
            "padding-top", 32, "padding-bottom", 32, "padding-left", 48, "padding-right", 48));
        panel = panel.withChildren(panelKids);
        UiStyleSheet sheet = new UiStyleSheet("main", Map.of("--accent", UiValue.of("#E8D9A8")), List.of(), List.of(
            new UiStyleSheet.StyleRule(".panel", map("background-color", "#202020D0", "border-radius", 6), List.of(),
                Map.of()),
            new UiStyleSheet.StyleRule(".title", map("font-size", 28, "color", "var(--accent)"), List.of(), Map.of()),
            new UiStyleSheet.StyleRule(".menu-button", map("width", 320, "height", 44, "align-items", "center",
                "justify-content", "center"), List.of(), Map.of())), Map.of());
        UiDocument d = new UiDocument(fullRoot(List.of(panel)), List.of("main"), null, null, Map.of());
        return OmuiArchive.of(manifest, d).withStyle(sheet);
    }

    private static OmuiArchive button(UiManifest manifest) {
        UiNode label = Nodes.withBindings(Nodes.withName(Nodes.create("label", "Label"), "label"),
            List.of(new UiNode.UiBinding("prop:text", ".label")));
        UiNode button = Nodes.withStyle(Nodes.withName(Nodes.create("button", "Button"), "button"),
            map("width", 320, "height", 44, "align-items", "center", "justify-content", "center"))
            .withChildren(List.of(label));
        UiDocument.ComponentDef def = new UiDocument.ComponentDef(
            List.of(new UiDocument.Param("label", ValueType.STRING, UiValue.of("Button"), Map.of())),
            List.of(new UiDocument.EventDef("clicked", List.of(), Map.of())), List.of(), Map.of());
        return OmuiArchive.of(manifest, doc(button, def));
    }
}
