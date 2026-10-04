package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.SchemaVersion;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiManifest.DocumentKind;
import com.openmason.engine.format.omui.UiManifest.HostRequirement;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/** {@code manifest.json} ↔ {@link UiManifest}. */
public final class ManifestCodec {

    private ManifestCodec() {
    }

    /** Reads only the version, before anything else is decoded; {@code null} after a diagnostic. */
    public static SchemaVersion peekVersion(UiValue root, String formatId, UiDiagnostics d) {
        if (!(root instanceof UiValue.Obj obj)) {
            d.error(Code.WRONG_TYPE, OmuiFormat.MANIFEST, "", "Manifest must be an object");
            return null;
        }
        if (!(obj.get("format") instanceof UiValue.Str f) || !f.value().equals(formatId)) {
            d.error(Code.NOT_AN_ARCHIVE, OmuiFormat.MANIFEST, "/format", "Not an ." + formatId + " manifest");
            return null;
        }
        UiValue v = obj.get("schemaVersion");
        SchemaVersion version = v instanceof UiValue.Str s ? SchemaVersion.parse(s.value()) : null;
        if (version == null) {
            d.error(Code.INVALID_VALUE, OmuiFormat.MANIFEST, "/schemaVersion", "Expected \"MAJOR.MINOR\"");
        }
        return version;
    }

    public static UiManifest read(UiValue root, UiDiagnostics d) {
        ObjReader r = ObjReader.of(root, OmuiFormat.MANIFEST, "", d);
        r.raw("format");
        SchemaVersion version = SchemaVersion.parse(r.requiredString("schemaVersion"));
        String documentId = r.requiredString("documentId");
        DocumentKind kind = r.requiredEnum("kind", DocumentKind.class, DocumentKind.SCREEN);
        String displayName = r.optionalString("displayName", "");
        int uiApi = r.requiredInt("uiApi", 0, OmuiFormat.MAX_VERSION);
        String layout = r.requiredString("layoutSemantics");
        List<String> requires = r.stringList("requires");
        List<HostRequirement> apis = readHost(r, "hostApis");
        List<HostRequirement> providers = readHost(r, "providers");
        return new UiManifest(version == null ? OmuiFormat.SCHEMA_VERSION : version, documentId, kind, displayName,
                uiApi, layout, requires, apis, providers, r.unknown());
    }

    public static List<HostRequirement> readHost(ObjReader r, String key) {
        List<HostRequirement> out = new ArrayList<>();
        for (ObjReader h : r.objects(key)) {
            out.add(new HostRequirement(h.requiredString("id"), h.requiredInt("version", 1, OmuiFormat.MAX_VERSION),
                    h.optionalBool("optional", false), h.unknown()));
        }
        return out;
    }

    public static UiValue.Obj write(UiManifest m) {
        return new ObjWriter()
                .put("format", OmuiFormat.FORMAT_ID)
                .put("schemaVersion", m.schemaVersion().toString())
                .put("documentId", m.documentId())
                .putEnum("kind", m.kind())
                .putIfNot("displayName", m.displayName(), "")
                .put("uiApi", m.uiApi())
                .put("layoutSemantics", m.layoutSemantics())
                .putStrings("requires", m.requires())
                .putList("hostApis", m.hostApis(), ManifestCodec::writeHost)
                .putList("providers", m.providers(), ManifestCodec::writeHost)
                .putUnknown(m.unknown())
                .build();
    }

    public static UiValue writeHost(HostRequirement h) {
        return new ObjWriter()
                .put("id", h.id())
                .put("version", h.version())
                .putIfNot("optional", h.optional(), false)
                .putUnknown(h.unknown())
                .build();
    }
}
