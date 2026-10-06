package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;

import java.util.List;
import java.util.Set;

/**
 * What a graph can see of its document (#291), for port resolution, validation and code
 * generation: its elements by stable node-id path, the Lua functions it may call, its clips,
 * and, for a component, its own contract. {@link DocumentEnvironment} builds one from an
 * archive and the runtime's component/script source; tests and the editor may supply others.
 */
public interface GraphEnvironment {

    /** An element a node may target. */
    record ElementInfo(String path, String type, String name, UiDocument.ComponentDef contract) {
        /** True for a component instance (its contract may be null when the component is missing). */
        public boolean isInstance() {
            return "Instance".equals(type);
        }
    }

    /** The document whose graphs these are. */
    OmuiArchive document();

    /**
     * An element by node-id path relative to the document root, joined through component
     * instances ({@code resume}, {@code resume/label}), or null when there is none.
     */
    ElementInfo element(String path);

    /** Every element a node may target, in tree order (pickers). */
    List<ElementInfo> elements();

    /** Annotated Lua functions of the code-behind and the requirable modules. */
    List<LuaFunction> luaFunctions();

    default LuaFunction luaFunction(String module, String name) {
        String m = module == null ? "" : module;
        for (LuaFunction f : luaFunctions()) {
            if (f.module().equals(m) && f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** Names {@code require} resolves: in-archive script ids and declared script dependencies. */
    Set<String> modules();

    /** Timeline clip ids of the document. */
    default Set<String> clips() {
        return document().animations().keySet();
    }

    /** The component contract when the document is a component, else null. */
    default UiDocument.ComponentDef ownContract() {
        return document().document().component();
    }
}
