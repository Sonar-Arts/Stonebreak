package com.stonebreak.openmason.fx;

import com.openmason.api.files.FileType;
import com.openmason.api.fx.FxExportInput;
import com.openmason.api.fx.FxExportTarget;
import com.openmason.api.fx.FxExporter;
import com.stonebreak.fx.sbfx.SbfxCodec;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.openmason.StonebreakGameTarget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stonebreak's FX container in Open Mason's Export FX window and {@code fx_export} tool (Stonebreak#435, through the
 * FX export plugin point of OM#102): an {@code .omfx} v2 system becomes a self-contained {@code .sbfx} and, while the
 * Stonebreak target is active, deploys straight into the checkout's {@code sbfx/} folder, where the game loads every
 * file at start-up ({@code ShippedFx}).
 */
public final class SbfxExport {

    /** Export target id. */
    public static final String ID = "stonebreak.sbfx";
    /** File type id, also the game target's asset-folder key for {@code sbfx/}. */
    public static final String FILE_TYPE_ID = "sbfx";
    /** The checkout folder SBFX files deploy into (relative to the resource root). */
    public static final String FOLDER = "sbfx";

    public static final FileType FILE_TYPE = FileType.of(FILE_TYPE_ID, "Stonebreak FX", SbfxCodec.FILE_EXTENSION)
            .withSave("target", FILE_TYPE_ID, "fx");

    private SbfxExport() {
    }

    public static FxExportTarget target() {
        return new FxExportTarget(ID, "Stonebreak FX (.sbfx)", FILE_TYPE, StonebreakGameTarget.ID, new Exporter());
    }

    /** The container writer: checks the system can ship self-contained, then writes the SBFX bytes. */
    static final class Exporter implements FxExporter {

        @Override
        public byte[] export(FxExportInput input) throws IOException {
            List<String> problems = problems(input);
            if (!problems.isEmpty()) {
                throw new IOException(String.join("; ", problems));
            }
            return SbfxCodec.write(document(input));
        }

        @Override
        public List<String> problems(FxExportInput input) {
            List<String> problems = new ArrayList<>();
            for (String name : input.externalTextures()) {
                problems.add("sprite '" + name + "' is not embedded: an .sbfx is self-contained, embed the image "
                        + "(FX Sprites panel or fx_sprite) before exporting");
            }
            try {
                document(input);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
            return problems;
        }

        private static SbfxDocument document(FxExportInput input) {
            return SbfxDocument.of(input.document());
        }
    }
}
