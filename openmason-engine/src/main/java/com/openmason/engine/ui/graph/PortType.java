package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

/**
 * The type of a graph port (#291): {@link #EXEC} for execution flow, a value type for data, or
 * {@link #ANY}. Value types are the format's {@link ValueType}s, so graph variables, component
 * parameters, signal arguments and data ports share one vocabulary and one wire spelling.
 */
public enum PortType {
    EXEC("exec", null),
    ANY("any", null),
    BOOL("bool", ValueType.BOOL),
    INT("int", ValueType.INT),
    NUMBER("number", ValueType.NUMBER),
    STRING("string", ValueType.STRING),
    COLOR("color", ValueType.COLOR),
    ASSET("asset", ValueType.ASSET),
    LIST("list", ValueType.LIST),
    OBJECT("object", ValueType.OBJECT);

    /** How a value of one type reaches a port of another. */
    public enum Assign {
        /** Same representation: passed as is. */
        DIRECT,
        /** Rendered with {@code tostring} (numbers and booleans into text ports). */
        TO_STRING,
        /** Not connectable. */
        NONE
    }

    private final String wire;
    private final ValueType value;

    PortType(String wire, ValueType value) {
        this.wire = wire;
        this.value = value;
    }

    public String wire() {
        return wire;
    }

    /** The format value type, or null for {@link #EXEC} and {@link #ANY}. */
    public ValueType valueType() {
        return value;
    }

    public boolean isExec() {
        return this == EXEC;
    }

    public static PortType of(ValueType t) {
        for (PortType p : values()) {
            if (p.value == t) {
                return p;
            }
        }
        throw new IllegalArgumentException("no port type for " + t);
    }

    /** The type for a wire name ({@code bool}, ..., {@code any}, {@code exec}), or null. */
    public static PortType fromWire(String wire) {
        for (PortType p : values()) {
            if (p.wire.equals(wire)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Whether an output of type {@code from} may feed an input of this type. Exec only meets
     * exec; {@code any} meets every data type both ways (checked at run time); an int is a
     * number; colors and assets are strings; numbers and booleans render into text ports.
     */
    public Assign accepts(PortType from) {
        if (this == EXEC || from == EXEC) {
            return this == from ? Assign.DIRECT : Assign.NONE;
        }
        if (this == from || this == ANY || from == ANY) {
            return Assign.DIRECT;
        }
        return switch (this) {
            case NUMBER -> from == INT ? Assign.DIRECT : Assign.NONE;
            case STRING -> switch (from) {
                case COLOR, ASSET -> Assign.DIRECT;
                case INT, NUMBER, BOOL -> Assign.TO_STRING;
                default -> Assign.NONE;
            };
            default -> Assign.NONE;
        };
    }

    /** Whether a literal fits this type ({@code null} always fits a data port). */
    public boolean acceptsLiteral(UiValue v) {
        if (this == EXEC) {
            return false;
        }
        return this == ANY || value.accepts(v);
    }
}
