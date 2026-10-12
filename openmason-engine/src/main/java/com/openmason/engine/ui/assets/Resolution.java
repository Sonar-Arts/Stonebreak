package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiDiagnostic;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of resolving a set of dependency ids.
 *
 * @param assets      requested id → the asset that serves it (for a missing optional
 *                    dependency, its fallback's asset)
 * @param fallbacks   requested id → fallback id that served it
 * @param missing     requested ids nothing serves (optional ones without a usable fallback,
 *                    and required ones, which are also errors)
 * @param diagnostics everything worth reporting; any error means a required dependency failed
 */
public record Resolution(Map<String, ResolvedAsset> assets, Map<String, String> fallbacks, Set<String> missing,
                         List<UiDiagnostic> diagnostics) {

    public Resolution {
        assets = Map.copyOf(assets);
        fallbacks = Map.copyOf(fallbacks);
        missing = Set.copyOf(missing);
        diagnostics = List.copyOf(diagnostics);
    }

    public ResolvedAsset get(String id) {
        return assets.get(id);
    }

    /** True when every required dependency resolved. */
    public boolean complete() {
        return diagnostics.stream().noneMatch(UiDiagnostic::isError);
    }
}
