package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;

import java.util.Objects;

/**
 * One port of a resolved node.
 *
 * @param name         port name, an identifier ({@code exec}, {@code then}, {@code condition})
 * @param type         {@link PortType#EXEC} or a data type
 * @param defaultValue literal used when a data input is neither connected nor set; null = none
 * @param optional     a data input that may stay empty (it is {@code nil} in Lua)
 * @param doc          one line for the editor's tooltip
 */
public record PortSpec(String name, PortType type, UiValue defaultValue, boolean optional, String doc) {

    /** Standard exec input of a statement node. */
    public static final String EXEC_IN = "exec";
    /** Standard exec output: what runs next. */
    public static final String THEN = "then";

    public PortSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        doc = doc == null ? "" : doc;
    }

    public static PortSpec exec(String name) {
        return new PortSpec(name, PortType.EXEC, null, false, "");
    }

    public static PortSpec exec(String name, String doc) {
        return new PortSpec(name, PortType.EXEC, null, false, doc);
    }

    public static PortSpec data(String name, PortType type) {
        return new PortSpec(name, type, null, false, "");
    }

    public static PortSpec data(String name, PortType type, String doc) {
        return new PortSpec(name, type, null, false, doc);
    }

    /** A data input with a default literal. */
    public static PortSpec data(String name, PortType type, UiValue defaultValue, String doc) {
        return new PortSpec(name, type, defaultValue, false, doc);
    }

    public static PortSpec optional(String name, PortType type, String doc) {
        return new PortSpec(name, type, null, true, doc);
    }

    public boolean isExec() {
        return type == PortType.EXEC;
    }
}
