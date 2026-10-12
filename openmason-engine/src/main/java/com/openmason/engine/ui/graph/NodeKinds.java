package com.openmason.engine.ui.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The node kinds this compiler knows (#291), by id. A kind's {@link NodeKind#version()} is the
 * newest {@code kindVersion} it reads; a node saved by a newer version is refused before
 * compiling ({@code NEWER_KIND_VERSION}), so an old host never runs semantics it does not know.
 */
public final class NodeKinds {

    private static final Map<String, NodeKind> KINDS;

    static {
        Map<String, NodeKind> m = new LinkedHashMap<>();
        List<NodeKind> all = new ArrayList<>();
        all.addAll(EventKinds.all());
        all.addAll(FlowKinds.all());
        all.addAll(ValueKinds.all());
        all.addAll(ElementKinds.all());
        all.addAll(HostKinds.all());
        all.addAll(CallKinds.all());
        for (NodeKind k : all) {
            if (m.put(k.id(), k) != null) {
                throw new IllegalStateException("duplicate node kind " + k.id());
            }
        }
        KINDS = Collections.unmodifiableMap(m);
    }

    private NodeKinds() {
    }

    /** The kind with this id, or null. */
    public static NodeKind get(String id) {
        return KINDS.get(id);
    }

    /** Every kind, grouped by category in registration order. */
    public static List<NodeKind> all() {
        return List.copyOf(KINDS.values());
    }

    public static boolean isEntry(String kind) {
        return CallKinds.ENTRY.equals(kind);
    }

    public static boolean isReturn(String kind) {
        return CallKinds.RETURN.equals(kind);
    }

    /** The kind id of a graph function call node. */
    public static String functionCall() {
        return CallKinds.CALL;
    }

    /** The kind id of a Lua call node. */
    public static String luaCall() {
        return CallKinds.LUA_CALL;
    }
}
