package com.openmason.engine.format.sbe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Compatibility checks between an animation clip's required parts and a
 * model's available parts.
 *
 * <p>The source of truth for a clip's part requirements is its track list.
 * Both the OMA manifest ({@code requiredParts}) and the SBE manifest's
 * {@link SBEFormat.AnimationRef#requiredParts()} are derived snapshots of
 * that list, persisted so downstream consumers can validate compatibility
 * without unpacking every track file.
 *
 * <p>This class is intentionally a pure utility — no I/O, no logging — so it
 * can be reused by the editor (warn at authoring time), the SBE export window
 * (block invalid exports), and the runtime (reject clips that don't match the
 * loaded model).
 */
public final class AnimationCompatibility {

    private AnimationCompatibility() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Result of a compatibility check.
     *
     * @param missingParts partIds the clip needs that the model does not provide
     * @param extraParts   partIds the model provides that the clip does not animate;
     *                     informational only — not a compatibility failure
     */
    public record Result(List<String> missingParts, List<String> extraParts) {
        public Result {
            missingParts = missingParts == null ? Collections.emptyList() : List.copyOf(missingParts);
            extraParts = extraParts == null ? Collections.emptyList() : List.copyOf(extraParts);
        }

        /** True when the clip can be played against the model without dropping animated parts. */
        public boolean isCompatible() {
            return missingParts.isEmpty();
        }

        /**
         * Returns a short, human-readable summary of the missing parts.
         * Returns an empty string when {@link #isCompatible()} is true.
         */
        public String describeMissing() {
            if (missingParts.isEmpty()) return "";
            return String.join(", ", missingParts);
        }
    }

    /**
     * Check whether a clip's required parts are all present in the model.
     *
     * @param requiredParts  partIds the clip animates (typically from
     *                       {@link SBEFormat.AnimationRef#requiredParts()}
     *                       or the OMA manifest's {@code requiredParts})
     * @param availableParts partIds the model exposes
     * @return a result detailing missing and extra parts
     */
    public static Result check(Collection<String> requiredParts, Collection<String> availableParts) {
        Set<String> required = toSet(requiredParts);
        Set<String> available = toSet(availableParts);

        List<String> missing = new ArrayList<>();
        for (String part : required) {
            if (!available.contains(part)) missing.add(part);
        }

        List<String> extra = new ArrayList<>();
        for (String part : available) {
            if (!required.contains(part)) extra.add(part);
        }

        return new Result(missing, extra);
    }

    /** Convenience: check a single SBE animation reference against a model's parts. */
    public static Result check(SBEFormat.AnimationRef ref, Collection<String> availableParts) {
        return check(ref.requiredParts(), availableParts);
    }

    /**
     * Probe an OMA file for its declared {@code requiredParts}. Falls back to
     * enumerating track refs when the field is absent, matching the same
     * heuristic the SBE serializer uses when bundling clips from older files.
     */
    public static List<String> readOMARequiredParts(Path omanim) throws IOException {
        try (InputStream in = new FileInputStream(omanim.toFile())) {
            byte[] manifest = readZipEntry(in, "manifest.json");
            if (manifest == null) return Collections.emptyList();
            JsonNode root = MAPPER.readTree(manifest);

            List<String> parts = new ArrayList<>();
            JsonNode required = root.get("requiredParts");
            if (required != null && required.isArray()) {
                for (JsonNode p : required) {
                    if (p != null && !p.isNull()) parts.add(p.asText());
                }
                return parts;
            }

            JsonNode tracks = root.get("tracks");
            if (tracks != null && tracks.isArray()) {
                for (JsonNode t : tracks) {
                    JsonNode partId = t.get("partId");
                    if (partId != null && !partId.isNull()) parts.add(partId.asText());
                }
            }
            return parts;
        }
    }

    /**
     * Probe an OMO file for its part IDs. Reads the top-level {@code parts}
     * array in {@code manifest.json}; returns an empty list when the model has
     * no explicit part list (single implicit root part, pre-v1.3 OMOs).
     */
    public static List<String> readOMOPartIds(Path omo) throws IOException {
        try (InputStream in = new FileInputStream(omo.toFile())) {
            byte[] manifest = readZipEntry(in, "manifest.json");
            if (manifest == null) return Collections.emptyList();
            JsonNode root = MAPPER.readTree(manifest);

            List<String> ids = new ArrayList<>();
            JsonNode parts = root.get("parts");
            if (parts != null && parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode id = part.get("id");
                    if (id != null && !id.isNull()) ids.add(id.asText());
                }
            }
            return ids;
        }
    }

    private static byte[] readZipEntry(InputStream zipStream, String name) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = zis.read(buffer)) > 0) out.write(buffer, 0, n);
                    return out.toByteArray();
                }
                zis.closeEntry();
            }
        }
        return null;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Set<String> toSet(Collection<String> source) {
        if (source == null) return Collections.emptySet();
        Set<String> set = new LinkedHashSet<>(source.size());
        for (String s : source) {
            if (s != null && !s.isBlank()) set.add(s);
        }
        return set;
    }
}
