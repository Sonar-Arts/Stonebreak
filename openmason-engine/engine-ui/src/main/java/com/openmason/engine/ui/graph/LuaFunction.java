package com.openmason.engine.ui.graph;

import java.util.List;
import java.util.Objects;

/**
 * A Lua function a graph may call ({@code lua:call}), read from its LuaLS annotations by
 * {@link LuaSignatures}. Functions with a typed {@code ---@} signature become palette nodes.
 *
 * @param module   {@code ""} for the document's code-behind, otherwise the name {@code require}
 *                 takes (an in-archive script id or a declared script dependency id)
 * @param name     the function's name in its module table, or the global's name
 * @param global   true for an environment global ({@code function name()}), false for a field
 *                 of the table the module returns ({@code function M.name()})
 * @param params   parameters in order ({@link PortSpec#optional()} for {@code name?} / {@code T?})
 * @param returns  return values in order; names come from {@code ---@return T name}, else
 *                 {@code result}, {@code result2}, ...
 * @param async    {@code ---@async}: may {@code ui.await}, so it only runs inside a task
 * @param doc      the description lines above the annotations
 * @param line     1-based line of the {@code function} keyword
 */
public record LuaFunction(String module, String name, boolean global, List<PortSpec> params, List<PortSpec> returns,
                          boolean async, String doc, int line) {

    public LuaFunction {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(name, "name");
        params = List.copyOf(params);
        returns = List.copyOf(returns);
        doc = doc == null ? "" : doc;
    }

    /** {@code name} for the code-behind, {@code module.name} otherwise (the palette label). */
    public String label() {
        return module.isEmpty() ? name : module + "." + name;
    }
}
