package com.openmason.engine.format.uiarchive;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiUpgrader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Headless command line for UI archives (no GL, no tool):
 *
 * <pre>
 * validate &lt;file.omui|file.sbui&gt;
 * pack     &lt;dir&gt; &lt;out-file&gt;
 * unpack   &lt;file&gt; &lt;dir&gt; [--replace]
 * upgrade  &lt;in.omui&gt; [&lt;out.omui&gt;]          (in place keeps &lt;in&gt;.v&lt;from&gt;.bak)
 * export   &lt;in.omui&gt; &lt;out.sbui&gt; [--asset-id &lt;id&gt;]   (shared export)
 * import   &lt;in.sbui&gt; &lt;out.omui&gt; [--portable]
 * </pre>
 *
 * Exit status: 0 success, 1 invalid input (diagnostics on stderr), 2 usage error.
 */
public final class UiArchiveTool {

    private UiArchiveTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err);
        }
        List<String> a = Arrays.asList(args);
        try {
            switch (a.get(0)) {
                case "validate" -> {
                    if (a.size() != 2) {
                        return usage(err);
                    }
                    validate(Path.of(a.get(1)), out);
                }
                case "pack" -> {
                    if (a.size() != 3) {
                        return usage(err);
                    }
                    AtomicFiles.write(Path.of(a.get(2)), UiPacker.pack(Path.of(a.get(1))));
                    out.println("packed " + a.get(2));
                }
                case "unpack" -> {
                    if (a.size() < 3 || a.size() > 4 || (a.size() == 4 && !a.get(3).equals("--replace"))) {
                        return usage(err);
                    }
                    UiPacker.unpack(Path.of(a.get(1)), Path.of(a.get(2)), a.size() == 4);
                    out.println("unpacked into " + a.get(2));
                }
                case "upgrade" -> {
                    if (a.size() < 2 || a.size() > 3) {
                        return usage(err);
                    }
                    Path in = Path.of(a.get(1));
                    var up = OmuiUpgrader.upgradeFile(in, a.size() == 3 ? Path.of(a.get(2)) : in);
                    out.println(up.upgraded() ? "upgraded " + up.from() + " via " + up.stages() : "already current");
                    print(up.diagnostics(), out);
                }
                case "export" -> {
                    if (a.size() != 3 && !(a.size() == 5 && a.get(3).equals("--asset-id"))) {
                        return usage(err);
                    }
                    var source = OmuiReader.read(Path.of(a.get(1))).archive();
                    var options = new SbuiExporter.Options(a.size() == 5 ? a.get(4) : null, null, false, null, null);
                    var export = SbuiExporter.export(source, options);
                    SbuiWriter.save(export.archive(), Path.of(a.get(2)));
                    out.println("exported " + a.get(2));
                    print(export.diagnostics(), out);
                }
                case "import" -> {
                    if (a.size() != 3 && !(a.size() == 4 && a.get(3).equals("--portable"))) {
                        return usage(err);
                    }
                    var sbui = SbuiReader.read(Path.of(a.get(1)), SbuiReader.Options.EDITOR).archive();
                    var imported = a.size() == 4 ? SbuiImporter.importPortable(sbui) : SbuiImporter.importEditable(sbui);
                    OmuiWriter.save(imported.document(), Path.of(OmuiFormat.ensureExtension(a.get(2))));
                    out.println("imported " + a.get(2));
                }
                default -> {
                    return usage(err);
                }
            }
            return 0;
        } catch (UiFormatException e) {
            err.println(e.getMessage().lines().findFirst().orElse("invalid"));
            print(e.diagnostics(), err);
            return 1;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return 1;
        }
    }

    private static void validate(Path file, PrintStream out) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<UiDiagnostic> diagnostics = switch (UiPacker.detect(bytes)) {
            case OMUI -> OmuiReader.read(bytes).diagnostics();
            case SBUI -> SbuiReader.read(bytes, SbuiReader.Options.RUNTIME).diagnostics();
        };
        out.println("valid " + file);
        print(diagnostics, out);
    }

    private static void print(List<UiDiagnostic> diagnostics, PrintStream out) {
        for (UiDiagnostic d : diagnostics) {
            if (d.severity() != UiDiagnostic.Severity.INFO) {
                out.println("  " + d);
            }
        }
    }

    private static int usage(PrintStream err) {
        err.println("""
                usage: UiArchiveTool <command>
                  validate <file.omui|file.sbui>
                  pack     <dir> <out-file>
                  unpack   <file> <dir> [--replace]
                  upgrade  <in.omui> [<out.omui>]
                  export   <in.omui> <out.sbui> [--asset-id <id>]
                  import   <in.sbui> <out.omui> [--portable]""");
        return 2;
    }
}
