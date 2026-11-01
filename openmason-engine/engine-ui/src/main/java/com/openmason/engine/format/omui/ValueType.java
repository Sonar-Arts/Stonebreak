package com.openmason.engine.format.omui;

/**
 * Types of component parameters, events, graph variables and graph data ports. Each maps to
 * one JSON shape so a literal can be checked without knowing the widget or node registry.
 */
public enum ValueType implements WireEnum {
    BOOL("bool"),
    INT("int"),
    NUMBER("number"),
    STRING("string"),
    /** {@code #RRGGBB} or {@code #RRGGBBAA}, uppercase or lowercase hex. */
    COLOR("color"),
    /** A dependency id or in-archive asset reference string. */
    ASSET("asset"),
    LIST("list"),
    OBJECT("object");

    private final String wire;

    ValueType(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** @return true when {@code value} has this type's JSON shape ({@code null} always fits) */
    public boolean accepts(UiValue value) {
        return switch (value) {
            case UiValue.Null n -> true;
            case UiValue.Bool b -> this == BOOL;
            case UiValue.Num n -> this == NUMBER || (this == INT && n.isIntegral());
            case UiValue.Str s -> this == STRING || this == ASSET
                    || (this == COLOR && s.value().matches("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})"));
            case UiValue.Arr a -> this == LIST;
            case UiValue.Obj o -> this == OBJECT;
        };
    }

    public static ValueType fromWire(String wire) {
        for (ValueType t : values()) {
            if (t.wire.equals(wire)) {
                return t;
            }
        }
        return null;
    }
}
