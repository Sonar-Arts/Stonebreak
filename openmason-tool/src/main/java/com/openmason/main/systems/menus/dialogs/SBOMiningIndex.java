package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mining data (SBO 1.9) gathered across every shipped SBO, for the SBO
 * editor's Tool tab (which materials exist) and the block Mining section
 * (live break-time preview per known tool).
 *
 * <p>Built once on first use by parsing every SBO the {@link SBOObjectIndex}
 * knows about; {@link #refresh()} rebuilds it (e.g. after saving a tool). The
 * {@link #of} factory is pure so the aggregation is testable without disk.
 */
public final class SBOMiningIndex {

    private static final Logger logger = LoggerFactory.getLogger(SBOMiningIndex.class);

    /** One mining tool found on disk. */
    public record KnownTool(String objectId, String displayName, SBOFormat.ToolData tool) {}

    private static volatile SBOMiningIndex shared;

    private final List<String> materials;
    private final List<KnownTool> tools;

    private SBOMiningIndex(List<String> materials, List<KnownTool> tools) {
        this.materials = List.copyOf(materials);
        this.tools = List.copyOf(tools);
    }

    /** Aggregates block materials and item tools from parsed manifests. */
    public static SBOMiningIndex of(List<SBOFormat.Document> manifests) {
        Set<String> materials = new TreeSet<>();
        List<KnownTool> tools = new ArrayList<>();
        for (SBOFormat.Document d : manifests) {
            if (d.gameProperties() != null && d.gameProperties().material() != null) {
                materials.add(d.gameProperties().material());
            }
            if (d.tool() != null) {
                tools.add(new KnownTool(d.objectId(), d.objectName(), d.tool()));
                materials.addAll(d.tool().materials());
            }
        }
        tools.sort(Comparator.comparing((KnownTool t) -> t.tool().toolClass())
                .thenComparingInt(t -> t.tool().tier())
                .thenComparing(KnownTool::objectId));
        return new SBOMiningIndex(new ArrayList<>(materials), tools);
    }

    /** The shared index, scanned on first use. */
    public static SBOMiningIndex shared() {
        SBOMiningIndex idx = shared;
        if (idx == null) {
            idx = scan();
            shared = idx;
        }
        return idx;
    }

    /** Re-scans the SBOs on disk. */
    public static void refresh() {
        SBOObjectIndex.refresh();
        shared = scan();
    }

    /** Drops the cached index; the next {@link #shared()} call re-reads the SBOs. */
    public static void invalidate() {
        shared = null;
    }

    /** Every known material name (blocks' and tools'), sorted. */
    public List<String> materials() {
        return materials;
    }

    /** Every known mining tool, grouped by class then tier. */
    public List<KnownTool> tools() {
        return tools;
    }

    private static SBOMiningIndex scan() {
        SBOParser parser = new SBOParser();
        List<SBOFormat.Document> manifests = new ArrayList<>();
        for (SBOObjectIndex.Entry e : SBOObjectIndex.listAll()) {
            if (e.sourcePath() == null) continue;
            try {
                manifests.add(parser.parseRaw(e.sourcePath()).manifest());
            } catch (Exception ex) {
                logger.debug("Skipping unreadable SBO {} for the mining index: {}", e.sourcePath(), ex.getMessage());
            }
        }
        return of(manifests);
    }
}
