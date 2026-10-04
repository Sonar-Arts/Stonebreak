package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.omui.io.CanonicalJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, validated, atomic {@code .sbui} writing. */
public final class SbuiWriter {

    private SbuiWriter() {
    }

    public static Map<String, UiBytes> entries(SbuiArchive a) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        SbuiValidator.validate(a, SbuiValidator.entries(a.sourceBytes(), d), SbuiReader.Options.RUNTIME, d);
        d.throwIfErrors("Refusing to write an invalid SBUI export");
        Map<String, UiBytes> out = new LinkedHashMap<>();
        out.put(SbuiFormat.MANIFEST, UiBytes.copyOf(CanonicalJson.write(SbuiManifestCodec.write(a.manifest()))));
        out.put(a.manifest().source().entry(), a.sourceBytes());
        out.putAll(a.assets());
        out.putAll(a.derived());
        a.extraEntries().forEach(out::putIfAbsent);
        return out;
    }

    public static byte[] write(SbuiArchive a) throws UiFormatException {
        return ArchiveIO.write(entries(a));
    }

    public static void save(SbuiArchive a, Path target) throws IOException {
        AtomicFiles.write(target, write(a));
    }
}
