package com.openmason.main.systems.assets;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small LRU cache of {@link AssetModelDigest}s keyed by
 * {@code (path, mtime, size, state, variant)} — a touched file re-digests
 * automatically, an untouched one parses once per session window.
 */
public final class AssetParseCache {

    private static final int MAX_ENTRIES = 8;

    private record Key(String path, long mtime, long size, String state, String variant) {
    }

    private final Map<Key, AssetModelDigest> cache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, AssetModelDigest> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    public synchronized AssetModelDigest get(AssetEntry entry, String state, String variant)
            throws IOException {
        long mtime;
        long size;
        try {
            mtime = Files.getLastModifiedTime(entry.sourcePath()).toMillis();
            size = Files.size(entry.sourcePath());
        } catch (IOException e) {
            throw new IOException("asset file unreadable: " + entry.sourcePath(), e);
        }
        Key key = new Key(entry.sourcePath().toString(), mtime, size, state, variant);
        AssetModelDigest digest = cache.get(key);
        if (digest == null) {
            digest = AssetModelDigest.build(entry, state, variant);
            cache.put(key, digest);
        }
        return digest;
    }

    public synchronized void clear() {
        cache.clear();
    }
}
