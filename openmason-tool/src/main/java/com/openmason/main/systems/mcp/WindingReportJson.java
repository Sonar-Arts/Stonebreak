package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.model.gmr.analysis.WindingAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared JSON shaping for winding reports — used by both
 * {@code asset_check_winding} (file-based) and {@code model_check_winding}
 * (live model) so clients see one report format.
 */
final class WindingReportJson {

    private WindingReportJson() {
    }

    /** Aggregate part reports into the documented {status, parts, totals, hints} shape. */
    static Map<String, Object> aggregate(List<WindingAnalyzer.PartReport> reports) {
        List<Map<String, Object>> parts = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        int inverted = 0;
        int degenerate = 0;
        int violations = 0;
        int indeterminate = 0;
        for (WindingAnalyzer.PartReport r : reports) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("part", r.part());
            row.put("faces", r.faceCount());
            row.put("outward", r.outward());
            row.put("inward", r.inward());
            row.put("indeterminate", r.indeterminate());
            row.put("mixedWinding", r.mixedWinding());
            row.put("closed", r.closed());
            row.put("signedVolume", AssetLensService.round4(r.signedVolume()));
            if (r.indeterminateReason() != null) {
                row.put("reason", r.indeterminateReason());
            }
            putIds(row, "inverted", r.invertedFaceIds());
            putIds(row, "degenerate", r.degenerateFaceIds());
            putIds(row, "nonPlanar", r.nonPlanarFaceIds());
            putIds(row, "contractViolations", r.contractViolationFaceIds());
            parts.add(row);
            hints.addAll(r.hints());
            inverted += r.invertedFaceIds().length;
            degenerate += r.degenerateFaceIds().length;
            violations += r.contractViolationFaceIds().length;
            indeterminate += r.indeterminate();
        }
        String status = inverted + degenerate + violations > 0 ? "errors"
                : indeterminate > 0 ? "warnings" : "ok";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("parts", parts);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("parts", parts.size());
        totals.put("inverted", inverted);
        totals.put("degenerate", degenerate);
        totals.put("contractViolations", violations);
        totals.put("indeterminate", indeterminate);
        out.put("totals", totals);
        if (!hints.isEmpty()) {
            out.put("hints", hints);
        }
        return out;
    }

    private static void putIds(Map<String, Object> row, String key, int[] ids) {
        if (ids.length > 0) {
            row.put(key, ids);
        }
    }
}
