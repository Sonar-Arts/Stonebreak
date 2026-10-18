package com.openmason.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionsTest {

    private record Item(String id, String value) {
    }

    private final Contributions<Item> items = new Contributions<>("item", Item::id);

    @Test
    void keepsRegistrationOrderAndOwners() {
        items.register("a", new Item("one", "1"));
        items.register("b", new Item("two", "2"));
        items.register("a", new Item("three", "3"));

        assertEquals(List.of("one", "two", "three"), items.all().stream().map(Item::id).toList());
        assertEquals(List.of("one", "three"), items.ownedBy("a").stream().map(Item::id).toList());
        assertEquals("b", items.ownerOf("two").orElseThrow());
        assertEquals("2", items.find("two").orElseThrow().value());
    }

    @Test
    void refusesADuplicateIdNamingTheOwner() {
        items.register("first", new Item("x", "1"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> items.register("second", new Item("x", "2")));
        assertTrue(e.getMessage().contains("first"), e.getMessage());
    }

    @Test
    void removeOwnerAndRegistrationsNotifyListeners() {
        List<String> events = new ArrayList<>();
        items.addListener(new Contributions.Listener<>() {
            @Override
            public void added(Item item) {
                events.add("+" + item.id());
            }

            @Override
            public void removed(Item item) {
                events.add("-" + item.id());
            }
        });
        Registration one = items.register("a", new Item("one", "1"));
        items.register("b", new Item("two", "2"));
        items.register("b", new Item("three", "3"));

        items.removeOwner("b");
        one.close();
        one.close(); // harmless

        assertEquals(List.of("+one", "+two", "+three", "-two", "-three", "-one"), events);
        assertEquals(0, items.size());
    }

    @Test
    void aRegistrarRegistersForItsOwnerAndSeesEveryone() {
        items.register("core", new Item("core-item", ""));
        Registrar<Item> plugin = items.registrar("plugin");
        plugin.register(new Item("plugin-item", ""));

        assertEquals(2, plugin.all().size());
        assertTrue(plugin.find("core-item").isPresent());
        assertEquals("plugin", items.ownerOf("plugin-item").orElseThrow());
    }

    @Test
    void serviceRegistryTracksAndWithdrawsPublications() {
        ServiceRegistry services = new ServiceRegistry();
        List<Registration> published = new ArrayList<>();
        ServiceRegistry tracked = services.tracking(published::add);

        tracked.publish(CharSequence.class, "hello");
        assertEquals("hello", services.require(CharSequence.class), "views share the same services");
        assertThrows(IllegalStateException.class, () -> services.publish(CharSequence.class, "other"));

        published.forEach(Registration::close);
        assertTrue(services.find(CharSequence.class).isEmpty());

        services.put(CharSequence.class, "host");
        services.put(CharSequence.class, "host 2"); // the shell may re-wire its own services
        assertEquals("host 2", services.require(CharSequence.class));
    }
}
