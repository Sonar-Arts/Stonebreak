package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TGMPipeException;

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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.logging.Logger;

/**
 * The CUDA runtime compiler (NVRTC) the native terrain engine compiles its kernels with. One pinned release for every
 * machine, NVIDIA's redistributable {@code nvidia-cuda-nvrtc-cu12} {@value #VERSION} (the one the model's PyTorch
 * environment carried, which the native pipeline's parity runs used): the same compiler gives the same cubins, so tiles
 * are identical across machines and the content-addressed kernel cache never mixes compiler outputs.
 *
 * <p><b>Pinned, not preferred.</b> The game never uses a system CUDA toolkit's NVRTC or {@code CENDA_NVRTC}: only
 * the copy in {@code Models/tools/nvrtc/<version>/}, whose every file must match its pinned SHA-256 ({@link Wheel#files};
 * a file whose size and mtime match the {@value #VERIFIED} record is trusted without rehashing). A missing, partial or
 * altered copy is downloaded again: the wheel (a zip; no Python involved) from PyPI, checked against its pinned
 * SHA-256, of which only the pinned libraries are kept. The model's training environment pins the same release
 * ({@code uv.lock}). The one escape hatch, {@code -Dstonebreak.terrain.nvrtc=<library>}, is for engine development
 * only and logs a warning: another NVRTC may build different kernels, so its tiles may not match other machines.
 */
final class NvrtcTool {

    private static final Logger LOG = Logger.getLogger(NvrtcTool.class.getName());

    static final String VERSION = "12.9.86";

    /** Record of the verified files, next to them: {@code name|size|mtimeMillis|sha256} per line. */
    static final String VERIFIED = ".verified";

    /**
     * One downloadable build: the wheel, its digest and size, the library the engine opens and every file kept from
     * it (name -> SHA-256; the library and the builtins it loads from its own folder).
     */
    record Wheel(String url, String sha256, long size, String library, Map<String, String> files) {
    }

    private static final Wheel LINUX_X64 = new Wheel(
            "https://files.pythonhosted.org/packages/b8/85/e4af82cc9202023862090bfca4ea827d533329e925c758f0cde964cb54b7/"
                    + "nvidia_cuda_nvrtc_cu12-12.9.86-py3-none-manylinux2010_x86_64.manylinux_2_12_x86_64.whl",
            "210cf05005a447e29214e9ce50851e83fc5f4358df8b453155d5e1918094dcb4", 89_568_129L, "libnvrtc.so.12",
            Map.of("libnvrtc.so.12", "7c67c6b51ea0e0279634cebd676ff7efda1674806444520c84430ad5c35fe625",
                    "libnvrtc-builtins.so.12.9", "6507cf33450cfb22970c2c1e7aa8e7c0a33e39d864720277b05f61a92791e2e8"));
    private static final Wheel WINDOWS_X64 = new Wheel(
            "https://files.pythonhosted.org/packages/52/de/823919be3b9d0ccbf1f784035423c5f18f4267fb0123558d58b813c6ec86/"
                    + "nvidia_cuda_nvrtc_cu12-12.9.86-py3-none-win_amd64.whl",
            "72972ebdcf504d69462d3bcd67e7b81edd25d0fb85a2c46d3ea3517666636349", 76_408_187L, "nvrtc64_120_0.dll",
            Map.of("nvrtc64_120_0.dll", "0cc63349fedffeec1660b30955e8028b6e4e4c26cf1e41c56dc4569fd5eca82c",
                    "nvrtc-builtins64_129.dll", "c405226b0b394ed8bdc593bb1419b94a989f93248ed2873e8f2e0e27c1688ca1"));

    private NvrtcTool() {
    }

    /** The build for this OS / CPU, or null where NVIDIA ships none (macOS, ARM Windows). */
    static Wheel wheel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64) {
            return null;
        }
        if (os.startsWith("windows")) {
            return WINDOWS_X64;
        }
        return os.contains("linux") ? LINUX_X64 : null;
    }

    private static final AtomicBoolean OVERRIDE_WARNED = new AtomicBoolean();

    /** The engine-development override ({@code -Dstonebreak.terrain.nvrtc}), or null. Environment variables never count. */
    static Path override() {
        String set = System.getProperty("stonebreak.terrain.nvrtc");
        if (set == null || set.isBlank()) {
            return null;
        }
        if (OVERRIDE_WARNED.compareAndSet(false, true)) {
            LOG.warning(() -> "NVRTC override -Dstonebreak.terrain.nvrtc=" + set.trim() + " replaces the pinned NVRTC "
                    + VERSION + ": kernels (and so tiles) may differ from other machines");
        }
        return Path.of(set.trim());
    }

    /** Where the downloaded copy lives. */
    static Path installDir(Path toolsDir) {
        return toolsDir.resolve("nvrtc").resolve(VERSION);
    }

    /** The NVRTC that is already here (override, or the downloaded copy if every pinned file verifies), or null. */
    static Path installed(Path toolsDir) {
        Path o = override();
        if (o != null) {
            return o;
        }
        Wheel w = wheel();
        if (w == null) {
            return null;
        }
        Path dir = installDir(toolsDir);
        return verify(dir, w.files()) ? dir.resolve(w.library()) : null;
    }

    /**
     * Whether every file of {@code pinned} is in {@code dir} with its pinned SHA-256. Files whose size and mtime match
     * the {@value #VERIFIED} record are trusted as recorded; the others are hashed (and the record is rewritten).
     */
    static synchronized boolean verify(Path dir, Map<String, String> pinned) {
        Map<String, String> recorded = new HashMap<>();
        Path record = dir.resolve(VERIFIED);
        try {
            if (Files.isRegularFile(record)) {
                for (String line : Files.readAllLines(record)) {
                    int bar = line.indexOf('|');
                    if (bar > 0) recorded.put(line.substring(0, bar), line.substring(bar + 1));
                }
            }
            List<String> lines = new ArrayList<>();
            boolean rehashed = false;
            for (var e : new TreeMap<>(pinned).entrySet()) {
                Path f = dir.resolve(e.getKey());
                if (!Files.isRegularFile(f)) {
                    return false;
                }
                String id = Files.size(f) + "|" + Files.getLastModifiedTime(f).toMillis() + "|";
                String was = recorded.get(e.getKey());
                String sha;
                if (was != null && was.startsWith(id)) {
                    sha = was.substring(id.length());
                } else {
                    sha = sha256(f);
                    rehashed = true;
                }
                if (!sha.equals(e.getValue())) {
                    LOG.warning(() -> "NVRTC file " + f + " does not match the pinned " + VERSION + " build; it will be"
                            + " downloaded again");
                    return false;
                }
                lines.add(e.getKey() + "|" + id + sha);
            }
            if (rehashed) {
                Files.write(record, lines);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The NVRTC library to hand the engine, downloading the pinned one when it is not here yet.
     *
     * @param log      setup log, or null
     * @param progress hears (text, fraction 0..1 or negative) while downloading
     * @throws TGMPipeException when there is none and none can be had
     */
    static Path locate(Path toolsDir, PrintWriter log, BiConsumer<String, Double> progress) {
        Path have = installed(toolsDir);
        if (have != null) {
            return have;
        }
        Wheel w = wheel();
        if (w == null) {
            throw new TGMPipeException("no NVRTC build for " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch") + "; name one with -Dstonebreak.terrain.nvrtc=<library>");
        }
        try {
            download(w, installDir(toolsDir), log, progress);
        } catch (IOException e) {
            throw new TGMPipeException("could not download the CUDA compiler (NVRTC " + VERSION + "): " + e.getMessage(), e);
        }
        return installDir(toolsDir).resolve(w.library());
    }

    private static void download(Wheel w, Path dir, PrintWriter log, BiConsumer<String, Double> progress)
            throws IOException {
        if (log != null) {
            log.println("downloading " + w.url());
        }
        Files.createDirectories(dir.getParent());
        Path part = dir.resolveSibling(dir.getFileName() + ".whl.part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(w.url())).timeout(Duration.ofMinutes(10)).GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + w.url());
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(resp.body(), sha); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                long got = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    got += n;
                    progress.accept(String.format("Downloading the CUDA compiler: %.0f / %.0f MB", got / 1048576.0,
                            w.size() / 1048576.0), Math.min(1.0, (double) got / w.size()));
                }
            }
            String digest = HexFormat.of().formatHex(sha.digest());
            if (!digest.equals(w.sha256())) {
                throw new IOException("checksum mismatch for " + w.url() + " (got " + digest + ")");
            }
            progress.accept("Unpacking the CUDA compiler", -1.0);
            extract(part, dir, w.files());
            if (log != null) {
                log.println("NVRTC " + VERSION + " installed in " + dir);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * Copies the pinned files of the wheel (found anywhere under {@code nvidia/cuda_nvrtc/}: the compiler and the
     * builtins it loads from its own folder) flat into {@code dir}, checks each against its pinned SHA-256 and only then
     * moves the folder into place, so a failed or altered unpack never leaves an installed copy.
     */
    static void extract(Path wheel, Path dir, Map<String, String> pinned) throws IOException {
        Path tmp = dir.resolveSibling(dir.getFileName() + ".tmp").toAbsolutePath().normalize();
        deleteTree(tmp);
        Files.createDirectories(tmp);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(wheel)))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (e.isDirectory() || !name.startsWith("nvidia/cuda_nvrtc/") || !pinned.containsKey(base)) {
                    continue;
                }
                Path target = tmp.resolve(base).normalize();
                if (!target.startsWith(tmp) || target.equals(tmp)) {   // never write outside the unpack folder
                    throw new IOException("unsafe entry " + name + " in " + wheel.getFileName());
                }
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().setExecutable(true);
            }
        }
        if (!verify(tmp, pinned)) {
            deleteTree(tmp);
            throw new IOException("the NVRTC libraries in " + wheel.getFileName() + " are missing or do not match the"
                    + " pinned " + VERSION + " build");
        }
        deleteTree(dir);
        Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            for (Path q : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(q);
            }
        }
    }
}
