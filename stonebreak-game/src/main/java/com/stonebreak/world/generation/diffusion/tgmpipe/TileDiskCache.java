package com.stonebreak.world.generation.diffusion.tgmpipe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finished tiles on disk, in the TGMPipe service's file format ({@code terrain_slm/tgmpipe/tile_cache.py}, SCHEMA 2):
 * {@code <root>/<namespace>/s<seed>_x<tx>_z<tz>_l<lod>.tile} = {@code u32 rows, u32 cols}, 7 int16 planes
 * (little endian), then the surface block ({@code world/surface_channels.pack}: {@code u8 count}, per channel
 * {@code u8 name_len, name, f32 scale, f32 offset}, then the uint8 planes). One byte budget over the whole root; least
 * recently used files go first, emptied foreign namespaces are removed. Used by {@link NativeTerrainService} (the
 * Python service keeps its own instance over the same root: namespaces never collide).
 */
final class TileDiskCache {

    private static final Logger LOG = Logger.getLogger(TileDiskCache.class.getName());

    /** A cached tile: planes[p][row * cols + col], surface channels by wire name in file order. */
    record Entry(int rows, int cols, short[][] planes, Map<String, byte[]> surface) {
    }

    private final Path root;
    private final List<Path> dirs;
    private final long maxBytes;
    private final Map<Path, long[]> files = new HashMap<>();   // path -> {recency, size}
    private long tick;   // recency = ms * 1000 + a counter: strictly increasing within a run (ties broke LRU order)
    private long bytes;
    private boolean indexed;

    TileDiskCache(Path root, List<String> namespaces, long maxBytes) {
        this.root = root;
        this.dirs = new ArrayList<>();
        for (String ns : namespaces) {
            Path d = root.resolve(ns);
            try {
                Files.createDirectories(d);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create tile cache " + d, e);
            }
            dirs.add(d);
        }
        this.maxBytes = maxBytes;
    }

    private Path path(int world, long seed, int tileX, int tileZ, int lod) {
        return dirs.get(world).resolve("s" + seed + "_x" + tileX + "_z" + tileZ + "_l" + lod + ".tile");
    }

    Entry get(int world, long seed, int tileX, int tileZ, int lod, int planes) {
        Path p = path(world, seed, tileX, tileZ, lod);
        byte[] data;
        try {
            data = Files.readAllBytes(p);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "tile cache read failed: " + p, e);
            return null;
        }
        Entry entry = decode(data, planes);
        if (entry == null) {
            try {
                Files.deleteIfExists(p);   // torn or foreign file: regenerate rather than trust it
            } catch (IOException ignored) {
                // next put overwrites it
            }
            return null;
        }
        try {
            Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // LRU order only
        }
        synchronized (this) {
            long[] f = files.get(p);
            if (f != null) {
                f[0] = recency();
            }
        }
        return entry;
    }

    static Entry decode(byte[] data, int planeCount) {
        if (data.length < 9) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long rows = Integer.toUnsignedLong(b.getInt());
        long cols = Integer.toUnsignedLong(b.getInt());
        long cells = rows * cols;
        if (rows == 0 || cells > (1 << 24) || data.length < 8 + 2 * planeCount * cells + 1) {
            return null;
        }
        short[][] planes = new short[planeCount][(int) cells];
        var shorts = b.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        for (short[] plane : planes) {
            shorts.get(plane);
        }
        b.position(b.position() + (int) (2 * planeCount * cells));
        try {
            int count = Byte.toUnsignedInt(b.get());
            List<String> names = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                byte[] name = new byte[Byte.toUnsignedInt(b.get())];
                b.get(name);
                b.getFloat();
                b.getFloat();
                names.add(new String(name, StandardCharsets.US_ASCII));
            }
            if (b.remaining() != count * cells) {
                return null;
            }
            Map<String, byte[]> surface = new LinkedHashMap<>();
            for (String n : names) {
                byte[] plane = new byte[(int) cells];
                b.get(plane);
                surface.put(n, plane);
            }
            return new Entry((int) rows, (int) cols, planes, surface);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static byte[] encode(Entry e, Map<String, float[]> scaleOffset) {
        int cells = e.rows() * e.cols();
        int size = 8 + 2 * e.planes().length * cells + 1;
        for (String n : e.surface().keySet()) {
            size += 1 + n.length() + 8 + cells;
        }
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(e.rows()).putInt(e.cols());
        for (short[] plane : e.planes()) {
            for (short v : plane) {
                b.putShort(v);
            }
        }
        b.put((byte) e.surface().size());
        for (String n : e.surface().keySet()) {
            byte[] name = n.getBytes(StandardCharsets.US_ASCII);
            float[] so = scaleOffset.getOrDefault(n, new float[]{1.0f, 0.0f});
            b.put((byte) name.length).put(name).putFloat(so[0]).putFloat(so[1]);
        }
        for (byte[] plane : e.surface().values()) {
            b.put(plane);
        }
        return b.array();
    }

    void put(int world, long seed, int tileX, int tileZ, int lod, Entry e, Map<String, float[]> scaleOffset) {
        Path p = path(world, seed, tileX, tileZ, lod);
        byte[] payload = encode(e, scaleOffset);
        Path tmp = p.resolveSibling(p.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try {
            Files.write(tmp, payload);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "tile cache write failed: " + p, ex);
            return;
        }
        synchronized (this) {
            index();
            long[] old = files.put(p, new long[]{recency(), payload.length});
            bytes += payload.length - (old != null ? old[1] : 0);
            if (bytes > maxBytes) {
                evict();
            }
        }
    }

    private long recency() {
        tick = Math.max(tick + 1, System.currentTimeMillis() * 1000);
        return tick;
    }

    /** Size index over the whole root, built on the first write (startup stays cheap). */
    private void index() {
        if (indexed) {
            return;
        }
        indexed = true;
        try (DirectoryStream<Path> nss = Files.newDirectoryStream(root)) {
            for (Path ns : nss) {
                if (!Files.isDirectory(ns)) {
                    continue;
                }
                try (DirectoryStream<Path> tiles = Files.newDirectoryStream(ns, "*.tile")) {
                    for (Path t : tiles) {
                        try {
                            long size = Files.size(t);
                            files.put(t, new long[]{Files.getLastModifiedTime(t).toMillis() * 1000, size});
                            bytes += size;
                        } catch (IOException ignored) {
                            // raced a deletion
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "tile cache index failed: " + root, e);
        }
    }

    private void evict() {
        List<Map.Entry<Path, long[]>> all = new ArrayList<>(files.entrySet());
        all.sort((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]));
        for (Map.Entry<Path, long[]> f : all) {
            if (bytes <= maxBytes * 0.9) {
                break;
            }
            try {
                Files.deleteIfExists(f.getKey());
            } catch (IOException ignored) {
                // gone already
            }
            files.remove(f.getKey());
            bytes -= f.getValue()[1];
        }
        try (DirectoryStream<Path> nss = Files.newDirectoryStream(root)) {
            for (Path ns : nss) {
                if (Files.isDirectory(ns) && !dirs.contains(ns)) {
                    try (DirectoryStream<Path> any = Files.newDirectoryStream(ns)) {
                        if (!any.iterator().hasNext()) {
                            Files.deleteIfExists(ns);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    synchronized String statsJson() {
        return "{\"files\":" + files.size() + ",\"bytes\":" + bytes + ",\"max_bytes\":" + maxBytes + "}";
    }
}
