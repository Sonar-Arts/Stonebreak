package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/** {@code dependencies.json} ↔ {@link UiDependencies}. */
public final class DependencyCodec {

    private DependencyCodec() {
    }

    public static UiDependencies read(UiValue root, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, OmuiFormat.DEPENDENCIES, "", d);
        List<UiDependency> rows = new ArrayList<>();
        List<ObjReader> raw = r.objects("dependencies");
        if (raw.size() > OmuiFormat.MAX_DEPENDENCIES) {
            r.error(Code.LIMIT_EXCEEDED, "dependencies", "More than " + OmuiFormat.MAX_DEPENDENCIES + " rows");
            return new UiDependencies(List.of(), r.unknown());
        }
        for (ObjReader row : raw) {
            rows.add(readRow(row));
        }
        return new UiDependencies(rows, r.unknown());
    }

    public static UiDependency readRow(ObjReader row) {
        return new UiDependency(
                row.requiredString("id"),
                row.requiredEnum("kind", UiDependency.Kind.class, UiDependency.Kind.IMAGE),
                row.optionalString("version", null),
                row.requiredString("sha256"),
                row.optionalLong("size", 0, 0),
                row.requiredEnum("mode", UiDependency.Mode.class, UiDependency.Mode.SHARED),
                row.optionalString("entry", null),
                row.optionalString("sourceHint", null),
                row.stringList("requires"),
                row.optionalBool("optional", false),
                row.optionalString("fallback", null),
                row.optionalString("license", null),
                row.unknown());
    }

    public static UiValue.Obj write(UiDependencies deps) {
        return new ObjWriter()
                .putList("dependencies", deps.entries(), DependencyCodec::writeRow)
                .putUnknown(deps.unknown())
                .build();
    }

    public static UiValue.Obj writeRow(UiDependency dep) {
        return new ObjWriter()
                .put("id", dep.id())
                .putEnum("kind", dep.kind())
                .put("version", dep.version())
                .put("sha256", dep.sha256())
                .putIfNot("size", dep.size(), 0)
                .putEnum("mode", dep.mode())
                .put("entry", dep.entry())
                .put("sourceHint", dep.sourceHint())
                .putStrings("requires", dep.requires())
                .putIfNot("optional", dep.optional(), false)
                .put("fallback", dep.fallback())
                .put("license", dep.license())
                .putUnknown(dep.unknown())
                .build();
    }
}
