package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiSpriteRef;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheets;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.StyleCodec;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiFormat;
import com.openmason.engine.format.sbui.SbuiManifest;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetRow;
import com.openmason.engine.ui.assets.SpriteBinding;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, Optional<ResolvedAsset>> resolved = new ConcurrentHashMap<>();
    private final Map<String, Optional<UiSpriteSheet>> parsedSheets = new ConcurrentHashMap<>();
    private final Map<String, SheetView> sheetViews = new ConcurrentHashMap<>();
    private final Map<String, Optional<UiImage>> images = new ConcurrentHashMap<>();
    private final Map<String, Optional<MTexture>> texturesById = new ConcurrentHashMap<>();
    private final Map<String, UiDiagnostic> spriteFindings = new LinkedHashMap<>();

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
                resolved.values().removeIf(Optional::isEmpty); // the new table may hold a remembered miss
                images.values().removeIf(Optional::isEmpty);
                texturesById.values().removeIf(Optional::isEmpty);
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
        if (assetRef == null) {
            return null;
        }
        Optional<MTexture> known = texturesById.get(assetRef);
        if (known != null) {
            return known.orElse(null);
        }
        ResolvedAsset asset = resolve(assetRef);
        MTexture t = asset == null ? null
            : textures.get("ui:" + asset.sha256(), key -> MTexture.decode(key, asset.bytes().toArray()));
        texturesById.put(assetRef, Optional.ofNullable(t));
        return t;
    }

    /**
     * What an asset reference draws: a whole texture, or for {@code <sheet>#<name>} the sprite
     * region or skin (#294). Null when unresolvable, unknown or outside its texture. Resolved once
     * per reference until {@link #invalidate}/{@link #refresh}: painting and measuring every
     * element every frame is a map lookup.
     */
    public UiImage image(String assetRef) {
        if (assetRef == null) {
            return null;
        }
        Optional<UiImage> known = images.get(assetRef);
        if (known != null) {
            return known.orElse(null);
        }
        UiImage img;
        UiSpriteRef ref = UiSpriteRef.parse(assetRef);
        if (ref == null) {
            MTexture t = texture(assetRef);
            img = t == null ? null : UiImage.whole(t);
        } else {
            SheetView view = sheetView(ref.sheet());
            img = view == null ? null : view.image(ref.name());
        }
        images.put(assetRef, Optional.ofNullable(img));
        return img;
    }

    @Override
    public double[] spritePivot(String assetRef) {
        if (assetRef == null || assetRef.indexOf('#') < 0) {
            return null;
        }
        com.openmason.engine.format.omui.UiSpriteRef ref;
        try {
            ref = com.openmason.engine.format.omui.UiSpriteRef.parse(assetRef);
        } catch (RuntimeException e) {
            return null;
        }
        UiSpriteSheet sheet = ref == null ? null : spriteSheet(ref.sheet());
        if (sheet == null) {
            return null;
        }
        String name = sheet.skin(ref.name()).map(UiSpriteSheet.Skin::normal).orElse(ref.name());
        return sheet.sprite(name).map(s -> new double[]{s.pivotX(), s.pivotY()}).orElse(null);
    }

    /** The parsed sheet a {@code sprites} dependency resolves to, or null. */
    public UiSpriteSheet spriteSheet(String sheetId) {
        ResolvedAsset asset = resolve(sheetId);
        return asset == null ? null : parse(sheetId, asset);
    }

    /** The geometry check of a sheet against its resolved texture, or null when either is missing. */
    public UiSpriteSheets.Check spriteCheck(String sheetId) {
        SheetView view = sheetView(sheetId);
        return view == null ? null : view.check;
    }

    /** Every sprite problem met so far (invalid sheets, regions, slices, unknown names), oldest first. */
    public synchronized List<UiDiagnostic> spriteDiagnostics() {
        return List.copyOf(spriteFindings.values());
    }

    /**
     * Forgets resolved bytes so the next lookup reads the sources again (a texture or sheet was
     * saved). Decoded textures and parsed sheets are keyed by content hash and stay valid.
     */
    public void invalidate() {
        resolved.clear();
        dropDerivedLookups();
    }

    /** What {@link #refresh} found: the ids whose bytes changed and the texture cache keys they left. */
    public record Refresh(Set<String> changed, Set<String> staleTextureKeys) {
        public boolean any() {
            return !changed.isEmpty();
        }
    }

    /**
     * Re-resolves every id resolved so far and keeps what is unchanged: only ids whose bytes (or
     * presence) changed are replaced, and lookups derived from them are dropped. The returned
     * stale texture keys are what an editor may {@link #forget} once no open document draws them.
     */
    public synchronized Refresh refresh() {
        Set<String> changed = new java.util.TreeSet<>();
        Set<String> stale = new java.util.TreeSet<>();
        for (Map.Entry<String, Optional<ResolvedAsset>> e : new ArrayList<>(resolved.entrySet())) {
            ResolvedAsset old = e.getValue().orElse(null);
            ResolvedAsset now = resolveNow(e.getKey());
            String before = old == null ? null : old.sha256();
            String after = now == null ? null : now.sha256();
            if (!java.util.Objects.equals(before, after)) {
                changed.add(e.getKey());
                if (before != null) {
                    stale.add("ui:" + before);
                }
                resolved.put(e.getKey(), Optional.ofNullable(now));
            }
        }
        if (!changed.isEmpty()) {
            dropDerivedLookups();
        }
        return new Refresh(changed, stale);
    }

    /** Every asset currently resolved (for file watching), in no particular order. */
    public List<ResolvedAsset> resolvedAssets() {
        List<ResolvedAsset> out = new ArrayList<>();
        resolved.values().forEach(o -> o.ifPresent(out::add));
        return out;
    }

    /** Texture cache keys of everything currently resolved (non-textures included, harmlessly). */
    public Set<String> textureKeys() {
        Set<String> out = new java.util.HashSet<>();
        resolved.values().forEach(o -> o.ifPresent(a -> out.add("ui:" + a.sha256())));
        return out;
    }

    /**
     * Drops {@code keys} from the shared texture cache without closing them: an image still drawn
     * somewhere stays alive until the GC collects it, and a later lookup decodes it again.
     */
    public void forget(java.util.Collection<String> keys) {
        keys.forEach(textures::forget);
    }

    private void dropDerivedLookups() {
        images.clear();
        texturesById.clear();
        sheetViews.clear();
    }

    private SheetView sheetView(String sheetId) {
        ResolvedAsset asset = resolve(sheetId);
        if (asset == null) {
            return null;
        }
        UiSpriteSheet sheet = parse(sheetId, asset);
        if (sheet == null) {
            return null;
        }
        SpriteBinding binding = SpriteBinding.of(sheetId, sheet, this::row);
        if (binding.problem() != null) {
            report(new UiDiagnostic(binding.fatal() ? UiDiagnostic.Severity.ERROR : UiDiagnostic.Severity.WARNING,
                UiDiagnostic.Code.UNRESOLVED_REFERENCE, sheetId, "", binding.problem()));
        }
        MTexture texture = binding.texture() == null ? null : texture(binding.texture());
        if (texture == null) {
            if (!binding.fatal()) {
                report(new UiDiagnostic(UiDiagnostic.Severity.ERROR, UiDiagnostic.Code.UNRESOLVED_REFERENCE, sheetId,
                    "", "Texture '" + binding.texture() + "' of sprite sheet '" + sheetId + "' does not resolve"));
            }
            return null;
        }
        return sheetViews.computeIfAbsent(asset.sha256() + "|" + texture.resourcePath(), k -> {
            SheetView v = new SheetView(sheetId, sheet, texture,
                UiSpriteSheets.check(sheet, texture.width(), texture.height(), sheetId));
            v.check.diagnostics().forEach(this::report);
            return v;
        });
    }

    private UiSpriteSheet parse(String sheetId, ResolvedAsset asset) {
        return parsedSheets.computeIfAbsent(asset.sha256(), k -> {
            UiDiagnostics d = new UiDiagnostics();
            UiSpriteSheet sheet = SpriteSheetCodec.read(asset.bytes().toArray(), sheetId, d);
            d.list().stream().filter(UiDiagnostic::isError).forEach(this::report);
            return Optional.ofNullable(d.hasErrors() ? null : sheet);
        }).orElse(null);
    }

    private synchronized void report(UiDiagnostic d) {
        String key = d.code() + "|" + d.entry() + "|" + d.pointer() + "|" + d.message();
        if (spriteFindings.putIfAbsent(key, d) == null) {
            LOGGER.warn("Sprites: {}", d);
        }
    }

    /** A sheet bound to one texture revision: regions resolved once per name. */
    private final class SheetView {
        final String sheetId;
        final UiSpriteSheet sheet;
        final MTexture texture;
        final UiSpriteSheets.Check check;
        final Map<String, Optional<UiImage>> images = new ConcurrentHashMap<>();

        SheetView(String sheetId, UiSpriteSheet sheet, MTexture texture, UiSpriteSheets.Check check) {
            this.sheetId = sheetId;
            this.sheet = sheet;
            this.texture = texture;
            this.check = check;
        }

        UiImage image(String name) {
            return images.computeIfAbsent(name, n -> Optional.ofNullable(build(n))).orElse(null);
        }

        private UiImage build(String name) {
            UiImage.Region region = region(name);
            if (region != null) {
                return region;
            }
            UiSpriteSheet.Skin skin = sheet.skin(name).orElse(null);
            if (skin != null) {
                Map<String, UiImage.Region> states = new HashMap<>();
                for (String state : UiSpriteSheet.Skin.STATES) {
                    UiImage.Region r = skin.region(state) == null ? null : region(skin.region(state));
                    if (r != null) {
                        states.put(state, r);
                    }
                }
                return states.containsKey("normal") ? new UiImage.Skinned(skin, states) : null;
            }
            if (sheet.sprite(name).isEmpty()) {
                report(new UiDiagnostic(UiDiagnostic.Severity.ERROR, UiDiagnostic.Code.UNKNOWN_SPRITE, sheetId, "",
                    "Sprite sheet '" + sheetId + "' has no sprite or skin '" + name + "'"));
            }
            return null;
        }

        private UiImage.Region region(String name) {
            UiSpriteSheet.Sprite sprite = sheet.sprite(name).orElse(null);
            if (sprite == null || !check.drawable(name)) {
                return null;
            }
            return new UiImage.Region(texture, sprite, check.sliceUsable(name));
        }
    }

    /** A paint host serving this document's textures plus the host's draw providers. */
    public UiPaintHost paintHost(Map<String, UiPaintHost.UiDrawProvider> providers) {
        return UiPaintHost.of(this::texture, this::image, providers);
    }

    /** The row {@code id} has in the first table that lists it. */
    private AssetRow row(String id) {
        for (AssetResolver r : resolvers) {
            AssetRow row = r.row(id);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    private ResolvedAsset resolve(String id) {
        if (id == null) {
            return null;
        }
        Optional<ResolvedAsset> known = resolved.get(id);
        if (known != null) {
            return known.orElse(null);
        }
        ResolvedAsset a = resolveNow(id);
        resolved.put(id, Optional.ofNullable(a));
        return a;
    }

    private ResolvedAsset resolveNow(String id) {
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
