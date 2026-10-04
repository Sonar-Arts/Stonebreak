package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.omui.io.ManifestCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explicit schema upgrades. An upgrade is a chain of {@link UpgradeStage}s applied to raw
 * entries in memory; the result is decoded and validated like any current document before a
 * single byte is written. The original is never partially overwritten: in-place upgrades copy
 * the original to a {@code .v<from>.bak} sibling first and then replace atomically.
 */
public final class OmuiUpgrader {

    private static final List<UpgradeStage> STAGES = List.of(new DraftUpgradeStage());

    /**
     * @param archive  the upgraded, validated document
     * @param from     the version that was read
     * @param stages   applied stages as {@code "0.1->1.0"}, empty when already current
     */
    public record Upgrade(OmuiArchive archive, SchemaVersion from, List<String> stages,
                          List<UiDiagnostic> diagnostics) {
        public boolean upgraded() {
            return !stages.isEmpty();
        }
    }

    private OmuiUpgrader() {
    }

    /** Upgrade in memory. A current-version document passes through unchanged. */
    public static Upgrade upgrade(byte[] archive) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> entries = ArchiveIO.read(archive, ArchiveLimits.DEFAULT, d);
        d.throwIfErrors("Cannot open OMUI archive");
        return upgradeEntries(entries, d);
    }

    static Upgrade upgradeEntries(Map<String, byte[]> entries, UiDiagnostics d) throws UiFormatException {
        UiValue manifest = OmuiReader.json(entries, OmuiFormat.MANIFEST, true, ArchiveLimits.DEFAULT, d);
        d.throwIfErrors("Cannot read OMUI manifest");
        SchemaVersion from = ManifestCodec.peekVersion(manifest, OmuiFormat.FORMAT_ID, d);
        d.throwIfErrors("Cannot read OMUI manifest");

        List<String> applied = new ArrayList<>();
        SchemaVersion version = from;
        while (version.major() < OmuiFormat.SCHEMA_VERSION.major()) {
            UpgradeStage stage = stageFrom(version);
            if (stage == null) {
                d.error(Code.UNSUPPORTED_SCHEMA_VERSION, OmuiFormat.MANIFEST, "/schemaVersion",
                        "No upgrade path from schema " + version);
                d.throwIfErrors("Cannot upgrade OMUI document");
            }
            entries = stage.apply(entries, d);
            d.throwIfErrors("Upgrade " + stage.from() + "->" + stage.to() + " failed");
            applied.add(stage.from() + "->" + stage.to());
            version = stage.to();
        }
        OmuiReader.Result result = OmuiReader.fromEntries(entries, ArchiveLimits.DEFAULT);
        List<UiDiagnostic> all = new ArrayList<>(d.list());
        all.addAll(result.diagnostics());
        return new Upgrade(result.archive(), from, List.copyOf(applied), all);
    }

    private static UpgradeStage stageFrom(SchemaVersion version) {
        for (UpgradeStage s : STAGES) {
            if (s.from().equals(version)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Upgrade {@code source} into {@code target}. When they are the same file the original is
     * first copied to {@code <name>.v<from>.bak}; the replacement is atomic. Nothing is written
     * when the document is already current or the upgrade fails.
     */
    public static Upgrade upgradeFile(Path source, Path target) throws IOException {
        byte[] original = Files.readAllBytes(source);
        Upgrade up = upgrade(original);
        if (!up.upgraded()) {
            return up;
        }
        byte[] bytes = OmuiWriter.write(up.archive());
        if (Files.exists(target) && Files.isSameFile(source, target)) {
            // The backup holds exactly the bytes that were upgraded, and never replaces an older backup.
            AtomicFiles.write(freeBackupPath(source, up.from()), original);
        }
        AtomicFiles.write(target, bytes);
        return up;
    }

    private static Path freeBackupPath(Path source, SchemaVersion from) {
        String base = source.getFileName() + ".v" + from + ".bak";
        Path candidate = source.resolveSibling(base);
        for (int n = 1; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); n++) {
            candidate = source.resolveSibling(base + "." + n);
        }
        return candidate;
    }
}
