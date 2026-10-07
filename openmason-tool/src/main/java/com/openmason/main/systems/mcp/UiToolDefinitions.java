package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.uiEditor.automation.UiPreviewAutomation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * The UI Editor's MCP surface (#324): {@code ui_*} tools over {@link UiEditingService}. Documents
 * are addressed by {@code doc} (document id, file name or title; omitted = the active one) and
 * elements by element key. All edits are {@code ui_ops} batches: one call, one undo step.
 * Guide topic {@code ui_editor} explains keys, overrides, convention paths and Preview.
 */
public final class UiToolDefinitions {

    private static final String DOC = "Document id, file name or title (default: active)";

    private final UiEditingService ui;
    private final ObjectMapper mapper;

    public UiToolDefinitions(UiEditingService ui, ObjectMapper mapper) {
        this.ui = ui;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- documents ----------
        registry.register(new McpTool("ui_documents",
                "List open UI documents: doc id, title, kind, file, origin, active, dirty, undo/redo labels, "
                        + "selection, and saveTarget (convention path) for untitled ones. Start here.",
                schema().build(),
                args -> ui.documents()));

        registry.register(new McpTool("ui_new",
                "Create a UI document from a template and make it active (unsaved until ui_save, which "
                        + "writes UI/<ns>/<path>.omui). Templates: blank_screen (default), menu_screen, "
                        + "blank_component, button_component.",
                schema().str("template", "blank_screen | menu_screen | blank_component | button_component")
                        .str("id", "Document id namespace:path, lowercase (stonebreak:ui/screens/hud)")
                        .str("name", "Display name (default: last id segment)")
                        .required("id").build(),
                args -> ui.create(optString(args, "template"), reqString(args, "id"), optString(args, "name"))));

        registry.register(new McpTool("ui_open",
                "Open a .omui, or an .sbui as an editable copy (never written back). path: absolute or "
                        + "project:/game:/exports: inside the sandbox roots. Already open = activated.",
                schema().str("path", "File to open").required("path").build(),
                args -> ui.open(reqString(args, "path"))));

        registry.register(new McpTool("ui_close",
                "Close a UI document. Refuses unsaved changes unless discard:true.",
                schema().str("doc", DOC).bool("discard", "Drop unsaved changes").build(),
                args -> ui.close(optString(args, "doc"), optBool(args, "discard", false))));

        registry.register(new McpTool("ui_activate",
                "Make a document the active one (shown in the Designer; the default doc of other tools).",
                schema().str("doc", DOC).required("doc").build(),
                args -> ui.activate(reqString(args, "doc"))));

        // ---------- inspect ----------
        registry.register(new McpTool("ui_tree",
                "Element tree: key, type, name, classes, component (instances), slots, children. "
                        + "internals:true adds elements inside component instances (keys like quit/label).",
                schema().str("doc", DOC).bool("internals", "Include component internals").build(),
                args -> ui.tree(optString(args, "doc"), optBool(args, "internals", false))));

        registry.register(new McpTool("ui_get",
                "One element: props, inline style, classes, data source, bindings, instance params/"
                        + "overrides (internal key: component node + override), location, rect (frame px). "
                        + "computed:true adds computed style with origins (rule sheet#i selector|inline|override..).",
                schema().str("doc", DOC).str("key", "Element key").bool("computed", "Include computed style + origins")
                        .required("key").build(),
                args -> ui.get(optString(args, "doc"), reqString(args, "key"), optBool(args, "computed", false))));

        registry.register(new McpTool("ui_diagnostics",
                "Every problem of the document as the Diagnostics panel lists it: format, assets, runtime, "
                        + "references, scripts, preview activation.",
                schema().str("doc", DOC).build(),
                args -> ui.diagnostics(optString(args, "doc"))));

        registry.register(new McpTool("ui_style_sheets",
                "Attached sheet order and the in-archive sheets with tokens and rules (index, selector, "
                        + "specificity, style). key: also which rules match that element and whether each still "
                        + "contributes.",
                schema().str("doc", DOC).str("key", "Element key to match rules against").build(),
                args -> ui.styleSheets(optString(args, "doc"), optString(args, "key"))));

        // ---------- edit ----------
        registry.register(new McpTool("ui_ops",
                "Edit a UI document: an op batch validated in full, then applied as ONE undo step; a failing "
                        + "op leaves the document untouched and names its index + reason. ops: [{\"op\":...}]. "
                        + "Elements by key; quit/label (inside an instance) edits an override. \"as\":\"x\" on "
                        + "create/add_instance/duplicate/wrap binds $x (or $x/inner) for later ops. Placement: "
                        + "parent,index,slot or before|after. Ops: create{type,id,name,classes,props,style,"
                        + "data_source,bindings} add_instance{component,name,params} delete move duplicate "
                        + "wrap{type} {keys}; reorder{key,delta|to} rename{key,name} set_prop|clear_prop{keys,prop,"
                        + "value} set_style{keys,style:{p:v|null}} clear_style{keys,properties} set_classes{keys,"
                        + "classes|add|remove} set_data_source{key,path} bind{key,target,path,mode,converter} "
                        + "unbind set_param{key,param,value} reset_override{keys} set_display_name add_sheet{id} "
                        + "attach_sheet move_sheet add_rule{sheet,selector,style} set_rule{sheet,rule,selector,"
                        + "style} remove_rule move_rule set_token{sheet,name,value} set_script{id,source} "
                        + "set_code_behind{module} put_clip{clip} remove_clip{id} put_state_machine{machine} "
                        + "remove_state_machine put_graph{graph} remove_graph{id} add_dependency{path|id+kind,embed,"
                        + "optional,fallback,requires} set_dependency remove_dependency{id,force} embed_|refresh_"
                        + "dependency{id} extract_dependency{id,collision} relink_dependency{id,path}. Guide: "
                        + "describe_api ui_editor.",
                schema().str("doc", DOC)
                        .arr("ops", "object", "The ops, in order")
                        .str("label", "History label for the step (default \"Agent: ...\")")
                        .bool("verbose", "Return the element tree after the edit")
                        .required("ops").build(),
                args -> ui.ops(optString(args, "doc"), args.get("ops"), optString(args, "label"),
                        optBool(args, "verbose", false))));

        registry.register(new McpTool("ui_undo",
                "Undo the last step of a UI document (an agent batch or an edit by the author).",
                schema().str("doc", DOC).build(),
                args -> ui.undo(optString(args, "doc"), false)));

        registry.register(new McpTool("ui_redo",
                "Redo the last undone step of a UI document.",
                schema().str("doc", DOC).build(),
                args -> ui.undo(optString(args, "doc"), true)));

        // ---------- preview ----------
        registry.register(new McpTool("ui_preview",
                "Designer runtime state, never source or dirty: mode design|preview (preview runs scripts, "
                        + "graphs, fixtures, real input; leaving discards it), force {key:[\"hover\"]} pseudo-"
                        + "states in design ([] clears), clear_forced, frame width/height/ui_scale/pixel_ratio.",
                schema().str("doc", DOC).enumStr("mode", "design or preview", "design", "preview")
                        .obj("force", "{elementKey: [pseudo-states]}")
                        .bool("clear_forced", "Drop every forced pseudo-state")
                        .intg("width", "Frame width, device px").intg("height", "Frame height, device px")
                        .num("ui_scale", "UI scale (0.25-8)").num("pixel_ratio", "Device pixel ratio (0.5-4)")
                        .build(),
                args -> ui.preview(optString(args, "doc"), optString(args, "mode"), force(args),
                        optBool(args, "clear_forced", false), frame(args))));

        registry.register(new McpTool("ui_capture",
                "PNG of the document as the engine paints it: source design (forced states applied), "
                        + "preview (live frame) or auto (the mode). Frame = the designer's unless width/height/"
                        + "ui_scale/pixel_ratio are given. max_size caps the longest side (default 1024, 0 = none).",
                schema().str("doc", DOC).enumStr("source", "auto | design | preview", "auto", "design", "preview")
                        .intg("width", "Frame width, device px").intg("height", "Frame height, device px")
                        .num("ui_scale", "UI scale").num("pixel_ratio", "Device pixel ratio")
                        .intg("max_size", "Longest-side cap of the returned image (default 1024, 0 = none)")
                        .build(),
                args -> ui.capture(optString(args, "doc"), optString(args, "source"), frame(args),
                        optInt(args, "max_size", 1024))));

        registry.register(new McpTool("ui_preview_input",
                "Input to the running preview (the canvas's router). events: [{type:click|move|down|up|"
                        + "leave|wheel, key:<element> | x,y (frame px), button?, dy?}, {type:key, name:enter|"
                        + "escape|tab|up|a|f1.., mods?}, {type:text, text}]. advance_ms then runs the preview "
                        + "clock so scripts/tweens settle.",
                schema().str("doc", DOC).arr("events", "object", "Input events in order")
                        .intg("advance_ms", "Preview time to run after the events (default 100, max 10000)")
                        .required("events").build(),
                args -> ui.input(optString(args, "doc"), args.get("events"), optInt(args, "advance_ms", 100))));

        registry.register(new McpTool("ui_console",
                "The preview's script console (last lines), loaded modules, script findings, host requests "
                        + "(sound/navigate/close: listed, never performed) and fixture action calls.",
                schema().str("doc", DOC).intg("limit", "Lines per list (default 50)").build(),
                args -> ui.console(optString(args, "doc"), optInt(args, "limit", 50))));

        // ---------- files ----------
        registry.register(new McpTool("ui_save",
                "Save the .omui to its file, or a new document to its convention path "
                        + "UI/<ns>/<path>.omui (screens resolve project components there). Files under game: "
                        + "and overwrites follow the write policy (may ask the user). Use ui_save_as for "
                        + "another file.",
                schema().str("doc", DOC).build(),
                args -> ui.save(optString(args, "doc"), null, false, false)));

        registry.register(new McpTool("ui_save_as",
                "Save the .omui to another file through the write sandbox (project:/exports:/game:, or "
                        + "absolute inside a root); it becomes the document's file. prompt:true lets the user "
                        + "pick in the Save Sheet; overwrite acknowledges replacing a file.",
                schema().str("doc", DOC).str("path", "Target .omui (extension added)")
                        .bool("prompt", "Ask in the Save Sheet").bool("overwrite", "Acknowledge replacing a file")
                        .build(),
                args -> {
                    String path = optString(args, "path");
                    boolean prompt = optBool(args, "prompt", false);
                    if (path == null && !prompt) {
                        throw new IllegalArgumentException("ui_save_as needs path (e.g. project:UI/menus/pause_v2) "
                                + "or prompt:true; ui_save saves to the document's own file");
                    }
                    return ui.save(optString(args, "doc"), path, prompt, optBool(args, "overwrite", false));
                }));

        registry.register(new McpTool("ui_export",
                "Export .sbui + <name>.report.json (default Exports/UI/<stem>.sbui). mode shared (the "
                        + "report lists what must ship) or collect_all (packs every dependency). Sandboxed like "
                        + "ui_save_as. Result hostCheck = what the real game host would say. deploy:true ships it "
                        + "into the game (ui/documents/<screen>.sbui + shared assets under ui/shared/).",
                schema().str("doc", DOC).str("path", "Target .sbui")
                        .enumStr("mode", "shared (default) | collect_all", "shared", "collect_all")
                        .bool("prompt", "Ask in the Save Sheet").bool("overwrite", "Acknowledge replacing a file")
                        .bool("deploy", "Ship into the game instead of Exports/")
                        .build(),
                args -> ui.export(optString(args, "doc"), optString(args, "path"), optString(args, "mode"),
                        optBool(args, "prompt", false), optBool(args, "overwrite", false),
                        optBool(args, "deploy", false))));

        registry.register(new McpTool("ui_import_sbui",
                "Import an .sbui into the project (new files only; colliding ids get -imported), save it at "
                        + "a convention path and open it.",
                schema().str("path", ".sbui to import").required("path").build(),
                args -> ui.importSbui(reqString(args, "path"))));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private static UiPreviewAutomation.Frame frame(JsonNode args) {
        Integer w = args.hasNonNull("width") ? McpArgs.reqInt(args, "width") : null;
        Integer h = args.hasNonNull("height") ? McpArgs.reqInt(args, "height") : null;
        Float s = McpArgs.optFloatBoxed(args, "ui_scale");
        Float r = McpArgs.optFloatBoxed(args, "pixel_ratio");
        return w == null && h == null && s == null && r == null ? UiPreviewAutomation.Frame.CURRENT
                : new UiPreviewAutomation.Frame(w, h, s, r);
    }

    private static Map<String, List<String>> force(JsonNode args) {
        JsonNode f = args.get("force");
        if (f == null || f.isNull()) {
            return null;
        }
        if (!(f instanceof ObjectNode obj)) {
            throw new IllegalArgumentException("force is an object {elementKey: [\"hover\", ...]}");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            List<String> states = new ArrayList<>();
            if (e.getValue().isTextual()) {
                states.add(e.getValue().asText());
            } else if (e.getValue().isArray()) {
                e.getValue().forEach(s -> states.add(s.asText()));
            } else {
                throw new IllegalArgumentException("force." + e.getKey() + " is a list of pseudo-states");
            }
            out.put(e.getKey(), states);
        }
        return out;
    }
}
