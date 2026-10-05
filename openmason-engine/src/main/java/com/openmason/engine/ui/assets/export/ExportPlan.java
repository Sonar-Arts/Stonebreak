package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.sbui.SbuiExporter;

import java.util.List;
import java.util.Map;

/**
 * A computed export: what happens to every dependency, which host contracts the result
 * needs, and whether it may proceed. Plans are pure data — computing one writes nothing.
 *
 * @param items       one per dependency row, in id order
 * @param hostApis    host contracts the document and its components need (union), listed apart
 *                    from transported artwork: they ship with the host, not the SBUI
 * @param providers   widget/draw providers, same rule
 * @param collected   dependency id → bytes the SBUI will carry (collect-all)
 * @param packs       shared dependency id → resource pack it ships in
 * @param diagnostics findings; any error blocks the export
 */
public record ExportPlan(String documentId, ExportMode mode, List<PlanItem> items, List<HostRequirement> hostApis,
                         List<HostRequirement> providers, Map<String, UiBytes> collected, Map<String, String> packs,
                         List<UiDiagnostic> diagnostics) {

    public ExportPlan {
        items = List.copyOf(items);
        hostApis = List.copyOf(hostApis);
        providers = List.copyOf(providers);
        collected = Map.copyOf(collected);
        packs = Map.copyOf(packs);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean blocked() {
        return diagnostics.stream().anyMatch(UiDiagnostic::isError);
    }

    public PlanItem item(String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }

    /** Shared resources that must be deployed alongside the SBUI for it to run. */
    public List<PlanItem> mustShip() {
        return items.stream().filter(i -> i.action() == PlanItem.Action.SHIPS_SHARED).toList();
    }

    /** Exporter options that realize this plan. */
    public SbuiExporter.Options toOptions(String assetId, List<SbuiExporter.DerivedInput> derived) {
        return new SbuiExporter.Options(assetId, collected, mode == ExportMode.COLLECT_ALL, packs, derived);
    }
}
