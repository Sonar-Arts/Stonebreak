package com.openmason.api.target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The folders a {@link GameTarget} keeps each asset kind in, relative to its resource tree (#380).
 *
 * <p>Folders are keyed by file type id ({@code sbo}: the type's base folder) and by
 * {@code <type>.<subtype>} ({@code sbo.block}: the folder of one manifest type, matched
 * case-insensitively). File types' save rules name these keys, so each target maps them to its
 * own layout.
 */
public final class AssetFolders {

    /** No folders: a target that only lends its namespace or UI host. */
    public static final AssetFolders NONE = builder().build();

    private final Map<String, String> folders;

    private AssetFolders(Map<String, String> folders) {
        this.folders = Collections.unmodifiableMap(new LinkedHashMap<>(folders));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Every folder by key, in declaration order. */
    public Map<String, String> folders() {
        return folders;
    }

    /** The folder for {@code key} ({@code sbo} or {@code sbo.block}), or null when undeclared. */
    public String folder(String key) {
        return key == null ? null : folders.get(key.toLowerCase(Locale.ROOT));
    }

    /**
     * The folder {@code type} assets of manifest type {@code subtype} ship from: the subtype's
     * folder when one is declared, else the type's base folder; null when neither is.
     */
    public String folderFor(String type, String subtype) {
        String specific = subtype == null || subtype.isBlank() ? null : folder(key(type, subtype));
        return specific != null ? specific : folder(type);
    }

    /** Every folder {@code type} assets live in: its base folder, then its subtypes', no repeats. */
    public List<String> foldersOf(String type) {
        List<String> out = new ArrayList<>();
        String base = folder(type);
        if (base != null) {
            out.add(base);
        }
        String prefix = type.toLowerCase(Locale.ROOT) + ".";
        for (Map.Entry<String, String> e : folders.entrySet()) {
            if (e.getKey().startsWith(prefix) && !out.contains(e.getValue())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** The subtype whose folder is exactly {@code folder} ({@code sbe/Mobs} → {@code mob}), or null. */
    public String subtypeOf(String type, String folder) {
        if (folder == null) {
            return null;
        }
        String f = trimSlashes(folder);
        String prefix = type.toLowerCase(Locale.ROOT) + ".";
        for (Map.Entry<String, String> e : folders.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue().equalsIgnoreCase(f)) {
                return e.getKey().substring(prefix.length());
            }
        }
        return null;
    }

    private static String key(String type, String subtype) {
        return (type + "." + subtype.trim()).toLowerCase(Locale.ROOT);
    }

    private static String trimSlashes(String s) {
        String out = s.replace('\\', '/');
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public static final class Builder {

        private final Map<String, String> folders = new LinkedHashMap<>();

        private Builder() {
        }

        /** {@code type}'s base folder (where assets without a subtype folder go). */
        public Builder base(String type, String folder) {
            folders.put(type.toLowerCase(Locale.ROOT), trimSlashes(folder));
            return this;
        }

        /** The folder of {@code type} assets whose manifest type is {@code subtype}. */
        public Builder subtype(String type, String subtype, String folder) {
            folders.put(key(type, subtype), trimSlashes(folder));
            return this;
        }

        public AssetFolders build() {
            return new AssetFolders(folders);
        }
    }
}
