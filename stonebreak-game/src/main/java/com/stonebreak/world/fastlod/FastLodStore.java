package com.stonebreak.world.fastlod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent SQLite-backed cache for {@link FastLodChunkData}. Reads are
 * synchronous (the worker thread that's about to generate a node blocks on
 * the lookup), writes are asynchronous on a single I/O thread so the worker
 * can return as soon as the blob is handed off.
 *
 * <p>The schema is intentionally tiny — one table keyed by {@code (level, cx, cz)}
 * — so recovery and manual inspection stay trivial. A one-row {@code fastlod_meta}
 * table records which terrain the nodes were sampled from
 * ({@link com.stonebreak.world.generation.TerrainGenerator#lodCacheTag}); opening
 * the store for any other terrain discards every node, since a node from other
 * terrain is exactly the LOD-standing-where-the-chunk-does-not artifact this cache
 * must never produce. The serializer's version byte covers changes to the game's
 * own code; the tag covers what it cannot see — a DaedalusTGM-Exp world's terrain
 * changes whenever the model is retrained.
 *
 * <p>Threading contract:
 * <ul>
 *   <li>All JDBC calls happen on the I/O thread; {@link #tryLoad} submits and
 *       awaits, {@link #saveAsync} submits and returns.</li>
 *   <li>The single-thread executor guarantees the SQLite connection is never
 *       touched concurrently, so we don't need connection pooling or WAL.</li>
 * </ul>
 */
public final class FastLodStore implements AutoCloseable {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS fastlod_nodes (
                level INTEGER NOT NULL,
                cx    INTEGER NOT NULL,
                cz    INTEGER NOT NULL,
                data  BLOB    NOT NULL,
                PRIMARY KEY (level, cx, cz)
            ) WITHOUT ROWID;
            """;
    private static final String META_DDL = """
            CREATE TABLE IF NOT EXISTS fastlod_meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            ) WITHOUT ROWID;
            """;
    private static final String TAG_KEY = "terrain";

    private final Path dbPath;
    private final ExecutorService io;
    private final Connection connection;
    private final PreparedStatement selectStmt;
    private final PreparedStatement upsertStmt;
    /**
     * True while the database is known to contain no rows. Short-circuits
     * {@link #tryLoad} so a cold first session never blocks workers on the
     * single-thread IO executor. Flips to false as soon as a row is written.
     */
    private final AtomicBoolean knownEmpty;

    private volatile boolean closed = false;

    private FastLodStore(Path dbPath, Connection connection,
                         PreparedStatement selectStmt, PreparedStatement upsertStmt,
                         ExecutorService io, boolean knownEmpty) {
        this.dbPath = dbPath;
        this.connection = connection;
        this.selectStmt = selectStmt;
        this.upsertStmt = upsertStmt;
        this.io = io;
        this.knownEmpty = new AtomicBoolean(knownEmpty);
    }

    /**
     * Opens (or creates) the database at {@code dbPath} for nodes sampled from
     * the terrain {@code terrainTag} names, discarding any it holds from other
     * terrain (or from before stores were tagged). Returns {@code null} if the
     * driver fails or the schema can't be created — the caller should treat
     * that as "no persistence" and fall through to pure generation.
     */
    public static FastLodStore open(Path dbPath, String terrainTag) {
        try {
            Files.createDirectories(dbPath.getParent());
            // Force-load the driver once; the service loader usually handles
            // this, but some shaded jars miss META-INF entries.
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA synchronous=NORMAL;");
                st.execute(DDL);
                st.execute(META_DDL);
            }
            adoptTag(conn, dbPath, terrainTag);
            PreparedStatement select = conn.prepareStatement(
                    "SELECT data FROM fastlod_nodes WHERE level=? AND cx=? AND cz=?");
            PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO fastlod_nodes(level, cx, cz, data) VALUES(?,?,?,?) "
                  + "ON CONFLICT(level, cx, cz) DO UPDATE SET data=excluded.data");

            boolean empty = isTableEmpty(conn);

            ExecutorService io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FastLod-Store-IO");
                t.setDaemon(true);
                return t;
            });
            return new FastLodStore(dbPath, conn, select, upsert, io, empty);
        } catch (Exception e) {
            System.err.println("[FastLodStore] Failed to open " + dbPath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Blocking lookup. Returns {@code null} on miss, malformed blob, or
     * shutdown. Safe to call from worker threads — the executor serializes
     * access to the JDBC connection.
     */
    public FastLodChunkData tryLoad(FastLodKey key) {
        if (closed) return null;
        // Fast path — a fresh world (or one that hasn't persisted anything yet)
        // has an empty table, so every worker would otherwise stall on the
        // single IO thread for a guaranteed miss. Skip the round-trip entirely.
        if (knownEmpty.get()) return null;
        try {
            return io.submit(() -> loadOnIoThread(key)).get();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fire-and-forget write. Drops silently if the store has been closed. */
    public void saveAsync(FastLodChunkData data) {
        if (closed) return;
        byte[] blob = FastLodSerializer.serialize(data);
        FastLodKey key = data.key();
        // First successful save promotes the store to "might-have-data" so
        // subsequent loads actually consult SQLite.
        knownEmpty.set(false);
        io.submit(() -> writeOnIoThread(key, blob));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Close JDBC resources last, on the calling thread, since the executor
        // is already drained. Order: statements → connection.
        closeQuietly(selectStmt);
        closeQuietly(upsertStmt);
        // Null guard: connection may be null if initialization failed before it was assigned.
        try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
    }

    public Path path() { return dbPath; }

    /**
     * Uncarved nodes live in their own rows: {@code level} column = index + 8.
     * Rows 0..4 keep meaning "carved", which every row written before the
     * carve flag existed was — so old caches stay valid.
     */
    static final int UNCARVED_LEVEL_OFFSET = 8;
    /** Coarse nodes that carry tree spots: a further +16, so tree-less rows stay valid. */
    static final int COARSE_TREES_LEVEL_OFFSET = 16;

    private static int levelColumn(FastLodKey key) {
        return key.level().index() + (key.carved() ? 0 : UNCARVED_LEVEL_OFFSET)
            + (key.coarseTrees() ? COARSE_TREES_LEVEL_OFFSET : 0);
    }

    private FastLodChunkData loadOnIoThread(FastLodKey key) {
        try {
            selectStmt.clearParameters();
            selectStmt.setInt(1, levelColumn(key));
            selectStmt.setInt(2, key.chunkX());
            selectStmt.setInt(3, key.chunkZ());
            try (ResultSet rs = selectStmt.executeQuery()) {
                if (!rs.next()) return null;
                byte[] blob = rs.getBytes(1);
                return FastLodSerializer.deserialize(key, blob);
            }
        } catch (SQLException e) {
            System.err.println("[FastLodStore] load failed for " + key + ": " + e.getMessage());
            return null;
        }
    }

    private void writeOnIoThread(FastLodKey key, byte[] blob) {
        try {
            upsertStmt.clearParameters();
            upsertStmt.setInt(1, levelColumn(key));
            upsertStmt.setInt(2, key.chunkX());
            upsertStmt.setInt(3, key.chunkZ());
            upsertStmt.setBytes(4, blob);
            upsertStmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[FastLodStore] write failed for " + key + ": " + e.getMessage());
        }
    }

    /** Clears the nodes if they were sampled from terrain other than {@code tag}, then records it. */
    private static void adoptTag(Connection conn, Path dbPath, String tag) throws SQLException {
        String stored = null;
        try (PreparedStatement get = conn.prepareStatement("SELECT value FROM fastlod_meta WHERE key=?")) {
            get.setString(1, TAG_KEY);
            try (ResultSet rs = get.executeQuery()) {
                if (rs.next()) stored = rs.getString(1);
            }
        }
        if (tag.equals(stored)) return;
        try (Statement st = conn.createStatement()) {
            int dropped = st.executeUpdate("DELETE FROM fastlod_nodes");
            if (dropped > 0) {
                System.out.println("[FastLodStore] " + dbPath + ": terrain changed (" + stored + " -> " + tag
                        + "), discarded " + dropped + " cached nodes");
            }
        }
        try (PreparedStatement put = conn.prepareStatement(
                "INSERT INTO fastlod_meta(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            put.setString(1, TAG_KEY);
            put.setString(2, tag);
            put.executeUpdate();
        }
    }

    private static boolean isTableEmpty(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM fastlod_nodes LIMIT 1")) {
            return !rs.next();
        } catch (SQLException e) {
            return false;   // on error, pay the lookup cost rather than skip legitimate hits
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
