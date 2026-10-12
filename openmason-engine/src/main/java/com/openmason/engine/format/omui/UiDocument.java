package com.openmason.engine.format.omui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code document.json}: the declarative tree plus the document-level references.
 *
 * @param root        root node
 * @param styleSheets style sheets applied to this document in precedence order (later wins
 *                    on equal specificity); each is an in-archive id or a dependency id
 * @param codeBehind  Lua module (in-archive script id or dependency id), or {@code null}
 * @param component   the component contract when the manifest kind is {@code component}
 * @param unknown     preserved fields this reader does not know
 */
public record UiDocument(UiNode root, List<String> styleSheets, String codeBehind, ComponentDef component,
                         Map<String, UiValue> unknown) {

    public UiDocument {
        Objects.requireNonNull(root, "root");
        styleSheets = Canon.list(styleSheets);
        codeBehind = codeBehind == null || codeBehind.isEmpty() ? null : codeBehind;
        unknown = Canon.unknown(unknown);
    }

    /**
     * What a reusable component exposes to the documents that instantiate it.
     *
     * @param params typed parameters in inspector order
     * @param events events the component raises, in declaration order
     * @param slots  named child slots in declaration order
     */
    public record ComponentDef(List<Param> params, List<EventDef> events, List<Slot> slots,
                               Map<String, UiValue> unknown) {
        public ComponentDef {
            params = Canon.list(params);
            events = Canon.list(events);
            slots = Canon.list(slots);
            unknown = Canon.unknown(unknown);
        }
    }

    /** A typed parameter or event argument; {@code defaultValue} is {@code null} when absent. */
    public record Param(String name, ValueType type, UiValue defaultValue, Map<String, UiValue> unknown) {
        public Param {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            defaultValue = Canon.value(defaultValue);
            unknown = Canon.unknown(unknown);
        }

        public Param(String name, ValueType type) {
            this(name, type, null, Map.of());
        }
    }

    public record EventDef(String name, List<Param> args, Map<String, UiValue> unknown) {
        public EventDef {
            Objects.requireNonNull(name, "name");
            args = Canon.list(args);
            unknown = Canon.unknown(unknown);
        }
    }

    /**
     * A named slot.
     *
     * @param host id of the node inside the component that receives slot content as children
     */
    public record Slot(String name, String host, Map<String, UiValue> unknown) {
        public Slot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(host, "host");
            unknown = Canon.unknown(unknown);
        }
    }
}
