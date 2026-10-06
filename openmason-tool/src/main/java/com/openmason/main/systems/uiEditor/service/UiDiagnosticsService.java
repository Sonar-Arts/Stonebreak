package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiValidator;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.Resolution;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiReferences;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.script.UiScriptChecker;
import com.openmason.engine.ui.script.UiScriptDiagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every problem of a UI document in one list for the Diagnostics panel: format validation (what
 * the writer would refuse), dependency resolution against the project (missing shared assets),
 * the runtime's diagnostics (unknown widgets, missing components, bad style values), unresolved
 * identity references (clip tracks, graph targets, overrides) and static script findings.
 * Recomputed only when the document revision changes.
 */
public final class UiDiagnosticsService {

    public enum Source { FORMAT, ASSETS, RUNTIME, REFERENCES, SCRIPT, PREVIEW }

    public enum Severity { ERROR, WARNING, INFO }

    /** One finding; {@code element} is an element key to select, or null. {@code line} for scripts. */
    public record Finding(Severity severity, Source source, String element, String message, String detail, int line,
                          String chunk) {
    }

    private final UiProjectContext project;
    private OmuiArchive cachedFor;
    private UiDocumentInstance cachedInstance;
    private List<Finding> cached = List.of();

    public UiDiagnosticsService(UiProjectContext project) {
        this.project = project;
    }

    public List<Finding> findings(OmuiArchive doc, UiDocumentInstance ui) {
        if (doc == cachedFor && ui == cachedInstance) {
            return cached;
        }
        List<Finding> out = new ArrayList<>();
        for (UiDiagnostic d : OmuiValidator.validate(doc)) {
            if (d.severity() == UiDiagnostic.Severity.INFO) {
                continue;
            }
            out.add(new Finding(map(d.severity()), Source.FORMAT, element(doc, d.pointer()), d.message(),
                d.entry() + d.pointer(), 0, null));
        }
        try {
            Resolution r = AssetResolver.forDocument(doc, project.sources()).resolveAll();
            for (UiDiagnostic d : r.diagnostics()) {
                if (d.severity() != UiDiagnostic.Severity.INFO) {
                    out.add(new Finding(map(d.severity()), Source.ASSETS, null, d.message(), d.entry(), 0, null));
                }
            }
            // #294: missing/invalid sprite regions and resized textures, as the export reports them
            for (UiDiagnostic d : com.openmason.engine.ui.assets.export.SpriteChecks.check(doc, r)) {
                if (d.severity() != UiDiagnostic.Severity.INFO) {
                    out.add(new Finding(map(d.severity()), Source.ASSETS, null, d.message(),
                        d.entry() + d.pointer(), 0, null));
                }
            }
        } catch (RuntimeException e) {
            out.add(new Finding(Severity.WARNING, Source.ASSETS, null, "Dependency check failed: " + e.getMessage(), "",
                0, null));
        }
        if (ui != null) {
            for (UiRuntimeDiagnostic d : ui.diagnostics()) {
                out.add(new Finding(map(d.severity()), Source.RUNTIME, emptyToNull(d.element()), d.message(),
                    d.code().name(), 0, null));
            }
            for (UiRuntimeDiagnostic d : UiReferences.unresolved(ui)) {
                out.add(new Finding(map(d.severity()), Source.REFERENCES, emptyToNull(d.element()), d.message(),
                    d.code().name(), 0, null));
            }
        }
        for (Map.Entry<String, String> s : doc.scripts().entrySet()) {
            for (UiScriptDiagnostic d : UiScriptChecker.check(s.getValue(), s.getKey() + ".lua")) {
                out.add(new Finding(map(d.severity()), Source.SCRIPT, emptyToNull(d.element()), d.headline(),
                    d.location(), d.line(), s.getKey()));
            }
        }
        cachedFor = doc;
        cachedInstance = ui;
        cached = List.copyOf(out);
        return cached;
    }

    /** Forgets the cache (the runtime re-reported after a reload). */
    public void invalidate() {
        cachedFor = null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** {@code /root/children/1/children/0/...} → the node id at that pointer, when it is a node. */
    static String element(OmuiArchive doc, String pointer) {
        if (pointer == null || !pointer.startsWith("/root")) {
            return null;
        }
        var node = doc.document().root();
        String[] parts = pointer.substring(1).split("/");
        String last = node.id();
        for (int i = 1; i + 1 < parts.length; i += 2) {
            if (!"children".equals(parts[i])) {
                break;
            }
            try {
                node = node.children().get(Integer.parseInt(parts[i + 1]));
                last = node.id();
            } catch (RuntimeException e) {
                break;
            }
        }
        return last;
    }

    private static Severity map(UiDiagnostic.Severity s) {
        return switch (s) {
            case ERROR -> Severity.ERROR;
            case WARNING -> Severity.WARNING;
            case INFO -> Severity.INFO;
        };
    }

    private static Severity map(UiRuntimeDiagnostic.Severity s) {
        return switch (s) {
            case ERROR -> Severity.ERROR;
            case WARNING -> Severity.WARNING;
            case INFO -> Severity.INFO;
        };
    }

    private static Severity map(UiScriptDiagnostic.Severity s) {
        return switch (s) {
            case ERROR -> Severity.ERROR;
            case WARNING -> Severity.WARNING;
            case INFO -> Severity.INFO;
        };
    }
}
