package com.stonebreak.openmason;

import com.openmason.api.target.GameTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Stonebreak checkout setting (Preferences > Stonebreak): which folders count as a checkout,
 * that a saved folder is what the target resolves (live, and after a restart), and that a launch
 * flag wins over it.
 */
class StonebreakResourcesTest {

    @TempDir
    Path tmp;

    private Path checkout;
    private Path module;
    private Path tree;
    private Path settings;

    @BeforeEach
    void fakeCheckout() throws Exception {
        System.clearProperty(StonebreakResources.PROPERTY);
        checkout = tmp.resolve("Stonebreak");
        module = checkout.resolve("stonebreak-game");
        tree = module.resolve("src/main/resources");
        Files.createDirectories(tree.resolve("sbo/blocks"));
        settings = tmp.resolve("settings/stonebreak.properties");
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty(StonebreakResources.PROPERTY);
    }

    @Test
    void acceptsTheCheckoutTheModuleOrTheResourcesFolder() throws Exception {
        assertEquals(tree, StonebreakResources.normalize(checkout));
        assertEquals(tree, StonebreakResources.normalize(module));
        assertEquals(tree, StonebreakResources.normalize(tree));
        assertEquals(tree, StonebreakResources.normalize(" " + checkout + " "));

        Path other = Files.createDirectories(tmp.resolve("not-stonebreak/src"));
        assertNull(StonebreakResources.normalize(other));
        assertNull(StonebreakResources.normalize(tmp.resolve("missing")));
        assertNull(StonebreakResources.normalize(""));
        assertNull(StonebreakResources.normalize((Path) null));
    }

    @Test
    void aSavedFolderIsWhatTheTargetResolves() throws Exception {
        StonebreakResources r = StonebreakResources.install(settings);
        GameTarget target = StonebreakGameTarget.create();

        r.save(tree);

        assertEquals(tree, target.resources().locate(), "applies at once, no restart");
        assertEquals(new StonebreakResources.Status(StonebreakResources.Source.SETTING, tree), r.status());
        assertEquals(tree, StonebreakResources.install(settings).saved(), "kept across restarts");
    }

    @Test
    void savedFolderIsReappliedOnTheNextStart() throws Exception {
        StonebreakResources.install(settings).save(tree);
        System.clearProperty(StonebreakResources.PROPERTY); // a new process

        StonebreakResources.install(settings);

        assertEquals(tree, StonebreakGameTarget.create().resources().locate());
    }

    @Test
    void clearingTheSettingFallsBackToTheWorkingFolder() throws Exception {
        StonebreakResources r = StonebreakResources.install(settings);
        r.save(tree);
        r.save(null);

        assertNull(r.saved());
        assertNull(System.getProperty(StonebreakResources.PROPERTY));
        assertNotEquals(StonebreakResources.Source.SETTING, r.status().source());
    }

    @Test
    void aLaunchFlagOverridesTheSetting() throws Exception {
        Path flagged = tmp.resolve("Other/stonebreak-game/src/main/resources");
        Files.createDirectories(flagged.resolve("sbo"));
        System.setProperty(StonebreakResources.PROPERTY, flagged.toString());

        StonebreakResources r = StonebreakResources.install(settings);
        r.save(tree);

        assertEquals(flagged, StonebreakGameTarget.create().resources().locate());
        assertEquals(new StonebreakResources.Status(StonebreakResources.Source.COMMAND_LINE, flagged), r.status());
        assertEquals(tree, r.saved(), "still saved for launches without the flag");
    }

    @Test
    void aSavedFolderThatWasDeletedIsIgnored() throws Exception {
        StonebreakResources.install(settings).save(tree);
        System.clearProperty(StonebreakResources.PROPERTY);
        Files.delete(tree.resolve("sbo/blocks"));
        Files.delete(tree.resolve("sbo"));
        Files.delete(tree);

        StonebreakResources r = StonebreakResources.install(settings);

        assertNotEquals(StonebreakResources.Source.SETTING, r.status().source());
        assertNotEquals(tree, StonebreakGameTarget.create().resources().locate());
    }
}
