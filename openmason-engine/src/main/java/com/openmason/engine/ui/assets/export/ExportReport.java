package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.ObjWriter;

/**
 * The dependency/provenance report written next to an export ({@code <name>.report.json}):
 * every dependency with its action, source, hashes, pack and licence; the host contracts the
 * export needs; and all findings. Canonical JSON, so the same plan always yields the same bytes
 * and reports diff cleanly in review.
 */
public final class ExportReport {

    public static final String FORMAT = "omui-export-report";
    public static final int VERSION = 1;
    public static final String SUFFIX = ".report.json";

    private ExportReport() {
    }

    public static UiBytes json(ExportPlan plan) {
        UiValue.Obj report = new ObjWriter()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("document", plan.documentId())
                .put("mode", plan.mode() == ExportMode.SHARED ? "shared" : "collect-all")
                .put("blocked", UiValue.of(plan.blocked()))
                .putList("dependencies", plan.items(), ExportReport::item)
                .putList("hostApis", plan.hostApis(), ExportReport::host)
                .putList("providers", plan.providers(), ExportReport::host)
                .putList("diagnostics", plan.diagnostics(), ExportReport::diagnostic)
                .build();
        return UiBytes.copyOf(CanonicalJson.write(report));
    }

    private static UiValue item(PlanItem i) {
        ObjWriter w = new ObjWriter()
                .put("id", i.id())
                .putEnum("kind", i.kind())
                .putEnum("action", i.action())
                .putIfNot("optional", i.optional(), false);
        putOpt(w, "source", i.location());
        w.put("recordedSha256", i.recordedSha256());
        putOpt(w, "sha256", i.sha256());
        w.put("size", i.size());
        putOpt(w, "pack", i.pack());
        putOpt(w, "license", i.license());
        putOpt(w, "fallback", i.fallback());
        return w.putStrings("shadowed", i.shadowed()).build();
    }

    private static UiValue host(HostRequirement h) {
        return new ObjWriter().put("id", h.id()).put("version", h.version())
                .putIfNot("optional", h.optional(), false).build();
    }

    private static UiValue diagnostic(UiDiagnostic d) {
        ObjWriter w = new ObjWriter().put("severity", d.severity().name().toLowerCase(java.util.Locale.ROOT))
                .put("code", d.code().name());
        if (!d.entry().isEmpty()) {
            w.put("entry", d.entry());
        }
        if (!d.pointer().isEmpty()) {
            w.put("pointer", d.pointer());
        }
        return w.put("message", d.message()).build();
    }

    private static void putOpt(ObjWriter w, String key, String value) {
        if (value != null) {
            w.put(key, value);
        }
    }
}
