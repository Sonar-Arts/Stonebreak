package com.openmason.engine.format.omui;

/**
 * Hard bounds applied while reading an archive, so a hostile or corrupt file costs bounded
 * memory and time. Sizes are measured on the inflated bytes as they stream, never trusted
 * from ZIP headers.
 *
 * @param maxEntries     entries per archive (directories included)
 * @param maxEntryBytes  inflated size of any single entry
 * @param maxTotalBytes  inflated size of all entries together
 * @param maxJsonBytes   inflated size of any JSON entry
 * @param maxNodes       nodes per document tree (component slot content included)
 * @param maxTreeDepth   nesting depth of a document tree
 * @param maxGraphNodes  nodes across one graph (functions included)
 */
public record ArchiveLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes, long maxJsonBytes,
                            int maxNodes, int maxTreeDepth, int maxGraphNodes) {

    public static final ArchiveLimits DEFAULT = new ArchiveLimits(
            4096, 64L << 20, 256L << 20, 8L << 20, 20_000, 48, 10_000);
}
