package com.stonebreak.world.generation.diffusion.tgmpipe;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds {@code uv} (Astral's Python package manager, one static binary) for {@link ModelSetup}, or
 * downloads a pinned release into {@code Models/tools/uv/}. Order: {@code -Dstonebreak.modelSetup.uv},
 * the copy this game downloaded before, {@code uv} on the PATH (and its usual install folders), then
 * the download. Extraction is plain Java (tar.gz on Linux/macOS, zip on Windows).
 */
final class UvTool {

    /** The uv release the model's uv.lock was made with. */
    static final String VERSION = "0.12.20";

    private UvTool() { }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String exe() {
        return windows() ? "uv.exe" : "uv";
    }

    /**
     * @param progress hears (text, fraction 0..1 or negative) while downloading
     * @return a runnable uv binary
     */
    static Path locate(Path toolsDir, PrintWriter log, BiConsumer<String, Double> progress) throws IOException {
        String override = System.getProperty("stonebreak.modelSetup.uv");
        if (override != null && works(Path.of(override), log)) return Path.of(override);
        Path ours = toolsDir.resolve("uv").resolve(exe());
        if (works(ours, log)) return ours;
        for (Path candidate : pathCandidates()) {
            if (works(candidate, log)) return candidate;
        }
        progress.accept("Downloading uv " + VERSION, -1.0);
        download(ours, log, progress);
        if (!works(ours, log)) throw new IOException("downloaded uv does not run: " + ours);
        return ours;
    }

    static String describe(Path uv, Path toolsDir) {
        return (uv.startsWith(toolsDir) ? "uv " + VERSION + " (downloaded)" : "uv (" + uv + ")");
    }

    private static List<Path> pathCandidates() {
        List<Path> out = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) out.add(Path.of(dir).resolve(exe()));
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            out.add(Path.of(home, ".local", "bin", exe()));
            out.add(Path.of(home, ".cargo", "bin", exe()));
        }
        return out;
    }

    private static boolean works(Path uv, PrintWriter log) {
        if (!Files.isRegularFile(uv) || !Files.isExecutable(uv)) return false;
        try {
            Process p = new ProcessBuilder(uv.toString(), "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).strip();
            boolean ok = p.waitFor() == 0 && out.startsWith("uv ");
            log.println("uv candidate " + uv + ": " + (ok ? out : "does not run"));
            return ok;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The release asset for this OS / CPU, e.g. {@code uv-x86_64-unknown-linux-gnu.tar.gz}. */
    static String asset() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x86_64";
        if (os.startsWith("windows")) return "uv-" + cpu + "-pc-windows-msvc.zip";
        if (os.contains("mac") || os.contains("darwin")) return "uv-" + cpu + "-apple-darwin.tar.gz";
        return "uv-" + cpu + "-unknown-linux-gnu.tar.gz";
    }

    private static void download(Path target, PrintWriter log, BiConsumer<String, Double> progress) throws IOException {
        String url = "https://github.com/astral-sh/uv/releases/download/" + VERSION + "/" + asset();
        log.println("downloading " + url);
        Files.createDirectories(target.getParent());
        Path archive = target.resolveSibling(asset() + ".part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(archive)) {
                byte[] buf = new byte[1 << 16];
                long got = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    got += n;
                    progress.accept(String.format("Downloading uv %s: %.1f / %s MB", VERSION, got / 1048576.0,
                            total > 0 ? String.format("%.1f", total / 1048576.0) : "?"), total > 0 ? (double) got / total : -1.0);
                }
            }
            extract(archive, target);
            target.toFile().setExecutable(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /** Pulls the {@code uv} binary (whatever folder it sits in) out of the release archive. */
    static void extract(Path archive, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        boolean found = false;
        if (archive.toString().endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    if (!e.isDirectory() && baseName(e.getName()).equals(target.getFileName().toString())) {
                        Files.copy(zip, tmp, StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
            }
        } else {
            try (InputStream tar = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
                found = untarOne(tar, target.getFileName().toString(), tmp);
            }
        }
        if (!found) throw new IOException("no " + target.getFileName() + " in " + archive.getFileName());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Minimal ustar reader: copies the first regular file named {@code name} to {@code out}. */
    private static boolean untarOne(InputStream tar, String name, Path out) throws IOException {
        byte[] header = new byte[512];
        while (tar.readNBytes(header, 0, 512) == 512) {
            if (header[0] == 0) return false;                        // end-of-archive block
            String entry = cString(header, 0, 100);
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) entry = prefix + "/" + entry;
            String octal = cString(header, 124, 12).trim();
            long size = octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
            char type = (char) header[156];
            if ((type == '0' || type == 0) && baseName(entry).equals(name)) {
                try (OutputStream o = Files.newOutputStream(out)) {
                    copyN(tar, o, size);
                }
                return true;
            }
            tar.skipNBytes((size + 511) / 512 * 512);             // entry data, padded to whole blocks
        }
        return false;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[1 << 16];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("truncated archive");
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String baseName(String entry) {
        int slash = entry.lastIndexOf('/');
        return slash < 0 ? entry : entry.substring(slash + 1);
    }
}
