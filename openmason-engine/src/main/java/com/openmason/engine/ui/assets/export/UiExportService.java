package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiFormat;
import com.openmason.engine.format.sbui.SbuiWriter;
import com.openmason.engine.ui.assets.AssetSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Plan, export and write an SBUI with its report: the one call an editor's Export command makes. */
public final class UiExportService {

    /** @param diagnostics plan findings followed by exporter findings */
    public record Result(SbuiArchive sbui, ExportPlan plan, List<UiDiagnostic> diagnostics) {
    }

    private UiExportService() {
    }

    /**
     * @param assetId game-facing id, {@code null} for the document id
     * @throws UiFormatException carrying the plan's findings when the plan is blocked, or the
     *                           exporter's when the export itself is refused
     */
    public static Result export(OmuiArchive doc, List<? extends AssetSource> sources, ExportPlanner.Request request,
                                String assetId, List<SbuiExporter.DerivedInput> derived) throws UiFormatException {
        ExportPlan plan = ExportPlanner.plan(doc, sources, request);
        if (plan.blocked()) {
            throw new UiFormatException("Export blocked", plan.diagnostics());
        }
        SbuiExporter.Export export = SbuiExporter.export(doc, plan.toOptions(assetId, derived));
        List<UiDiagnostic> all = new ArrayList<>(plan.diagnostics());
        all.addAll(export.diagnostics());
        return new Result(export.archive(), plan, all);
    }

    /**
     * Writes {@code target} ({@code .sbui}) and its report beside it, both atomically. The
     * report goes first, so a crash can leave a report without an export but never an export
     * without its report.
     */
    public static void save(Result result, Path target) throws IOException {
        AtomicFiles.write(reportPath(target), ExportReport.json(result.plan()).toArray());
        SbuiWriter.save(result.sbui(), target);
    }

    /** {@code menu.sbui} → {@code menu.report.json}. */
    public static Path reportPath(Path target) {
        String name = target.getFileName().toString();
        String stem = name.endsWith(SbuiFormat.FILE_EXTENSION)
                ? name.substring(0, name.length() - SbuiFormat.FILE_EXTENSION.length())
                : name;
        return target.resolveSibling(stem + ExportReport.SUFFIX);
    }
}
