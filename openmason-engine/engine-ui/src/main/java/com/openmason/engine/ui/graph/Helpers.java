package com.openmason.engine.ui.graph;

import java.util.Map;

/** Chunk-level Lua helpers generated code may share; each is written once, only when used. */
final class Helpers {

    static final String TEXT = "text";

    /** Name → definition. The definition binds the name as a chunk local. */
    static final Map<String, String> SOURCES = Map.of(
        TEXT, """
            -- Any value as text; integral floats print without ".0" (3.0 -> "3").
            local function text(v)
              if math.type(v) == "float" and v == math.floor(v) and v > -2^53 and v < 2^53 then
                return string.format("%d", v)
              end
              return tostring(v)
            end""");

    private Helpers() {
    }

    static String text(Emit e) {
        return e.helper(TEXT);
    }
}
