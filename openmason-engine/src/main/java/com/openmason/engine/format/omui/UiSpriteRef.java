package com.openmason.engine.format.omui;

import java.util.Objects;

/**
 * A reference to one region or skin of a sprite sheet (#294): {@code <sheet id>#<name>}, e.g.
 * {@code stonebreak:ui/sprites/buttons#stone}. The sheet part is a dependency id of kind
 * {@code sprites} and resolves through the document's table like any other reference; the name
 * is the sheet's own identity for the region, so repacking the texture never breaks it.
 */
public record UiSpriteRef(String sheet, String name) {

    public static final char SEPARATOR = '#';

    public UiSpriteRef {
        Objects.requireNonNull(sheet, "sheet");
        Objects.requireNonNull(name, "name");
    }

    /** @return the parsed reference, or null when {@code value} is not of the {@code id#name} form */
    public static UiSpriteRef parse(String value) {
        if (value == null) {
            return null;
        }
        int hash = value.indexOf(SEPARATOR);
        if (hash <= 0 || hash != value.lastIndexOf(SEPARATOR)) {
            return null;
        }
        String sheet = value.substring(0, hash);
        String name = value.substring(hash + 1);
        if (!OmuiFormat.LOGICAL_ID.matcher(sheet).matches() || !OmuiFormat.LOCAL_ID.matcher(name).matches()) {
            return null;
        }
        return new UiSpriteRef(sheet, name);
    }

    /** The dependency id an asset reference names: the sheet of a sprite reference, else the value itself. */
    public static String dependencyId(String value) {
        UiSpriteRef ref = parse(value);
        return ref != null ? ref.sheet() : value;
    }

    @Override
    public String toString() {
        return sheet + SEPARATOR + name;
    }
}
