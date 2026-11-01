package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.SchemaVersion;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.ManifestCodec;
import com.openmason.engine.format.omui.io.ObjReader;
import com.openmason.engine.format.omui.io.ObjWriter;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedEntry;
import com.openmason.engine.format.sbui.SbuiManifest.DerivedKind;
import com.openmason.engine.format.sbui.SbuiManifest.Location;
import com.openmason.engine.format.sbui.SbuiManifest.SbuiDependency;
import com.openmason.engine.format.sbui.SbuiManifest.SourceRef;

import java.util.ArrayList;
import java.util.List;

/** SBUI {@code manifest.json} ↔ {@link SbuiManifest}. */
final class SbuiManifestCodec {

    private static final String E = SbuiFormat.MANIFEST;

    private SbuiManifestCodec() {
    }

    static SbuiManifest read(UiValue root, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, E, "", d);
        r.raw("format");
        SchemaVersion version = SchemaVersion.parse(r.requiredString("schemaVersion"));
        String assetId = r.requiredString("assetId");
        String entry = r.requiredString("entry");
        SourceRef source = null;
        ObjReader s = r.optionalObject("source");
        if (s == null) {
            r.error(Code.MISSING_FIELD, "source", "Required field 'source' is missing");
        } else {
            SchemaVersion sv = SchemaVersion.parse(s.requiredString("schemaVersion"));
            if (sv == null) {
                s.error(Code.INVALID_VALUE, "schemaVersion", "Expected \"MAJOR.MINOR\"");
            }
            source = new SourceRef(s.requiredString("entry"), s.requiredString("digest"),
                    sv == null ? OmuiFormat.SCHEMA_VERSION : sv, s.unknown());
        }
        int uiApi = r.requiredInt("uiApi", 0, OmuiFormat.MAX_VERSION);
        String layout = r.requiredString("layoutSemantics");
        List<String> requires = r.stringList("requires");
        var apis = ManifestCodec.readHost(r, "hostApis");
        var providers = ManifestCodec.readHost(r, "providers");
        List<SbuiDependency> deps = new ArrayList<>();
        List<ObjReader> rows = r.objects("dependencies");
        List<ObjReader> derivedRows = r.objects("derived");
        if (rows.size() > OmuiFormat.MAX_DEPENDENCIES || derivedRows.size() > OmuiFormat.MAX_DEPENDENCIES) {
            r.error(Code.LIMIT_EXCEEDED, null, "More than " + OmuiFormat.MAX_DEPENDENCIES + " rows");
            rows = List.of();
            derivedRows = List.of();
        }
        for (ObjReader row : rows) {
            deps.add(new SbuiDependency(
                    row.requiredString("id"),
                    row.requiredEnum("kind", UiDependency.Kind.class, UiDependency.Kind.IMAGE),
                    row.optionalString("version", null),
                    row.requiredString("sha256"),
                    row.optionalLong("size", 0, 0),
                    row.requiredEnum("mode", UiDependency.Mode.class, UiDependency.Mode.SHARED),
                    row.optionalEnum("location", Location.class, null),
                    row.optionalString("entry", null),
                    row.optionalString("pack", null),
                    row.stringList("requires"),
                    row.optionalBool("optional", false),
                    row.optionalString("fallback", null),
                    row.optionalString("license", null),
                    row.unknown()));
        }
        List<DerivedEntry> derived = new ArrayList<>();
        for (ObjReader x : derivedRows) {
            derived.add(new DerivedEntry(x.requiredString("entry"),
                    x.requiredEnum("kind", DerivedKind.class, DerivedKind.GRAPH_LUA), x.requiredString("source"),
                    x.requiredString("sourceSha256"), x.requiredString("sha256"), x.requiredString("compiler"),
                    x.requiredString("compilerVersion"), x.unknown()));
        }
        if (source == null) {
            source = new SourceRef("", "", OmuiFormat.SCHEMA_VERSION, null);
        }
        return new SbuiManifest(version == null ? SbuiFormat.SCHEMA_VERSION : version, assetId, entry, source, uiApi,
                layout, requires, apis, providers, deps, derived, r.unknown());
    }

    static UiValue.Obj write(SbuiManifest m) {
        return new ObjWriter()
                .put("format", SbuiFormat.FORMAT_ID)
                .put("schemaVersion", m.schemaVersion().toString())
                .put("assetId", m.assetId())
                .put("entry", m.entry())
                .put("source", new ObjWriter()
                        .put("entry", m.source().entry())
                        .put("digest", m.source().digest())
                        .put("schemaVersion", m.source().schemaVersion().toString())
                        .putUnknown(m.source().unknown())
                        .build())
                .put("uiApi", m.uiApi())
                .put("layoutSemantics", m.layoutSemantics())
                .putStrings("requires", m.requires())
                .putList("hostApis", m.hostApis(), ManifestCodec::writeHost)
                .putList("providers", m.providers(), ManifestCodec::writeHost)
                .putList("dependencies", m.dependencies(), dep -> new ObjWriter()
                        .put("id", dep.id())
                        .putEnum("kind", dep.kind())
                        .put("version", dep.version())
                        .put("sha256", dep.sha256())
                        .putIfNot("size", dep.size(), 0)
                        .putEnum("mode", dep.mode())
                        .putEnum("location", dep.location())
                        .put("entry", dep.entry())
                        .put("pack", dep.pack())
                        .putStrings("requires", dep.requires())
                        .putIfNot("optional", dep.optional(), false)
                        .put("fallback", dep.fallback())
                        .put("license", dep.license())
                        .putUnknown(dep.unknown())
                        .build())
                .putList("derived", m.derived(), x -> new ObjWriter()
                        .put("entry", x.entry())
                        .putEnum("kind", x.kind())
                        .put("source", x.source())
                        .put("sourceSha256", x.sourceSha256())
                        .put("sha256", x.sha256())
                        .put("compiler", x.compiler())
                        .put("compilerVersion", x.compilerVersion())
                        .putUnknown(x.unknown())
                        .build())
                .putUnknown(m.unknown())
                .build();
    }
}
