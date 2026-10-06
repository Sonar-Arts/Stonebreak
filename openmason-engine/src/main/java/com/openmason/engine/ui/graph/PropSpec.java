package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;

import java.util.List;
import java.util.Objects;

/**
 * A node property: settings that are not data ports (the targeted element, the called function,
 * the variable). The editor's inspector picks a widget by {@link Kind}; the validator checks
 * {@link #required()} and {@link #options()}.
 */
public record PropSpec(String name, Kind kind, boolean required, UiValue defaultValue, List<String> options,
                       String doc) {

    public enum Kind {
        /** Free text. */
        TEXT,
        /** An identifier (custom event, class, signal name). */
        IDENT,
        /** A node-id path of an element in the document. */
        ELEMENT,
        /** A graph variable name. */
        VARIABLE,
        /** A graph function id. */
        FUNCTION,
        /** A Lua function name ({@code module} prop beside it). */
        LUA_FUNCTION,
        /** A module name {@code require} resolves; empty = the code-behind. */
        MODULE,
        /** A timeline clip id. */
        CLIP,
        /** One of {@link #options()}. */
        ENUM,
        BOOL,
        INT,
        /** {@code [{name, type}]}: typed parameters (custom events). */
        PARAMS,
        /** {@code [name]}: argument names (actions). */
        NAMES,
        /** A host data path ({@code session.online}). */
        DATA_PATH,
        /** A host action id ({@code stonebreak:screen.pause.resume}). */
        ACTION,
        /** A style property ({@code opacity}). */
        STYLE_PROPERTY,
        /** A widget property ({@code text}). */
        WIDGET_PROPERTY,
        /** A component signal of the target instance (or of this component). */
        SIGNAL,
        /** A UI event name ({@code click}, {@code pointer-enter}). */
        EVENT_NAME,
        /** A text template with {@code {name}} placeholders. */
        TEMPLATE
    }

    public PropSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        options = options == null ? List.of() : List.copyOf(options);
        doc = doc == null ? "" : doc;
    }

    public static PropSpec required(String name, Kind kind, String doc) {
        return new PropSpec(name, kind, true, null, List.of(), doc);
    }

    public static PropSpec optional(String name, Kind kind, UiValue defaultValue, String doc) {
        return new PropSpec(name, kind, false, defaultValue, List.of(), doc);
    }

    public static PropSpec choice(String name, String defaultValue, List<String> options, String doc) {
        return new PropSpec(name, Kind.ENUM, false, UiValue.of(defaultValue), options, doc);
    }
}
