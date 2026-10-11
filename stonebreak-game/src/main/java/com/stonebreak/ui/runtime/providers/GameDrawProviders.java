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
 *   <li>{@value IntroSceneProvider#OCEAN_ID} and {@value IntroSceneProvider#SONAR_ID}
 *       v{@value IntroSceneProvider#VERSION}: the startup intro's procedural layers (Skia only, the game's scene)</li>
 *   <li>{@value ElmFillProvider#ID} v{@value ElmFillProvider#VERSION}: the recipe book's elm-wood pane (Skia only)</li>
 *   <li>{@value TerrainMapProvider#ID} v{@value TerrainMapProvider#VERSION}: the terrain mapper's live map (Skia only)</li>
 *   <li>{@value AbilityIconProvider#ID} v{@value AbilityIconProvider#VERSION}: a class or ability icon (Skia only)</li>
 *   <li>{@value WoodPanelProvider#ID} v{@value WoodPanelProvider#VERSION}: character creation's wood panel (Skia only)</li>
 *   <li>{@value PipsProvider#ID} v{@value PipsProvider#VERSION}: the character sheet's heart / diamond rows (Skia only)</li>
 *   <li>{@value BattleHudProvider#ID} v{@value BattleHudProvider#VERSION}: a piece of the focus battle HUD (Skia only)</li>
 *   <li>{@value ChatEmojiProvider#ID} v{@value ChatEmojiProvider#VERSION}: a chat emoji, static or animated
 *       (Skia only, {@code params.emoji})</li>
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
    public static final Map<String, Integer> DECLARED = Map.ofEntries(
        Map.entry(ItemIconProvider.ID, ItemIconProvider.VERSION),
        Map.entry(EntityPreviewProvider.ID, EntityPreviewProvider.VERSION),
        Map.entry(FurnaceCrucibleProvider.BOWL_ID, FurnaceCrucibleProvider.VERSION),
        Map.entry(FurnaceCrucibleProvider.RINGS_ID, FurnaceCrucibleProvider.VERSION),
        Map.entry(DirtBackdropProvider.ID, DirtBackdropProvider.VERSION),
        Map.entry(MenuStageProvider.ID, MenuStageProvider.VERSION),
        Map.entry(ClassGaugeProvider.ID, ClassGaugeProvider.VERSION),
        Map.entry(IntroSceneProvider.OCEAN_ID, IntroSceneProvider.VERSION),
        Map.entry(IntroSceneProvider.SONAR_ID, IntroSceneProvider.VERSION),
        Map.entry(ChatEmojiProvider.ID, ChatEmojiProvider.VERSION),
        Map.entry(ElmFillProvider.ID, ElmFillProvider.VERSION),
        Map.entry(TerrainMapProvider.ID, TerrainMapProvider.VERSION),
        Map.entry(AbilityIconProvider.ID, AbilityIconProvider.VERSION),
        Map.entry(WoodPanelProvider.ID, WoodPanelProvider.VERSION),
        Map.entry(PipsProvider.ID, PipsProvider.VERSION),
        Map.entry(BattleHudProvider.ID, BattleHudProvider.VERSION));

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
        map.put(IntroSceneProvider.OCEAN_ID, IntroSceneProvider.ocean(IntroSceneProvider::gameScene));
        map.put(IntroSceneProvider.SONAR_ID, IntroSceneProvider.sonar(IntroSceneProvider::gameScene));
        map.put(ChatEmojiProvider.ID, new ChatEmojiProvider());
        map.put(ElmFillProvider.ID, new ElmFillProvider());
        map.put(TerrainMapProvider.ID, new TerrainMapProvider(TerrainMapProvider::gameState));
        map.put(AbilityIconProvider.ID, new AbilityIconProvider());
        map.put(WoodPanelProvider.ID, new WoodPanelProvider());
        map.put(PipsProvider.ID, new PipsProvider());
        map.put(BattleHudProvider.ID, new BattleHudProvider(BattleHudProvider::gameScreen, typeface));
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
        return Map.ofEntries(Map.entry(FurnaceCrucibleProvider.BOWL_ID, FurnaceCrucibleProvider.bowl()),
            Map.entry(FurnaceCrucibleProvider.RINGS_ID, FurnaceCrucibleProvider.rings()),
            Map.entry(DirtBackdropProvider.ID, new DirtBackdropProvider()),
            Map.entry(MenuStageProvider.ID, new MenuStageProvider(() -> null, () -> 0)),
            Map.entry(ClassGaugeProvider.ID, new ClassGaugeProvider(() -> null, () -> null)),
            Map.entry(IntroSceneProvider.OCEAN_ID, IntroSceneProvider.ocean(() -> null)),
            Map.entry(IntroSceneProvider.SONAR_ID, IntroSceneProvider.sonar(() -> null)),
            Map.entry(ChatEmojiProvider.ID, new ChatEmojiProvider()),
            Map.entry(ElmFillProvider.ID, new ElmFillProvider()),
            Map.entry(TerrainMapProvider.ID, new TerrainMapProvider(() -> null)),
            Map.entry(AbilityIconProvider.ID, new AbilityIconProvider()),
            Map.entry(WoodPanelProvider.ID, new WoodPanelProvider()),
            Map.entry(PipsProvider.ID, new PipsProvider()),
            Map.entry(BattleHudProvider.ID, new BattleHudProvider(() -> null, () -> null)));
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
