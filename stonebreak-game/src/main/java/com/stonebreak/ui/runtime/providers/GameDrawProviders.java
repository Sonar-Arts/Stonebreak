package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.ui.runtime.GameUiProviders;
import io.github.humbleui.skija.Typeface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The game's GL-backed draw providers for UI documents, one shared set per game (the block icon
 * atlas is shared by every open document), {@link #install installed} into the document provider
 * registry {@link GameUiProviders}. Both halves of a provider live here so they cannot drift: the
 * implementations ({@link #providers()}) and their declared versions ({@link #DECLARED},
 * {@link #offerTo}: what document activation and the editor's export check compare with a
 * manifest's {@code providers}).
 *
 * <ul>
 *   <li>{@value ItemIconProvider#ID} v{@value ItemIconProvider#VERSION}: item icons and counts
 *       ({@code ItemSlot} props {@code item}/{@code count}/{@code state}/{@code durability}, or a
 *       {@code DrawProvider}'s {@code params})</li>
 *   <li>{@value EntityPreviewProvider#ID} v{@value EntityPreviewProvider#VERSION}: orbiting 3D
 *       player/mob previews ({@code DrawProvider} {@code params})</li>
 *   <li>{@value ClassGaugeProvider#ID} v{@value ClassGaugeProvider#VERSION}: the selected class's HUD gauge
 *       (Skia only, the game's player; {@code params.classId})</li>
 *   <li>{@value FurnaceCrucibleProvider#BOWL_ID} and {@value FurnaceCrucibleProvider#RINGS_ID}
 *       v{@value FurnaceCrucibleProvider#VERSION}: the furnace crucible and its progress rings
 *       (Skia only, {@code params} = the {@code furnace} record)</li>
 * </ul>
 *
 * Providers render GL in {@code prepare}, so hosts must run {@code UiDocumentView.prepareProviders()}
 * before opening the Masonry frame. GL thread only.
 */
public final class GameDrawProviders implements AutoCloseable {

    /** Declared id → version of every provider this game implements. */
    public static final Map<String, Integer> DECLARED = Map.of(
        ItemIconProvider.ID, ItemIconProvider.VERSION,
        EntityPreviewProvider.ID, EntityPreviewProvider.VERSION,
        FurnaceCrucibleProvider.BOWL_ID, FurnaceCrucibleProvider.VERSION,
        FurnaceCrucibleProvider.RINGS_ID, FurnaceCrucibleProvider.VERSION,
        DirtBackdropProvider.ID, DirtBackdropProvider.VERSION,
        MenuStageProvider.ID, MenuStageProvider.VERSION,
        ClassGaugeProvider.ID, ClassGaugeProvider.VERSION);

    private static GameDrawProviders instance;
    private static boolean installed;

    private final ItemIconAtlas atlas;
    private final Map<String, UiPaintHost.UiDrawProvider> providers;

    /**
     * @param blockIcons renders block icons into the shared atlas (GL), or null for a GL-free set (CPU
     *                   raster stages, the fidelity gates): block icons then paint nothing, as the
     *                   legacy screens' GL phase does on such a stage
     */
    public GameDrawProviders(ItemIconAtlas.BlockIconPainter blockIcons, Supplier<Typeface> typeface) {
        this(blockIcons, typeface, () -> null);
    }

    /** @param player whose class gauge the HUD shows (the game's local player) */
    public GameDrawProviders(ItemIconAtlas.BlockIconPainter blockIcons, Supplier<Typeface> typeface,
                             Supplier<com.stonebreak.player.Player> player) {
        this.atlas = blockIcons == null ? null : new ItemIconAtlas(blockIcons);
        Map<String, UiPaintHost.UiDrawProvider> map = new LinkedHashMap<>();
        map.put(ItemIconProvider.ID, new ItemIconProvider(atlas, typeface));
        map.put(EntityPreviewProvider.ID, new EntityPreviewProvider());
        map.put(FurnaceCrucibleProvider.BOWL_ID, FurnaceCrucibleProvider.bowl());
        map.put(FurnaceCrucibleProvider.RINGS_ID, FurnaceCrucibleProvider.rings());
        map.put(DirtBackdropProvider.ID, new DirtBackdropProvider());
        map.put(MenuStageProvider.ID, new MenuStageProvider());
        map.put(ClassGaugeProvider.ID, new ClassGaugeProvider(player, typeface));
        this.providers = java.util.Collections.unmodifiableMap(map);
        if (!providers.keySet().equals(DECLARED.keySet())) {
            throw new IllegalStateException("declared providers " + DECLARED.keySet() + " != " + providers.keySet());
        }
    }

    /** The game's shared set (legacy block icons, the game backend's typeface). */
    public static synchronized GameDrawProviders get() {
        if (instance == null) {
            instance = new GameDrawProviders(ItemIconAtlas.BlockIconPainter.LEGACY, GameDrawProviders::gameTypeface,
                com.stonebreak.core.Game::getPlayer);
        }
        return instance;
    }

    /**
     * Registers the shared set in the game's document provider registry ({@link GameUiProviders})
     * once. GL-free: nothing renders until a document prepares an element.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        get().providers().forEach(GameUiProviders::register);
    }

    /** Frees the shared set's GL resources (renderer shutdown). Safe when never created. */
    public static synchronized void shutdown() {
        if (instance != null) {
            if (installed) {
                instance.providers().keySet().forEach(id -> GameUiProviders.register(id, null));
                installed = false;
            }
            instance.close();
            instance = null;
        }
    }

    /** Provider id → implementation, for {@code UiPaintHost}. */
    public Map<String, UiPaintHost.UiDrawProvider> providers() {
        return providers;
    }

    /**
     * The providers that paint with Skia alone (no GL, no running game): what an editor preview
     * or a raster capture can draw too, instead of placeholders. Fresh instances per call.
     */
    public static Map<String, UiPaintHost.UiDrawProvider> skiaOnly() {
        return Map.of(FurnaceCrucibleProvider.BOWL_ID, FurnaceCrucibleProvider.bowl(),
            FurnaceCrucibleProvider.RINGS_ID, FurnaceCrucibleProvider.rings(),
            DirtBackdropProvider.ID, new DirtBackdropProvider(),
            MenuStageProvider.ID, new MenuStageProvider(() -> null, () -> 0),
            ClassGaugeProvider.ID, new ClassGaugeProvider(() -> null, () -> null));
    }

    /** Declares every provider on {@code host}, so activation accepts documents that need them. */
    public static void offerTo(UiHost host) {
        DECLARED.forEach(host::offerProvider);
    }

    public ItemIconAtlas atlas() {
        return atlas;
    }

    @Override
    public void close() {
        if (atlas != null) {
            atlas.close();
        }
        for (UiPaintHost.UiDrawProvider p : providers.values()) {
            if (p instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // GL handles already gone with the context
                }
            }
        }
    }

    private static Typeface gameTypeface() {
        Renderer renderer = Game.getRenderer();
        return renderer == null || renderer.getSkijaBackend() == null ? null : renderer.getSkijaBackend().typeface();
    }
}
