package com.openmason.engine.ui.data;

import java.util.Objects;

/**
 * Where a data access or action call came from (#289), so a contract mismatch names the
 * document and node an author can fix instead of a Java stack trace.
 *
 * @param documentId logical id of the running document ({@code stonebreak:ui/pause_menu})
 * @param elementKey element key inside it ({@code resync}, {@code quit/label}), or {@code ""}
 * @param origin     which authoring surface made the call
 */
public record CallSite(String documentId, String elementKey, Origin origin) {

    /** The authoring surfaces that share one host contract. */
    public enum Origin {
        /** A declarative (inspector) binding. */
        BINDING,
        /** Lua code-behind (#292). */
        SCRIPT,
        /** A compiled graph (#291); runs as Lua, reported separately for source mapping. */
        GRAPH,
        /** Host Java code or a test. */
        HOST
    }

    public CallSite {
        documentId = documentId == null ? "" : documentId;
        elementKey = elementKey == null ? "" : elementKey;
        Objects.requireNonNull(origin, "origin");
    }

    public static CallSite host(String documentId) {
        return new CallSite(documentId, "", Origin.HOST);
    }

    public CallSite at(String key, Origin o) {
        return new CallSite(documentId, key, o);
    }

    /** {@code document stonebreak:ui/pause_menu, node resync (script)}. */
    public String describe() {
        return "document " + (documentId.isEmpty() ? "?" : documentId)
            + (elementKey.isEmpty() ? "" : ", node " + elementKey)
            + " (" + origin.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }
}
