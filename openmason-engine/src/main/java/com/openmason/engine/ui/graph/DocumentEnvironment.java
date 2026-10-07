package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.runtime.UiDocumentSource;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link GraphEnvironment} of an archive: its element tree, walked into component
 * instances through {@code source} (as the runtime builds element keys), and the annotated
 * functions of its code-behind, in-archive scripts and declared script dependencies.
 */
public final class DocumentEnvironment implements GraphEnvironment {

    private static final int MAX_DEPTH = 8;

    private final OmuiArchive doc;
    private final Map<String, ElementInfo> elements = new LinkedHashMap<>();
    private final List<LuaFunction> functions = new ArrayList<>();
    private final Set<String> modules = new LinkedHashSet<>();

    private DocumentEnvironment(OmuiArchive doc) {
        this.doc = doc;
    }

    /**
     * @param source components and shared scripts; {@link UiDocumentSource#EMPTY} sees only
     *               the archive itself (instances then have no contract)
     */
    public static DocumentEnvironment of(OmuiArchive doc, UiDocumentSource source) {
        DocumentEnvironment env = new DocumentEnvironment(doc);
        UiDocumentSource src = source == null ? UiDocumentSource.EMPTY : source;
        env.walk(doc.document().root(), "", src, new ArrayDeque<>(List.of(doc.manifest().documentId())));
        env.scripts(src);
        return env;
    }

    private void walk(UiNode node, String prefix, UiDocumentSource source, Deque<String> stack) {
        for (UiNode n : node.flatten()) {
            String path = prefix + n.id();
            UiDocument.ComponentDef contract = null;
            OmuiArchive comp = null;
            if (n.instance() != null) {
                comp = source.component(n.instance().component());
                contract = comp == null ? null : comp.document().component();
            }
            elements.putIfAbsent(path, new ElementInfo(path, n.type(), n.name(), contract));
            if (comp != null && stack.size() < MAX_DEPTH && !stack.contains(n.instance().component())) {
                stack.push(n.instance().component());
                walk(comp.document().root(), path + "/", source, stack);
                stack.pop();
            }
        }
    }

    private void scripts(UiDocumentSource source) {
        String code = doc.document().codeBehind();
        if (code != null) {
            String text = code.indexOf(':') < 0 ? doc.scripts().get(code) : source.script(code);
            functions.addAll(LuaSignatures.parse("", text));
        }
        for (Map.Entry<String, String> e : doc.scripts().entrySet()) {
            modules.add(e.getKey());
            if (!e.getKey().equals(code)) {
                functions.addAll(LuaSignatures.parse(e.getKey(), e.getValue()));
            }
        }
        for (UiDependency d : doc.dependencies().entries()) {
            if (d.kind() == UiDependency.Kind.SCRIPT) {
                modules.add(d.id());
                if (!d.id().equals(code)) {
                    functions.addAll(LuaSignatures.parse(d.id(), source.script(d.id())));
                }
            }
        }
    }

    @Override
    public OmuiArchive document() {
        return doc;
    }

    @Override
    public ElementInfo element(String path) {
        return path == null ? null : elements.get(path);
    }

    @Override
    public List<ElementInfo> elements() {
        return List.copyOf(elements.values());
    }

    @Override
    public List<LuaFunction> luaFunctions() {
        return Collections.unmodifiableList(functions);
    }

    @Override
    public Set<String> modules() {
        return Collections.unmodifiableSet(modules);
    }
}
