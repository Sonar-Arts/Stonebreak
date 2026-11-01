package com.openmason.engine.ui.assets.live;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.edit.RelinkOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.openmason.engine.ui.assets.UiAssetFixtures.bytes;
import static com.openmason.engine.ui.assets.UiAssetFixtures.screenUsing;
import static org.junit.jupiter.api.Assertions.*;

/** Reverse dependencies, preview invalidation and safe resource release (#285). */
class LiveAssetsTest {

    private static final String TEX = "stonebreak:ui/textures/panel";
    private static final String HINT = "textures/panel.sbt";

    /** Stand-in for a GPU-backed decoded image. */
    static final class FakeImage {
        final UiBytes bytes;
        boolean released;

        FakeImage(UiBytes bytes) {
            this.bytes = bytes;
        }
    }

    @TempDir
    Path tmp;
    ProjectAssetSource project;
    LiveAssets live;
    List<Set<String>> notified;

    @BeforeEach
    void setUp() throws Exception {
        project = new ProjectAssetSource(new ProjectFolder(tmp), "UI/");
        project.folder().write(HINT, bytes("v1"));
        live = new LiveAssets();
        notified = new ArrayList<>();
        live.addListener(notified::add);
    }

    @Test
    void twoDocumentsSharingATextureBothRefreshAfterItIsSaved() throws Exception {
        OmuiArchive menu = screenUsing("stonebreak:ui/menu", TEX, bytes("v1"), HINT);
        OmuiArchive hud = screenUsing("stonebreak:ui/hud", TEX, bytes("v1"), HINT);
        live.track(menu, project);
        live.track(hud, project);
        UiAssetCache<FakeImage> images = cache(menu);
        live.addCache(images);

        FakeImage first = images.get(TEX);
        for (int frame = 0; frame < 100; frame++) {
            assertSame(first, images.get(TEX), "steady state is a cache hit");
        }
        assertEquals(1, images.loads());

        project.folder().write(HINT, bytes("v2"));
        Set<String> affected = live.projectFileChanged(HINT);

        assertEquals(Set.of("stonebreak:ui/hud", "stonebreak:ui/menu"), affected);
        assertEquals(List.of(affected), notified);
        assertSame(first, images.peek(TEX), "previews keep drawing the old image until the new one loads");
        FakeImage second = images.get(TEX);
        assertEquals(bytes("v2"), second.bytes);
        assertEquals(2, images.loads());
        assertFalse(first.released, "not freed mid-frame");
        images.drainReleases();
        assertTrue(first.released, "freed on the owning thread");
        assertFalse(second.released);
    }

    @Test
    void savingATextureReachesScreensThroughTheComponentsThatUseIt() {
        String button = "stonebreak:ui/components/button";
        OmuiArchive component = screenUsing(button, TEX, bytes("v1"), HINT);
        UiBytes compBytes = bytes("component bytes");
        OmuiArchive screen = OmuiArchive.of(UiManifest.create("stonebreak:ui/pause", UiManifest.DocumentKind.SCREEN, ""),
                        new UiDocument(UiNode.of("root", "Box", List.of(new UiNode("b", null, UiNode.INSTANCE_TYPE, 1,
                                List.of(), Map.of(), Map.of(), null, List.of(), new UiNode.ComponentInstance(button,
                                Map.of(), List.of(), Map.of(), Map.of()), List.of(), Map.of()))), List.of(), null,
                                null, Map.of()))
                .withDependencies(new UiDependencies(List.of(UiDependency.shared(button, UiDependency.Kind.COMPONENT,
                        compBytes.sha256(), compBytes.size(), "ui/button.omui")), Map.of()));
        live.track(component, project);
        live.track(screen, project);

        assertEquals(Set.of(button, "stonebreak:ui/pause"), live.projectFileChanged(HINT));
        assertEquals(Set.of("stonebreak:ui/pause"), live.assetChanged(button));
        assertEquals(Set.of(), live.projectFileChanged("unrelated/file.sbt"));
        assertEquals(2, notified.size(), "no notification for files nothing uses");
    }

    @Test
    void failedLoadsAreNotRetriedPerFrameAndRecoverWhenTheAssetAppears() throws Exception {
        project.folder().delete(HINT);
        OmuiArchive menu = screenUsing("stonebreak:ui/menu", TEX, bytes("v1"), HINT);
        live.track(menu, project);
        UiAssetCache<FakeImage> images = cache(menu);
        live.addCache(images);

        assertNull(images.get(TEX));
        for (int frame = 0; frame < 100; frame++) {
            assertNull(images.get(TEX));
        }
        assertEquals(1, images.loads(), "a remembered failure costs nothing per frame");

        // The artist exports the texture to its convention path (not the stale hint).
        String convention = project.conventionPath(TEX, UiDependency.Kind.TEXTURE, null);
        project.folder().write(convention, bytes("found"));
        assertEquals(Set.of("stonebreak:ui/menu"), live.projectFileChanged(convention));

        assertEquals(bytes("found"), images.get(TEX).bytes);
        assertEquals(2, images.loads());
    }

    @Test
    void relinkRecoversAMissingAsset() throws Exception {
        project.folder().delete(HINT);
        OmuiArchive menu = screenUsing("stonebreak:ui/menu", TEX, bytes("v1"), HINT);
        live.track(menu, project);
        OmuiArchive[] current = {menu};
        UiAssetCache<FakeImage> images = new UiAssetCache<>(id -> resolve(current[0], id), a -> new FakeImage(a.bytes()),
                img -> img.released = true);
        live.addCache(images);
        assertNull(images.get(TEX));

        project.folder().write("art/new_panel.sbt", bytes("relinked"));
        current[0] = RelinkOperations.relink(menu, TEX, "art/new_panel.sbt", project).after();
        live.track(current[0], project);
        live.assetChanged(TEX);

        assertEquals(bytes("relinked"), images.get(TEX).bytes);
        assertEquals(Set.of("stonebreak:ui/menu"), live.projectFileChanged("art/new_panel.sbt"),
                "the new location is tracked after re-tracking");
    }

    @Test
    void aStaleAsyncLoadNeverOverwritesANewerRevision() throws Exception {
        OmuiArchive menu = screenUsing("stonebreak:ui/menu", TEX, bytes("v1"), HINT);
        UiAssetCache<FakeImage> images = cache(menu);
        Deque<Runnable> worker = new ArrayDeque<>();

        CompletableFuture<FakeImage> slow = images.getAsync(TEX, worker::add);
        project.folder().write(HINT, bytes("v2"));
        images.invalidate(TEX);                    // saved while the v1 load is still queued
        FakeImage fresh = images.get(TEX);        // the newer revision loads and installs first
        worker.poll().run();                      // now the stale load completes

        assertEquals(bytes("v2"), fresh.bytes);
        assertSame(fresh, images.peek(TEX), "stale result was not installed");
        assertSame(fresh, slow.get(), "the stale caller is handed the current value");
        images.drainReleases();
        assertFalse(fresh.released);
    }

    @Test
    void closeReleasesEverything() throws Exception {
        OmuiArchive menu = screenUsing("stonebreak:ui/menu", TEX, bytes("v1"), HINT);
        UiAssetCache<FakeImage> images = cache(menu);
        FakeImage img = images.get(TEX);
        images.close();
        assertTrue(img.released);
        assertNull(images.peek(TEX));
    }

    @Test
    void untrackStopsNotifications() {
        OmuiArchive menu = screenUsing("stonebreak:ui/menu", TEX, bytes("v1"), HINT);
        live.track(menu, project);
        live.untrack("stonebreak:ui/menu");
        assertEquals(Set.of(), live.projectFileChanged(HINT));
        assertTrue(notified.isEmpty());
    }

    private UiAssetCache<FakeImage> cache(OmuiArchive doc) {
        Function<String, ResolvedAsset> resolve = id -> resolve(doc, id);
        return new UiAssetCache<>(resolve, a -> new FakeImage(a.bytes()), img -> img.released = true);
    }

    private ResolvedAsset resolve(OmuiArchive doc, String id) {
        return AssetResolver.forDocument(doc, List.of(project)).resolveOne(id, new UiDiagnostics());
    }
}
