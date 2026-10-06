package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.StyleCodec;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiFormat;
import com.openmason.engine.format.sbui.SbuiManifest;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime access to a document's dependencies through #285 asset resolution: components and
 * shared sheets for the {@link UiDocumentSource}, textures for the {@link UiPaintHost}. The game
 * builds it on {@code AssetResolver.forExport(sbui, ...)}, the editor on
 * {@code AssetResolver.forDocument(omui, ...)}; both paint the same pixels from the same bytes.
 *
 * <p>A component's own dependencies (a nested component, its sheet) resolve through that
 * component's table, built over the same sources when the component is loaded. Decoded
 * documents and sheets are cached; textures go through the shared {@link MTextureCache} under
 * {@code ui:<sha256>} so identical bytes decode once across documents.
 */
public final class ResolvedUiAssets implements UiDocumentSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResolvedUiAssets.class);

    private final List<AssetResolver> resolvers = new CopyOnWriteArrayList<>();
    private final List<? extends AssetSource> sources;
    private final MTextureCache textures;
    private final Map<String, OmuiArchive> components = new HashMap<>();
    private final Map<String, UiStyleSheet> sheets = new HashMap<>();
    private final Map<String, DerivedLua> derived = new HashMap<>();
    private String derivedDocument;

    public ResolvedUiAssets(AssetResolver resolver, List<? extends AssetSource> sources, MTextureCache textures) {
        resolvers.add(resolver);
        this.sources = List.copyOf(sources);
        this.textures = textures;
    }

    /**
     * Serves the {@code graph-lua} caches of an exported screen (#291) to the script runtime,
     * which still checks their compiler version and source hash before using one.
     */
    public synchronized ResolvedUiAssets withDerived(SbuiArchive sbui) {
        derivedDocument = sbui.manifest().entry();
        for (SbuiManifest.DerivedEntry row : sbui.manifest().derived()) {
            UiBytes bytes = sbui.derived().get(row.entry());
            if (row.kind() == SbuiManifest.DerivedKind.GRAPH_LUA && bytes != null
                && row.source().startsWith(SbuiFormat.GRAPH_SOURCE)) {
                derived.put(row.source().substring(SbuiFormat.GRAPH_SOURCE.length()), new DerivedLua(
                    new String(bytes.toArray(), java.nio.charset.StandardCharsets.UTF_8), row.sourceSha256(),
                    row.compiler(), row.compilerVersion()));
            }
        }
        return this;
    }

    @Override
    public synchronized DerivedLua derivedGraph(String documentId, String graphId) {
        return documentId.equals(derivedDocument) ? derived.get(graphId) : null;
    }

    @Override
    public synchronized OmuiArchive component(String dependencyId) {
        if (components.containsKey(dependencyId)) {
            return components.get(dependencyId);
        }
        OmuiArchive archive = null;
        ResolvedAsset asset = resolve(dependencyId);
        if (asset != null) {
            try {
                archive = OmuiReader.read(asset.bytes().toArray()).archive();
                resolvers.add(AssetResolver.forDocument(archive, sources));
            } catch (Exception e) {
                LOGGER.warn("Component {} could not be read: {}", dependencyId, e.getMessage());
            }
        }
        components.put(dependencyId, archive);
        return archive;
    }

    @Override
    public synchronized UiStyleSheet styleSheet(String dependencyId) {
        if (sheets.containsKey(dependencyId)) {
            return sheets.get(dependencyId);
        }
        UiStyleSheet sheet = null;
        ResolvedAsset asset = resolve(dependencyId);
        if (asset != null) {
            UiDiagnostics d = new UiDiagnostics();
            UiValue root = CanonicalJson.parse(asset.bytes().toArray(), dependencyId, d);
            if (root instanceof UiValue.Obj o && o.get("id") instanceof UiValue.Str id) {
                sheet = StyleCodec.read(id.value(), dependencyId, root, d);
            }
            if (sheet == null || d.hasErrors()) {
                LOGGER.warn("Style sheet {} could not be read: {}", dependencyId, d.list());
                sheet = d.hasErrors() ? null : sheet;
            }
        }
        sheets.put(dependencyId, sheet);
        return sheet;
    }

    /**
     * Source of a shared or embedded Lua module (#292), resolved like every other dependency, so
     * it follows project relocation and portable export. A binary chunk is refused (null).
     */
    @Override
    public String script(String dependencyId) {
        ResolvedAsset asset = resolve(dependencyId);
        if (asset == null) {
            return null;
        }
        byte[] bytes = asset.bytes().toArray();
        if (bytes.length > 0 && bytes[0] == 0x1B) {
            LOGGER.warn("Script {} is a binary chunk; only Lua source is accepted", dependencyId);
            return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Texture for an asset reference, decoded once per content hash; null when unresolvable. */
    public MTexture texture(String assetRef) {
        ResolvedAsset asset = resolve(assetRef);
        if (asset == null) {
            return null;
        }
        byte[] bytes = asset.bytes().toArray();
        return textures.get("ui:" + asset.sha256(), key -> {
            MTexture t = MTexture.loadFromSbtBytes(key, bytes);
            if (t == null) {
                t = MTexture.loadFromOmtBytes(key, bytes);
            }
            if (t == null && isPng(bytes)) {
                t = MTexture.fromImage(key, io.github.humbleui.skija.Image.makeDeferredFromEncodedBytes(bytes));
            }
            return t;
        });
    }

    /** A paint host serving this document's textures plus the host's draw providers. */
    public UiPaintHost paintHost(Map<String, UiPaintHost.UiDrawProvider> providers) {
        return UiPaintHost.of(this::texture, providers);
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private ResolvedAsset resolve(String id) {
        if (id == null) {
            return null;
        }
        List<AssetResolver> chain = new ArrayList<>(resolvers);
        for (AssetResolver r : chain) {
            if (r.ids().contains(id)) {
                ResolvedAsset a = r.resolveOne(id, new UiDiagnostics());
                if (a != null) {
                    return a;
                }
            }
        }
        return null;
    }
}
