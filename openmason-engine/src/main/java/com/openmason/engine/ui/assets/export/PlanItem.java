package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.UiDependency.Kind;
import com.openmason.engine.format.omui.WireEnum;
import com.openmason.engine.ui.assets.AssetOrigin;

import java.util.List;
import java.util.Objects;

/**
 * What an export does with one dependency row, with its provenance.
 *
 * @param origin         where the bytes were found, {@code null} when nothing was
 * @param location       portable location in that source, {@code null} when nothing was found
 * @param recordedSha256 hash the document recorded
 * @param sha256         hash of the bytes actually found, {@code null} when nothing was
 * @param size           size of the bytes found (else the recorded size)
 * @param pack           resource pack a shared row must ship in, {@code null} = default root
 * @param fallback       fallback id that serves a missing optional row, else {@code null}
 * @param shadowed       lower-precedence sources that also hold the id with different bytes
 */
public record PlanItem(String id, Kind kind, Action action, boolean optional, AssetOrigin origin, String location,
                       String recordedSha256, String sha256, long size, String pack, String license,
                       String fallback, List<String> shadowed) {

    public enum Action implements WireEnum {
        /** Travels inside the embedded source OMUI. */
        SOURCE_EMBEDDED("source-embedded"),
        /** Collected into the SBUI's own {@code assets/}. */
        COLLECTED("collected"),
        /** Stays shared; must be deployed with the SBUI. */
        SHIPS_SHARED("ships-shared"),
        /** Optional and missing; its fallback serves. */
        FALLBACK("fallback"),
        /** Optional and missing without a usable fallback; the document degrades. */
        OMITTED("omitted"),
        /** Required and missing: the export is blocked. */
        MISSING("missing");

        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    public PlanItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(action, "action");
        shadowed = shadowed == null ? List.of() : List.copyOf(shadowed);
    }
}
