package com.openmason.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Typed service lookup shared by the editor and its plugins: one instance per type, published by
 * whoever owns the service (the editor shell publishes its core services before plugins register;
 * a plugin may publish its own for plugins that require it).
 *
 * <p>Replaces reaching services through the editor shell object: code asks for the narrow type it
 * needs ({@code services.require(FileDialogService.class)}) instead of a god object.
 */
public final class ServiceRegistry {

    private final Map<Class<?>, Object> services;
    /** Hears every {@link #publish} through this view (a plugin context's rollback log). */
    private final Consumer<Registration> published;

    public ServiceRegistry() {
        this(new ConcurrentHashMap<>(), r -> { });
    }

    private ServiceRegistry(Map<Class<?>, Object> services, Consumer<Registration> published) {
        this.services = services;
        this.published = published;
    }

    /**
     * A view of the same services whose {@link #publish} calls are also reported to
     * {@code published} (the host withdraws a failed plugin's services with them).
     */
    public ServiceRegistry tracking(Consumer<Registration> published) {
        return new ServiceRegistry(services, Objects.requireNonNull(published, "published"));
    }

    /**
     * Publishes {@code service} under {@code type}; the returned handle withdraws it (only if it is
     * still the published instance).
     *
     * @throws IllegalStateException when another instance is already published under {@code type}
     */
    public <T> Registration publish(Class<T> type, T service) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        Object previous = services.putIfAbsent(type, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("A " + type.getName() + " is already published");
        }
        Registration registration = () -> services.remove(type, service);
        published.accept(registration);
        return registration;
    }

    /**
     * Publishes {@code service} under {@code type}, replacing whatever was there; null withdraws
     * it. For the editor shell's own services, which are wired (and re-wired) as the UI is
     * composed; plugins use {@link #publish}.
     */
    public <T> void put(Class<T> type, T service) {
        Objects.requireNonNull(type, "type");
        if (service == null) {
            services.remove(type);
        } else {
            services.put(type, service);
        }
    }

    /** The service published under {@code type}, if any. */
    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(services.get(type)));
    }

    /**
     * The service published under {@code type}.
     *
     * @throws IllegalStateException when none is published
     */
    public <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
                "No " + type.getName() + " is published"));
    }

    /** True when a service is published under {@code type}. */
    public boolean has(Class<?> type) {
        return services.containsKey(type);
    }
}
