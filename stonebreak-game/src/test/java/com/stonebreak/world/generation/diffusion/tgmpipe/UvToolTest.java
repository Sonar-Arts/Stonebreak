package com.stonebreak.world.generation.diffusion.tgmpipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link UvTool}'s archive handling on synthetic release archives (no network). */
class UvToolTest {

    @TempDir
    Path dir;

    @Test
    void extractsTheBinaryFromATarGzWhateverFolderItIsIn() throws IOException {
        byte[] uv = "#!/bin/sh\necho uv 0.0.0\n".getBytes(StandardCharsets.UTF_8);
        Path archive = dir.resolve("uv-x86_64-unknown-linux-gnu.tar.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            tarEntry(out, "uv-x86_64-unknown-linux-gnu/", new byte[0], '5');
            tarEntry(out, "uv-x86_64-unknown-linux-gnu/uvx", "not me".getBytes(StandardCharsets.UTF_8), '0');
            tarEntry(out, "uv-x86_64-unknown-linux-gnu/uv", uv, '0');
            out.write(new byte[1024]);   // end-of-archive
        }
        Path target = dir.resolve("uv");
        UvTool.extract(archive, target);
        assertArrayEquals(uv, Files.readAllBytes(target));
    }

    @Test
    void extractsTheExeFromAZip() throws IOException {
        byte[] exe = new byte[70_000];
        for (int i = 0; i < exe.length; i++) exe[i] = (byte) (i * 31);
        Path archive = dir.resolve("uv-x86_64-pc-windows-msvc.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("uvx.exe"));
            zip.write(new byte[]{1, 2, 3});
            zip.putNextEntry(new ZipEntry("uv.exe"));
            zip.write(exe);
        }
        Path target = dir.resolve("uv.exe");
        UvTool.extract(archive, target);
        assertArrayEquals(exe, Files.readAllBytes(target));
    }

    @Test
    void anArchiveWithoutUvFailsLoudly() throws IOException {
        Path archive = dir.resolve("empty.tar.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            tarEntry(out, "readme.txt", "hi".getBytes(StandardCharsets.UTF_8), '0');
            out.write(new byte[1024]);
        }
        assertThrows(IOException.class, () -> UvTool.extract(archive, dir.resolve("uv")));
    }

    @Test
    void assetMatchesThisPlatform() {
        String asset = UvTool.asset();
        assertTrue(asset.startsWith("uv-") && (asset.endsWith(".tar.gz") || asset.endsWith(".zip")), asset);
    }

    /** One ustar entry: 512-byte header (name, octal size, checksum, type) + data padded to 512. */
    private static void tarEntry(OutputStream out, String name, byte[] data, char type) throws IOException {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, h, 0, n.length);
        put(h, 100, "0000755");
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", data.length));
        put(h, 136, "00000000000");
        h[156] = (byte) type;
        put(h, 257, "ustar");
        for (int i = 148; i < 156; i++) h[i] = ' ';
        int sum = 0;
        for (byte b : h) sum += b & 0xFF;
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        out.write(h);
        out.write(data);
        out.write(new byte[(512 - data.length % 512) % 512]);
    }

    private static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
    }
}
