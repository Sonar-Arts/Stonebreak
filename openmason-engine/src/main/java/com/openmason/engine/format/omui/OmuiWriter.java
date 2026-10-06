package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.io.AnimationCodec;
import com.openmason.engine.format.omui.io.StateMachineCodec;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.DependencyCodec;
import com.openmason.engine.format.omui.io.DocumentCodec;
import com.openmason.engine.format.omui.io.GraphCodec;
import com.openmason.engine.format.omui.io.ManifestCodec;
import com.openmason.engine.format.omui.io.StyleCodec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes {@code .omui} archives deterministically: canonical JSON for every owned entry,
 * verbatim bytes for assets, scripts, editor and unknown entries, fixed ZIP metadata. The
 * same {@link OmuiArchive} always yields the same bytes. Invalid documents are refused.
 */
public final class OmuiWriter {

    private OmuiWriter() {
    }

    /** Canonical entries (name → bytes), the form pack/unpack and digests work on. */
    public static Map<String, UiBytes> entries(OmuiArchive a) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        OmuiValidator.validate(a, d);
        d.throwIfErrors("Refusing to write an invalid OMUI document");

        Map<String, UiBytes> out = new LinkedHashMap<>();
        out.put(OmuiFormat.MANIFEST, json(ManifestCodec.write(a.manifest())));
        out.put(OmuiFormat.DOCUMENT, json(DocumentCodec.write(a.document())));
        if (!a.dependencies().entries().isEmpty() || !a.dependencies().unknown().isEmpty()) {
            out.put(OmuiFormat.DEPENDENCIES, json(DependencyCodec.write(a.dependencies())));
        }
        a.styles().forEach((id, s) -> out.put(OmuiFormat.styleEntry(id), json(StyleCodec.write(s))));
        a.graphs().forEach((id, g) -> out.put(OmuiFormat.graphEntry(id), json(GraphCodec.write(g))));
        a.animations().forEach((id, c) -> out.put(OmuiFormat.animationEntry(id), json(AnimationCodec.write(c))));
        a.stateMachines().forEach((id, m) -> out.put(OmuiFormat.stateMachineEntry(id),
                json(StateMachineCodec.write(m))));
        a.scripts().forEach((id, src) -> out.put(OmuiFormat.scriptEntry(id), UiBytes.utf8(src)));
        out.putAll(a.assets());
        out.putAll(a.editor());
        a.extraEntries().forEach(out::putIfAbsent);
        return out;
    }

    public static byte[] write(OmuiArchive a) throws UiFormatException {
        return ArchiveIO.write(entries(a));
    }

    /** Atomic save: the target is either the old file or the complete new one, never a mix. */
    public static void save(OmuiArchive a, Path target) throws IOException {
        AtomicFiles.write(target, write(a));
    }

    static UiBytes json(UiValue value) {
        return UiBytes.copyOf(CanonicalJson.write(value));
    }
}
