package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSelectors;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiStyleSheet.StyleRule;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.Nodes;
import com.openmason.main.systems.uiEditor.document.UiIds;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Document-level commands: manifest, style sheets and their rules, code-behind and script
 * sources, behavior graphs, animation clips and the dependency table.
 */
public final class DocumentCommands {

    private DocumentCommands() {
    }

    // ── manifest ────────────────────────────────────────────────────────────

    public static UiCommand setDisplayName(String name) {
        return UiCommand.of("Rename document", "displayName", ctx -> {
            UiManifest m = ctx.doc().manifest();
            ctx.setDoc(ctx.doc().withManifest(new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), name,
                m.uiApi(), m.layoutSemantics(), m.requires(), m.hostApis(), m.providers(), m.unknown())));
        });
    }

    // ── style sheets ────────────────────────────────────────────────────────

    /** Creates an in-archive sheet {@code id} and attaches it last (highest precedence). */
    public static UiCommand addStyleSheet(String id) {
        return UiCommand.of("Add style sheet " + id, ctx -> {
            if (!OmuiFormat.PART_ID.matcher(id).matches()) {
                throw new UiCommandException("'" + id + "' is not a valid sheet id (lowercase, digits, _ - /)");
            }
            if (ctx.doc().styles().containsKey(id)) {
                throw new UiCommandException("A sheet named '" + id + "' already exists");
            }
            ctx.setDoc(ctx.doc().withStyle(new UiStyleSheet(id, Map.of(), List.of(), List.of(), Map.of())));
            attach(ctx, id, true);
        });
    }

    /** Attaches ({@code on}) or detaches a sheet reference (in-archive id or dependency id). */
    public static UiCommand attachStyleSheet(String id, boolean on) {
        return UiCommand.of((on ? "Attach " : "Detach ") + id, ctx -> attach(ctx, id, on));
    }

    private static void attach(UiEditContext ctx, String id, boolean on) {
        UiDocument d = ctx.doc().document();
        List<String> sheets = new ArrayList<>(d.styleSheets());
        sheets.remove(id);
        if (on) {
            sheets.add(id);
        }
        ctx.setDocument(new UiDocument(d.root(), sheets, d.codeBehind(), d.component(), d.unknown()));
    }

    /** Moves an attached sheet by {@code delta} in the precedence list. */
    public static UiCommand moveStyleSheet(String id, int delta) {
        return UiCommand.of("Reorder style sheets", ctx -> {
            UiDocument d = ctx.doc().document();
            List<String> sheets = new ArrayList<>(d.styleSheets());
            int i = sheets.indexOf(id);
            int to = Math.max(0, Math.min(sheets.size() - 1, i + delta));
            if (i < 0 || to == i) {
                return;
            }
            sheets.remove(i);
            sheets.add(to, id);
            ctx.setDocument(new UiDocument(d.root(), sheets, d.codeBehind(), d.component(), d.unknown()));
        });
    }

    /** Appends a rule; {@code selector} is validated. */
    public static UiCommand addRule(String sheetId, String selector, Map<String, UiValue> style) {
        return editSheet(sheetId, "Add rule " + selector, null, s -> {
            requireSelector(s, selector);
            List<StyleRule> rules = new ArrayList<>(s.rules());
            rules.add(new StyleRule(selector, style, List.of(), Map.of()));
            return withRules(s, rules);
        });
    }

    public static UiCommand removeRule(String sheetId, int index) {
        return editSheet(sheetId, "Delete rule", null, s -> {
            List<StyleRule> rules = new ArrayList<>(s.rules());
            requireIndex(rules, index);
            rules.remove(index);
            return withRules(s, rules);
        });
    }

    /** Moves rule {@code index} by {@code delta} (later rules win on equal specificity). */
    public static UiCommand moveRule(String sheetId, int index, int delta) {
        return editSheet(sheetId, "Reorder rules", null, s -> {
            List<StyleRule> rules = new ArrayList<>(s.rules());
            requireIndex(rules, index);
            int to = Math.max(0, Math.min(rules.size() - 1, index + delta));
            rules.add(to, rules.remove(index));
            return withRules(s, rules);
        });
    }

    public static UiCommand setRuleSelector(String sheetId, int index, String selector) {
        return editSheet(sheetId, "Edit selector", null, s -> {
            requireSelector(s, selector);
            List<StyleRule> rules = new ArrayList<>(s.rules());
            requireIndex(rules, index);
            StyleRule r = rules.get(index);
            rules.set(index, new StyleRule(selector, r.style(), r.transitions(), r.unknown()));
            return withRules(s, rules);
        });
    }

    /** Sets ({@code null} removes) one declaration of a rule. */
    public static UiCommand setRuleDeclaration(String sheetId, int index, String property, UiValue value) {
        return editSheet(sheetId, (value == null ? "Remove " : "Set ") + property,
            "rule:" + sheetId + ":" + index + ":" + property, s -> {
                List<StyleRule> rules = new ArrayList<>(s.rules());
                requireIndex(rules, index);
                StyleRule r = rules.get(index);
                rules.set(index, new StyleRule(r.selector(), Nodes.put(r.style(), property, value), r.transitions(),
                    r.unknown()));
                return withRules(s, rules);
            });
    }

    /**
     * Sets ({@code null} removes) the transition rule {@code index} declares for {@code property}
     * (#295). Typing and dragging in one field merge into one undo step.
     */
    public static UiCommand setRuleTransition(String sheetId, int index, String property,
                                              UiStyleSheet.StyleTransition transition) {
        return editSheet(sheetId, (transition == null ? "Remove " : "Set ") + property + " transition",
            "transition:" + sheetId + ":" + index + ":" + property, s -> {
                if (transition != null && (!(transition.duration() >= 0) || !(transition.delay() >= 0)
                    || transition.bezier() != null && transition.bezier().problem() != null)) {
                    throw new UiCommandException("A transition needs a duration and delay of 0 s or more"
                        + (transition.bezier() != null && transition.bezier().problem() != null
                        ? " and " + transition.bezier().problem() : ""));
                }
                List<StyleRule> rules = new ArrayList<>(s.rules());
                requireIndex(rules, index);
                StyleRule r = rules.get(index);
                List<UiStyleSheet.StyleTransition> ts = new ArrayList<>(r.transitions());
                ts.removeIf(t -> t.property().equals(property));
                if (transition != null) {
                    ts.add(transition);
                }
                rules.set(index, new StyleRule(r.selector(), r.style(), ts, r.unknown()));
                return withRules(s, rules);
            });
    }

    /** Sets ({@code null} removes) a {@code --token} of a sheet. */
    public static UiCommand setVariable(String sheetId, String name, UiValue value) {
        return editSheet(sheetId, (value == null ? "Remove " : "Set ") + name, "var:" + sheetId + ":" + name,
            s -> new UiStyleSheet(s.id(), Nodes.put(s.variables(), name, value), s.customStates(), s.rules(),
                s.unknown()));
    }

    private static UiCommand editSheet(String sheetId, String label, String mergeKey, SheetEdit fn) {
        return UiCommand.of(label, mergeKey, ctx -> {
            UiStyleSheet s = ctx.doc().styles().get(sheetId);
            if (s == null) {
                throw new UiCommandException("Style sheet '" + sheetId + "' is not part of this document"
                    + " (shared sheets are edited in their own file)");
            }
            ctx.setDoc(ctx.doc().withStyle(fn.apply(s)));
        });
    }

    @FunctionalInterface
    private interface SheetEdit {
        UiStyleSheet apply(UiStyleSheet s) throws UiCommandException;
    }

    private static UiStyleSheet withRules(UiStyleSheet s, List<StyleRule> rules) {
        return new UiStyleSheet(s.id(), s.variables(), s.customStates(), rules, s.unknown());
    }

    private static void requireSelector(UiStyleSheet s, String selector) throws UiCommandException {
        String problem = UiSelectors.problem(selector, new HashSet<>(s.customStates()));
        if (problem != null) {
            throw new UiCommandException("Selector: " + problem);
        }
    }

    private static void requireIndex(List<?> list, int index) throws UiCommandException {
        if (index < 0 || index >= list.size()) {
            throw new UiCommandException("No rule #" + index);
        }
    }

    // ── code-behind and scripts ─────────────────────────────────────────────

    /** Sets the document's code-behind module (in-archive script id or dependency id; null clears). */
    public static UiCommand setCodeBehind(String moduleId) {
        return UiCommand.of(moduleId == null ? "Remove code-behind" : "Set code-behind " + moduleId, ctx -> {
            UiDocument d = ctx.doc().document();
            ctx.setDocument(new UiDocument(d.root(), d.styleSheets(), moduleId, d.component(), d.unknown()));
        });
    }

    /** Creates or replaces an in-archive Lua module ({@code scripts/<id>.lua}). */
    public static UiCommand setScript(String id, String source) {
        return UiCommand.of("Edit script " + id, "script:" + id, ctx -> {
            if (!OmuiFormat.PART_ID.matcher(id).matches()) {
                throw new UiCommandException("'" + id + "' is not a valid script id");
            }
            ctx.setDoc(ctx.doc().withScript(id, source));
        });
    }

    /** A new in-archive module that becomes the code-behind. */
    public static UiCommand createCodeBehind(String id, String source) {
        return UiCommand.compound("Add code-behind " + id, List.of(setScript(id, source), setCodeBehind(id)));
    }

    // ── graphs and clips ────────────────────────────────────────────────────

    /**
     * Takes the graphs (and their editor layout entries) of an edited copy of the document: the
     * graph editor saves through this, so its whole session lands as one undo step.
     */
    public static UiCommand replaceGraphs(OmuiArchive edited) {
        return UiCommand.of("Edit behavior graphs", ctx -> {
            OmuiArchive d = ctx.doc();
            Map<String, UiBytes> editor = new LinkedHashMap<>();
            d.editor().forEach((k, v) -> {
                if (!k.startsWith(OmuiFormat.EDITOR_DIR + "graphs/")) {
                    editor.put(k, v);
                }
            });
            edited.editor().forEach((k, v) -> {
                if (k.startsWith(OmuiFormat.EDITOR_DIR + "graphs/")) {
                    editor.put(k, v);
                }
            });
            Map<String, String> scripts = new LinkedHashMap<>(d.scripts());
            scripts.putAll(edited.scripts()); // "convert to script" adds a module
            UiDocument doc = d.document();
            UiDocument ed = edited.document();
            UiDocument merged = new UiDocument(doc.root(), doc.styleSheets(),
                ed.codeBehind() != null ? ed.codeBehind() : doc.codeBehind(), doc.component(), doc.unknown());
            ctx.setDoc(new OmuiArchive(d.manifest(), merged, d.styles(), edited.graphs(), d.animations(),
                d.stateMachines(), scripts,
                d.dependencies(), d.assets(), editor, d.extraEntries()));
        });
    }

    public static UiCommand putClip(UiAnimationClip clip) {
        return UiCommand.of("Edit animation " + clip.id(), "clip:" + clip.id(),
            ctx -> ctx.setDoc(ctx.doc().withAnimation(clip)));
    }

    public static UiCommand removeClip(String id) {
        return UiCommand.of("Delete animation " + id, ctx -> {
            OmuiArchive d = ctx.doc();
            Map<String, UiAnimationClip> clips = new LinkedHashMap<>(d.animations());
            if (clips.remove(id) == null) {
                throw new UiCommandException("No animation '" + id + "'");
            }
            ctx.setDoc(new OmuiArchive(d.manifest(), d.document(), d.styles(), d.graphs(), clips, d.stateMachines(), d.scripts(),
                d.dependencies(), d.assets(), d.editor(), d.extraEntries()));
        });
    }

    // ── dependencies and components ─────────────────────────────────────────

    /** Adds {@code row} unless a row with its id exists (existing rows are never replaced here). */
    public static UiCommand ensureDependency(UiDependency row) {
        return UiCommand.of("Add dependency " + row.id(), ctx -> ensure(ctx, row, null));
    }

    static void ensure(UiEditContext ctx, UiDependency row, UiBytes embeddedBytes) {
        OmuiArchive d = ctx.doc();
        if (d.dependencies().find(row.id()) != null) {
            return;
        }
        List<UiDependency> rows = new ArrayList<>(d.dependencies().entries());
        rows.add(row);
        d = d.withDependencies(new UiDependencies(rows, d.dependencies().unknown()));
        if (embeddedBytes != null && row.entry() != null && !d.assets().containsKey(row.entry())) {
            d = d.withAsset(row.entry(), embeddedBytes);
        }
        ctx.setDoc(d);
    }

    /**
     * Places an instance of component {@code componentId} at {@code at}, adding its shared
     * dependency row when the document does not list it yet. Parameters start at the
     * component's defaults (unset).
     */
    public static UiCommand addInstance(String componentId, UiDependency row, NodeLocation at) {
        return addInstance(componentId, row == null ? List.of() : List.of(row), at);
    }

    /**
     * As {@link #addInstance(String, UiDependency, NodeLocation)} with every row the component's
     * closure needs (nested components and their shared assets; see {@code ComponentDependencies}),
     * each added unless the document already lists it.
     */
    public static UiCommand addInstance(String componentId, List<UiDependency> rows, NodeLocation at) {
        return UiCommand.of("Add " + UiIds.stem(componentId), ctx -> {
            for (UiDependency row : rows) {
                ensure(ctx, row, null);
            }
            if (ctx.doc().dependencies().find(componentId) == null) {
                throw new UiCommandException("Component " + componentId + " is not a dependency of this document");
            }
            UiNode parent = ctx.require(at.parentId());
            NodeCommands.requireContainer(parent, at.slot());
            Set<String> ids = new HashSet<>(UiTree.ids(ctx.root()));
            UiNode node = Nodes.withInstance(Nodes.create(UiIds.fresh(UiIds.stem(componentId), ids),
                UiNode.INSTANCE_TYPE), new UiNode.ComponentInstance(componentId, Map.of(), List.of(), Map.of(), Map.of()));
            ctx.setRoot(UiTree.insert(ctx.root(), at, node));
            ctx.select(List.of(node.id()));
        });
    }

    /** Asset edits from the #285 layer (embed, extract, relink, rename) as one undoable step. */
    public static UiCommand assetEdit(com.openmason.engine.ui.assets.edit.AssetEdit edit) {
        return UiCommand.of(edit.label(), ctx -> {
            if (!edit.before().equals(ctx.doc())) {
                throw new UiCommandException(edit.label() + " was planned against an older version of the document");
            }
            ctx.applyAssetEdit(edit);
        });
    }
}
