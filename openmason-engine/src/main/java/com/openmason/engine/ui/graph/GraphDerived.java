package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.sbui.SbuiExporter;
import com.openmason.engine.format.sbui.SbuiFormat;
import com.openmason.engine.format.sbui.SbuiManifest;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.runtime.UiDocumentSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Graph Lua as SBUI derived caches (#291): {@code derived/graphs/<id>.lua}, keyed to the
 * graph's canonical source hash and {@link GraphCompiler#VERSION}. An export compiles every
 * graph of the document; one that fails its checks blocks the export, with each problem located
 * at its node ({@code GRAPH_INVALID}, JSON pointer into the graph entry). The runtime uses a
 * cache only while both keys still match and compiles the graph itself otherwise, so a cache
 * is never edited and never the only copy of anything.
 */
public final class GraphDerived {

    private GraphDerived() {
    }

    /** {@code graphs/<id>.lua} (under {@code derived/}). */
    public static String name(String graphId) {
        return "graphs/" + graphId + ".lua";
    }

    /**
     * Release Lua of every graph of {@code doc}, ready for {@link SbuiExporter.Options#derived()}.
     *
     * @param source components and shared scripts the graphs reference (signals, Lua signatures)
     * @throws UiFormatException listing every graph error with its node
     */
    public static List<SbuiExporter.DerivedInput> compileAll(OmuiArchive doc, UiDocumentSource source)
            throws UiFormatException {
        GraphEnvironment env = DocumentEnvironment.of(doc, source);
        UiDiagnostics d = new UiDiagnostics();
        List<SbuiExporter.DerivedInput> out = new ArrayList<>();
        for (UiGraph g : doc.graphs().values()) {
            CompiledGraph c = GraphCompiler.compile(g, env, false);
            for (GraphDiagnostic x : c.diagnostics()) {
                String entry = OmuiFormat.graphEntry(g.id());
                String message = x.location() + ": " + x.code() + ": " + x.message();
                if (x.isError()) {
                    d.error(UiDiagnostic.Code.GRAPH_INVALID, entry, pointer(g, x), message);
                } else if (x.severity() == GraphDiagnostic.Severity.WARNING) {
                    d.warning(UiDiagnostic.Code.GRAPH_INVALID, entry, pointer(g, x), message);
                }
            }
            if (c.ok()) {
                out.add(new SbuiExporter.DerivedInput(name(g.id()), SbuiManifest.DerivedKind.GRAPH_LUA,
                    SbuiFormat.GRAPH_SOURCE + g.id(), UiBytes.utf8(c.lua()), GraphCompiler.COMPILER,
                    GraphCompiler.VERSION));
            }
        }
        d.throwIfErrors("Behavior graphs have errors; fix them before exporting");
        return out;
    }

    /** RFC 6901 pointer of a diagnostic's node in its graph entry ({@code /functions/0/nodes/2}). */
    static String pointer(UiGraph g, GraphDiagnostic x) {
        if (x.node().isEmpty()) {
            if (x.function().isEmpty()) {
                return "";
            }
            for (int i = 0; i < g.functions().size(); i++) {
                if (g.functions().get(i).id().equals(x.function())) {
                    return "/functions/" + i;
                }
            }
            return "";
        }
        if (x.function().isEmpty()) {
            return nodePointer("", g.nodes(), x.node());
        }
        for (int i = 0; i < g.functions().size(); i++) {
            UiGraph.GraphFunction f = g.functions().get(i);
            if (f.id().equals(x.function())) {
                return nodePointer("/functions/" + i, f.nodes(), x.node());
            }
        }
        return "";
    }

    private static String nodePointer(String prefix, List<UiGraph.GraphNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(id)) {
                return prefix + "/nodes/" + i;
            }
        }
        return prefix;
    }

    /**
     * What graphs need to see of a document at export time: its components and shared scripts,
     * resolved through #285 asset resolution over {@code sources} (no textures, no GL).
     */
    public static UiDocumentSource exportSource(OmuiArchive doc, List<? extends AssetSource> sources) {
        AssetResolver resolver = AssetResolver.forDocument(doc, sources);
        return new UiDocumentSource() {
            @Override
            public OmuiArchive component(String dependencyId) {
                ResolvedAsset a = resolver.resolveOne(dependencyId, new UiDiagnostics());
                try {
                    return a == null ? null : OmuiReader.read(a.bytes().toArray()).archive();
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public UiStyleSheet styleSheet(String dependencyId) {
                return null;
            }

            @Override
            public String script(String dependencyId) {
                ResolvedAsset a = resolver.resolveOne(dependencyId, new UiDiagnostics());
                return a == null ? null : new String(a.bytes().toArray(), StandardCharsets.UTF_8);
            }
        };
    }
}
