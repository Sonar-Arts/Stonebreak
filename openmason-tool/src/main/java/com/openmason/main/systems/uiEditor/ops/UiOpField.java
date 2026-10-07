package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The JSON shape an op field must have. Validation checks shapes only (no document access), so
 * a malformed batch is refused before anything runs; meaning (does the element exist, is the
 * selector valid for this sheet) is checked when the batch executes.
 */
enum UiOpField {
    /** Non-blank string. */
    STRING("a string"),
    /** String, or null to clear. */
    NULLABLE_STRING("a string or null"),
    INT("an integer"),
    BOOL("true or false"),
    /** Any JSON value; null clears where the op says so. */
    ANY("any JSON value"),
    OBJECT("a JSON object"),
    STRING_LIST("an array of strings"),
    /** Element key, or {@code $alias} / {@code $alias/inner} bound by an earlier op's {@code as}. */
    KEY("an element key or $alias"),
    /** One key, or an array of keys. */
    KEYS("an element key or an array of them"),
    /** Rule index (integer) or exact selector text. */
    RULE("a rule index or its selector"),
    /** Array of {target, path, mode?, converter?} objects. */
    BINDINGS("an array of {target, path, mode?, converter?}");

    final String expected;

    UiOpField(String expected) {
        this.expected = expected;
    }

    /** True when {@code n} has this field's shape. */
    boolean accepts(JsonNode n) {
        return switch (this) {
            case STRING, KEY -> n.isTextual() && !n.asText().isBlank();
            case NULLABLE_STRING -> n.isNull() || n.isTextual();
            case INT -> n.isIntegralNumber() && n.canConvertToInt();
            case BOOL -> n.isBoolean();
            case ANY -> true;
            case OBJECT -> n.isObject();
            case STRING_LIST -> n.isArray() && allText(n);
            case KEYS -> n.isTextual() && !n.asText().isBlank() || n.isArray() && !n.isEmpty() && allText(n);
            case RULE -> n.isIntegralNumber() && n.canConvertToInt() || n.isTextual() && !n.asText().isBlank();
            case BINDINGS -> n.isArray();
        };
    }

    private static boolean allText(JsonNode arr) {
        for (JsonNode i : arr) {
            if (!i.isTextual() || i.asText().isBlank()) {
                return false;
            }
        }
        return true;
    }
}
