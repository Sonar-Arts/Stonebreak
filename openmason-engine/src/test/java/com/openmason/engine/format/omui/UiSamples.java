package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiDocument.ComponentDef;
import com.openmason.engine.format.omui.UiDocument.EventDef;
import com.openmason.engine.format.omui.UiDocument.Param;
import com.openmason.engine.format.omui.UiDocument.Slot;
import com.openmason.engine.format.omui.UiGraph.GraphEdge;
import com.openmason.engine.format.omui.UiGraph.GraphFunction;
import com.openmason.engine.format.omui.UiGraph.GraphNode;
import com.openmason.engine.format.omui.UiGraph.GraphPort;
import com.openmason.engine.format.omui.UiGraph.GraphVariable;
import com.openmason.engine.format.omui.UiManifest.DocumentKind;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiNode.ComponentInstance;
import com.openmason.engine.format.omui.UiNode.InstanceOverride;
import com.openmason.engine.format.omui.UiNode.UiBinding;
import com.openmason.engine.format.omui.UiStyleSheet.StyleRule;
import com.openmason.engine.format.omui.UiStyleSheet.StyleTransition;
import com.openmason.engine.format.omui.io.ArchiveIO;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample documents behind the golden fixtures in {@code src/test/resources/ui/omui/}: a
 * reusable stone-button component, the pause menu screen that instantiates it (style sheets,
 * bindings, a graph, Lua code-behind plus a shared Lua module, a timeline clip, shared and
 * embedded dependencies, editor metadata), and the frozen 0.1 draft of the pause menu.
 * Values follow the #283 pause fixture (520x560 panel, 360x50 buttons, 20 px gap, 50 px top
 * padding).
 */
public final class UiSamples {

    public static final String BUTTON_ID = "stonebreak:ui/components/stone_button";
    public static final String PAUSE_ID = "stonebreak:ui/pause_menu";
    public static final String THEME_ID = "stonebreak:ui/themes/stone";
    public static final String COMMON_LUA_ID = "stonebreak:ui/scripts/common";
    public static final String PANEL_TEXTURE_ID = "stonebreak:ui/textures/panel";

    public static final byte[] THEME_BYTES = utf8("{\"id\":\"stone\"}\n");
    public static final byte[] COMMON_LUA_BYTES = utf8("local M = {}\nfunction M.noop() end\nreturn M\n");
    public static final byte[] PANEL_TEXTURE_BYTES = utf8("fake-sbt-panel-texture");

    public static final String PAUSE_LUA = """
            -- Pause menu code-behind (#297 pilot shape). Never executed by the format layer.
            local common = require("stonebreak:ui/scripts/common")
            local M = {}

            function M.on_open(ui)
              ui.q("#resume"):on("click", function() ui.request("screen.resume") end)
              ui.q("#quit"):on("click", function() ui.request("screen.quit_to_menu") end)
              common.noop()
            end

            return M
            """;

    private UiSamples() {
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static UiNode node(String id, String name, String type, List<String> classes, Map<String, UiValue> props,
                       Map<String, UiValue> style, List<UiNode> children) {
        return new UiNode(id, name, type, 1, classes, props, style, null, List.of(), null, children, Map.of());
    }

    static Map<String, UiValue> map(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], switch (v) {
                case UiValue u -> u;
                case String s -> UiValue.of(s);
                case Number n -> UiValue.of(n.doubleValue());
                case Boolean b -> UiValue.of(b);
                default -> throw new IllegalArgumentException(String.valueOf(v));
            });
        }
        return m;
    }

    // ── component ──

    public static OmuiArchive stoneButton() {
        UiNode icon = node("icon_box", "icon", "Box", List.of(), Map.of(), map("width", 16, "height", 16), List.of());
        UiNode label = new UiNode("label", "label", "Label", 1, List.of(), map("text", "Button"), Map.of(), null,
                List.of(new UiBinding("prop:text", ".label")), null, List.of(), Map.of());
        UiNode root = node("button", "button", "Button", List.of("stone-button"), Map.of(),
                map("flex-direction", "row", "align-items", "center"), List.of(icon, label));
        ComponentDef contract = new ComponentDef(
                List.of(new Param("label", ValueType.STRING, UiValue.of("Button"), Map.of()),
                        new Param("enabled", ValueType.BOOL, UiValue.TRUE, Map.of())),
                List.of(new EventDef("pressed", List.of(), Map.of())),
                List.of(new Slot("icon", "icon_box", Map.of())),
                Map.of());
        UiStyleSheet sheet = new UiStyleSheet("stone_button", map("--hover-tint", "#FFFFFFFF"), List.of(), List.of(
                new StyleRule(".stone-button", map("width", 360, "height", 50), List.of(), Map.of()),
                new StyleRule(".stone-button:hover", map("-sb-tint", "var(--hover-tint)"),
                        List.of(new StyleTransition("-sb-tint", 0.1, UiEasing.EASE_OUT, 0, Map.of())), Map.of()),
                new StyleRule(".stone-button:disabled", map("opacity", 0.5), List.of(), Map.of())), Map.of());
        UiManifest manifest = UiManifest.create(BUTTON_ID, DocumentKind.COMPONENT, "Stone Button");
        return OmuiArchive.of(manifest, new UiDocument(root, List.of("stone_button"), null, contract, Map.of()))
                .withStyle(sheet);
    }

    // ── screen ──

    public static OmuiArchive pauseMenu() throws UiFormatException {
        byte[] button = OmuiWriter.write(stoneButton());
        UiBytes buttonBytes = UiBytes.copyOf(button);

        UiNode title = node("title", "title", "Label", List.of("title"), map("text", "Game Paused"), Map.of(), List.of());
        UiNode resume = instance("resume", "Resume Game", List.of(), List.of());
        UiNode statistics = instance("statistics", "Statistics", List.of(), List.of());
        UiNode resync = new UiNode("resync", "resync", UiNode.INSTANCE_TYPE, 1, List.of(), Map.of(), Map.of(), null,
                List.of(new UiBinding("style:display", ".online", UiNode.BindingMode.TO_TARGET, "display_if", Map.of())),
                new ComponentInstance(BUTTON_ID, map("label", "Resync"), List.of(), Map.of(), Map.of()),
                List.of(), Map.of());
        UiNode quit = instance("quit", "Quit to Menu",
                List.of(new InstanceOverride("button", Map.of(), Map.of(), List.of("danger"), List.of(), Map.of())),
                List.of(node("quit_icon", null, "Image", List.of(), map("source", PANEL_TEXTURE_ID), Map.of(), List.of())));
        UiNode panel = node("panel", "panel", "Box", List.of("panel"), Map.of(),
                map("width", 520, "height", 560, "flex-direction", "column", "justify-content", "center",
                        "align-items", "center", "row-gap", 20, "padding-top", 50, "background-image", PANEL_TEXTURE_ID),
                List.of(title, resume, statistics, resync, quit));
        UiNode root = new UiNode("root", "root", "Box", 1, List.of(), Map.of(),
                map("width", "100%", "height", "100%", "justify-content", "center", "align-items", "center"),
                "session", List.of(), null, List.of(panel), Map.of());

        UiStyleSheet sheet = new UiStyleSheet("pause", map("--accent", "#C8A050"), List.of("selected"), List.of(
                new StyleRule(".panel", map("background-color", "#00000078"), List.of(), Map.of()),
                new StyleRule("#panel > Label.title", map("color", "var(--accent)", "font-size", 32), List.of(), Map.of()),
                new StyleRule("Instance .danger:hover, Instance .danger:selected", map("-sb-tint", "#FF8080FF"),
                        List.of(), Map.of())), Map.of());

        UiGraph graph = new UiGraph("behaviors",
                List.of(new GraphVariable("clicks", ValueType.INT, UiValue.of(0), Map.of())),
                List.of(new GraphNode("on_resume", "ui:event.click", 1, 40, 80, Map.of(), map("target", "resume"), Map.of()),
                        new GraphNode("count", "ui:variable.increment", 1, 260, 80, Map.of(), map("variable", "clicks"), Map.of()),
                        new GraphNode("request", "ui:action.request", 1, 480, 80, map("action", "screen.resume"), Map.of(), Map.of())),
                List.of(new GraphEdge("on_resume", "then", "count", "exec", Map.of()),
                        new GraphEdge("count", "then", "request", "exec", Map.of())),
                List.of(new GraphFunction("log_click",
                        List.of(new GraphPort("exec", GraphPort.EXEC, Map.of()), new GraphPort("label", "string", Map.of())),
                        List.of(new GraphPort("then", GraphPort.EXEC, Map.of())),
                        List.of(new GraphNode("entry", "ui:function.entry", 1, 0, 0, Map.of(), Map.of(), Map.of()),
                                new GraphNode("print", "lua:call", 1, 200, 0, Map.of(), map("function", "common.noop"), Map.of())),
                        List.of(new GraphEdge("entry", "then", "print", "exec", Map.of())), Map.of())),
                Map.of());

        UiAnimationClip open = new UiAnimationClip("open", 0.25, LoopMode.ONCE, List.of(
                new AnimTrack("panel", "style:opacity", List.of(
                        new AnimKey(0, UiValue.of(0), UiEasing.EASE_OUT, Map.of()),
                        new AnimKey(0.25, UiValue.of(1), UiEasing.LINEAR, Map.of())), Map.of()),
                new AnimTrack("panel", "style:translate-y", List.of(
                        new AnimKey(0, UiValue.of(-20), UiEasing.EASE_OUT, Map.of()),
                        new AnimKey(0.25, UiValue.of(0), UiEasing.LINEAR, Map.of())), Map.of())),
                List.of(new AnimEvent(0.25, "opened", Map.of())), Map.of());

        String buttonEntry = "assets/components/stone_button.omui";
        UiDependencies deps = new UiDependencies(List.of(
                UiDependency.embedded(BUTTON_ID, UiDependency.Kind.COMPONENT, buttonBytes, buttonEntry,
                        "ui/components/stone_button.omui"),
                UiDependency.shared(THEME_ID, UiDependency.Kind.STYLESHEET, UiBytes.sha256(THEME_BYTES),
                        THEME_BYTES.length, "ui/themes/stone.uss.json"),
                UiDependency.shared(COMMON_LUA_ID, UiDependency.Kind.SCRIPT, UiBytes.sha256(COMMON_LUA_BYTES),
                        COMMON_LUA_BYTES.length, "ui/scripts/common.lua"),
                UiDependency.shared(PANEL_TEXTURE_ID, UiDependency.Kind.TEXTURE, UiBytes.sha256(PANEL_TEXTURE_BYTES),
                        PANEL_TEXTURE_BYTES.length, "textures/ui/panel.sbt")), Map.of());

        UiManifest manifest = new UiManifest(OmuiFormat.SCHEMA_VERSION, PAUSE_ID, DocumentKind.SCREEN, "Pause Menu",
                OmuiFormat.UI_API_VERSION, OmuiFormat.LAYOUT_SEMANTICS, List.of(),
                List.of(new HostRequirement("stonebreak:screen.pause", 1),
                        new HostRequirement("stonebreak:session", 1),
                        new HostRequirement("stonebreak:network.resync", 1, true, Map.of())),
                List.of(), Map.of());

        return OmuiArchive.of(manifest, new UiDocument(root, List.of(THEME_ID, "pause"), "pause", null, Map.of()))
                .withStyle(sheet)
                .withGraph(graph)
                .withAnimation(open)
                .withScript("pause", PAUSE_LUA)
                .withDependencies(deps)
                .withAsset(buttonEntry, buttonBytes)
                .withEditorEntry("editor/workspace.json",
                        UiBytes.utf8("{\n  \"selection\": [\n    \"resume\"\n  ],\n  \"zoom\": 1\n}\n"))
                .withEditorEntry("editor/fixtures.json",
                        UiBytes.utf8("{\n  \"session\": {\n    \"online\": true\n  }\n}\n"));
    }

    private static UiNode instance(String id, String label, List<InstanceOverride> overrides, List<UiNode> icon) {
        Map<String, List<UiNode>> slots = icon.isEmpty() ? Map.of() : Map.of("icon", icon);
        return new UiNode(id, id, UiNode.INSTANCE_TYPE, 1, List.of("menu-button"), Map.of(), Map.of(), null, List.of(),
                new ComponentInstance(BUTTON_ID, map("label", label), overrides, slots, Map.of()), List.of(), Map.of());
    }

    // ── frozen 0.1 draft ──

    /** Raw entries of the pause menu written in the frozen 0.1 draft schema. */
    public static Map<String, UiBytes> draftEntries() {
        Map<String, UiBytes> e = new LinkedHashMap<>();
        e.put("manifest.json", UiBytes.utf8("""
                {
                  "format": "omui",
                  "schemaVersion": "0.1",
                  "id": "stonebreak:ui/pause_draft",
                  "kind": "screen",
                  "name": "Pause (draft)"
                }
                """));
        e.put("document.json", UiBytes.utf8("""
                {
                  "root": {
                    "id": "root",
                    "type": "Box",
                    "layout": {"width": "100%", "height": "100%", "justify": "center", "alignItems": "center"},
                    "children": [
                      {
                        "id": "panel",
                        "name": "panel",
                        "type": "Box",
                        "layout": {"width": 520, "height": 560, "direction": "column", "justify": "center",
                                   "alignItems": "center", "gap": 20, "padding": [null, 50, null, null]},
                        "children": [
                          {"id": "resume", "name": "resume", "type": "Button", "props": {"text": "Resume Game"},
                           "layout": {"width": 360, "height": 50}},
                          {"id": "resync", "name": "resync", "type": "Button", "props": {"text": "Resync"},
                           "layout": {"width": 360, "height": 50, "display": "none"}},
                          {"id": "quit", "name": "quit", "type": "Button", "props": {"text": "Quit to Menu"},
                           "layout": {"width": 360, "height": 50, "wrap": false, "alignSelf": "center"},
                           "draftNote": "kept: unknown fields survive the upgrade"}
                        ]
                      }
                    ]
                  },
                  "script": "scripts/pause.lua"
                }
                """));
        e.put("scripts/pause.lua", UiBytes.utf8("return {}\n"));
        return e;
    }

    public static byte[] draftArchive() {
        return ArchiveIO.write(draftEntries());
    }
}
