package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDependency.Kind;
import com.openmason.engine.format.sbui.SbuiManifest.Location;
import com.openmason.engine.format.sbui.SbuiManifest.SbuiDependency;

import java.util.List;

/**
 * A dependency row as the resolver sees it, whether it came from an OMUI table (editor) or an
 * SBUI table (runtime).
 *
 * @param placement where the bytes are: inside the document, inside the export, or shared
 * @param entry     archive entry for document/export placement, else {@code null}
 * @param pack      resource pack a shared row ships in ({@code null} = default resource root)
 */
public record AssetRow(String id, Kind kind, Placement placement, String entry, String pack, String sha256,
                       long size, String sourceHint, List<String> requires, boolean optional, String fallback,
                       String license) {

    public enum Placement {
        /** Embedded snapshot inside the OMUI. */
        DOCUMENT,
        /** Collected into the SBUI at export. */
        EXPORT,
        /** Resolved by id through the host's sources. */
        SHARED
    }

    public static AssetRow of(UiDependency d) {
        Placement p = d.mode() == UiDependency.Mode.EMBEDDED ? Placement.DOCUMENT : Placement.SHARED;
        return new AssetRow(d.id(), d.kind(), p, d.entry(), null, d.sha256(), d.size(), d.sourceHint(), d.requires(),
                d.optional(), d.fallback(), d.license());
    }

    /** SBUI rows carry no source hint: a runtime never consults one. */
    public static AssetRow of(SbuiDependency d) {
        Placement p = d.mode() == UiDependency.Mode.SHARED ? Placement.SHARED
                : d.location() == Location.SBUI ? Placement.EXPORT : Placement.DOCUMENT;
        return new AssetRow(d.id(), d.kind(), p, d.entry(), d.pack(), d.sha256(), d.size(), null, d.requires(),
                d.optional(), d.fallback(), d.license());
    }

    public boolean shared() {
        return placement == Placement.SHARED;
    }
}
