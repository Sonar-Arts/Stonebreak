package com.stonebreak.network;

import com.stonebreak.mobs.entities.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring guard: every {@code EntityType.replicates()} type must have a shadow factory in
 * {@link EntityReplicationRegistry} — a replicating type without one spawns server-side
 * but silently never appears on any client.
 *
 * <p>Verified structurally (the factory switch must name the type) rather than by invoking
 * the factories, which construct real entities against a live World.
 */
class EntityReplicationRegistryTest {

    @Test
    void everyReplicatingTypeHasAShadowFactoryCase() throws Exception {
        // Read the registry source's createShadow switch and assert each replicating type
        // appears as a case label. Structural, but catches the "added a type, forgot the
        // factory" mistake without needing GL/world infrastructure in a unit test.
        java.nio.file.Path src = java.nio.file.Path.of(
            "src/main/java/com/stonebreak/network/EntityReplicationRegistry.java");
        if (!java.nio.file.Files.exists(src)) {
            src = java.nio.file.Path.of(
                "stonebreak-game/src/main/java/com/stonebreak/network/EntityReplicationRegistry.java");
        }
        String source = java.nio.file.Files.readString(src);
        String factorySection = source.substring(source.indexOf("createShadow"));
        for (EntityType type : EntityType.values()) {
            if (!type.replicates()) {
                continue;
            }
            assertTrue(factorySection.contains("case " + type.name()),
                "EntityType." + type.name() + " replicates() but EntityReplicationRegistry."
                    + "createShadow has no case for it — it would be invisible on clients");
        }
    }
}
