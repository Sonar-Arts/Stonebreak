package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.UiNode.InstanceOverride;

import java.util.ArrayList;
import java.util.List;

/**
 * The instance overrides that can reach nodes of one component being built, in increasing
 * precedence: overrides authored on the instance itself first, then ones re-rooted from
 * enclosing instances ({@code inner/leaf} seen from inside {@code inner} is {@code leaf}),
 * so the outermost author wins. Usage is tracked on the authored override, so a target that
 * no longer exists in the component's current revision is reported once, where it was written.
 */
final class OverrideSet {

    /** One authored override and where it was written. */
    static final class Authored {
        final InstanceOverride override;
        final String instanceKey;
        boolean used;

        Authored(InstanceOverride override, String instanceKey) {
            this.override = override;
            this.instanceKey = instanceKey;
        }
    }

    private record Entry(String path, Authored authored) {
    }

    static final OverrideSet EMPTY = new OverrideSet(List.of());

    private final List<Entry> entries;

    private OverrideSet(List<Entry> entries) {
        this.entries = entries;
    }

    /** Overrides whose remaining path is exactly {@code nodeId}, lowest precedence first. */
    List<InstanceOverride> forNode(String nodeId) {
        List<InstanceOverride> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.path.equals(nodeId)) {
                e.authored.used = true;
                out.add(e.authored.override);
            }
        }
        return out;
    }

    /**
     * The set for the component behind instance node {@code instanceNodeId}: the instance's
     * own overrides, then this set's entries that pass through it.
     */
    OverrideSet enter(String instanceNodeId, List<Authored> own) {
        List<Entry> next = new ArrayList<>();
        for (Authored a : own) {
            next.add(new Entry(a.override.target(), a));
        }
        String prefix = instanceNodeId + "/";
        for (Entry e : entries) {
            if (e.path.startsWith(prefix)) {
                next.add(new Entry(e.path.substring(prefix.length()), e.authored));
            }
        }
        return new OverrideSet(next);
    }
}
