package com.openmason.engine.format.omui;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Constants of the OMUI container and schema (issue #284). The normative description is
 * {@code docs/ui-program/omui-sbui-wire-contract.md}; golden fixtures live under
 * {@code openmason-engine/src/test/resources/ui/omui/}.
 *
 * <p>Version boundaries are independent: the container/schema ({@link #SCHEMA_VERSION}),
 * each widget type ({@code typeVersion} per node), the Lua {@code ui} script API
 * ({@link #UI_API_VERSION}), layout semantics ({@link #LAYOUT_SEMANTICS}) and each host
 * contract ({@code host.apis[].version}).
 *
 * <p>Version history:
 * <ul>
 *   <li>0.1 — frozen pre-release draft (#283 spike shape): flat {@code layout} object, no
 *       style sheets, no widget versions, script by archive path. Read only to upgrade.</li>
 *   <li>1.0 — first released schema.</li>
 * </ul>
 */
public final class OmuiFormat {

    public static final String FORMAT_ID = "omui";
    public static final String FILE_EXTENSION = ".omui";

    /** Newest schema this code reads and writes. */
    public static final SchemaVersion SCHEMA_VERSION = new SchemaVersion(1, 0);
    /** The frozen pre-release draft; only {@link OmuiUpgrader} reads it. */
    public static final SchemaVersion DRAFT_VERSION = new SchemaVersion(0, 1);

    /** Lua {@code ui} API version this code understands (owned by #292). */
    public static final int UI_API_VERSION = 1;
    /** Yoga v3.2.1 defaults as frozen by #283 (owned by #287). */
    public static final String LAYOUT_SEMANTICS = "flex-1";

    /** Largest value of any integer version field ({@code uiApi}, {@code typeVersion}, ...). */
    public static final int MAX_VERSION = 1_000_000;
    /** Times, durations and delays are seconds in [0, MAX_SECONDS]. */
    public static final double MAX_SECONDS = 3600;
    /** Graph canvas coordinates lie in [-MAX_COORD, MAX_COORD]. */
    public static final double MAX_COORD = 1e7;
    /** Rows in a dependency table (and derived rows in an SBUI). */
    public static final int MAX_DEPENDENCIES = 4096;

    /** Format features this reader understands. Documents list what they need in {@code requires}. */
    public static final Set<String> SUPPORTED_FEATURES = Set.of();

    // ── entries ──
    public static final String MANIFEST = "manifest.json";
    public static final String DOCUMENT = "document.json";
    public static final String DEPENDENCIES = "dependencies.json";
    public static final String STYLES_DIR = "styles/";
    public static final String STYLE_SUFFIX = ".uss.json";
    public static final String GRAPHS_DIR = "graphs/";
    public static final String GRAPH_SUFFIX = ".graph.json";
    public static final String ANIMATIONS_DIR = "animations/";
    public static final String ANIMATION_SUFFIX = ".anim.json";
    public static final String SCRIPTS_DIR = "scripts/";
    public static final String SCRIPT_SUFFIX = ".lua";
    public static final String ASSETS_DIR = "assets/";
    public static final String EDITOR_DIR = "editor/";

    // ── identifiers ──
    /**
     * Namespaced logical id: {@code stonebreak:ui/pause_menu}. Segments never start or end
     * with a dot, so an id mapped to an archive path can never form {@code .}/{@code ..}.
     */
    public static final Pattern LOGICAL_ID = Pattern.compile(
            "[a-z0-9_-](?:[a-z0-9_.-]{0,62}[a-z0-9_-])?:[a-z0-9_-](?:[a-z0-9_.-]{0,62}[a-z0-9_-])?"
                    + "(?:/[a-z0-9_-](?:[a-z0-9_.-]{0,62}[a-z0-9_-])?){0,15}");
    /** Node, rule-target, graph-node and other document-local ids. */
    public static final Pattern LOCAL_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    /** Ids of in-archive style sheets, graphs, clips and scripts ({@code menu/buttons}). */
    public static final Pattern PART_ID = Pattern.compile("[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,7}");
    /** Widget type: built-in {@code Button} or namespaced {@code stonebreak:CrucibleView}. */
    public static final Pattern WIDGET_TYPE = Pattern.compile("([a-z0-9_.-]{1,64}:)?[A-Z][A-Za-z0-9]{0,63}");
    /** Lowercase hex SHA-256. */
    public static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private OmuiFormat() {
    }

    /** True when {@code ref} names a dependency (contains a namespace) rather than an in-archive part. */
    public static boolean isDependencyRef(String ref) {
        return ref != null && ref.indexOf(':') >= 0;
    }

    public static String styleEntry(String id) {
        return STYLES_DIR + id + STYLE_SUFFIX;
    }

    public static String graphEntry(String id) {
        return GRAPHS_DIR + id + GRAPH_SUFFIX;
    }

    public static String animationEntry(String id) {
        return ANIMATIONS_DIR + id + ANIMATION_SUFFIX;
    }

    public static String scriptEntry(String id) {
        return SCRIPTS_DIR + id + SCRIPT_SUFFIX;
    }

    public static String ensureExtension(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(FILE_EXTENSION) ? path : path + FILE_EXTENSION;
    }
}
