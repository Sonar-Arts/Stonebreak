package com.openmason.main.systems.services;

import java.util.function.Supplier;

/**
 * The open project's root folder (the directory the {@code .omp} lives in), or null when no project
 * is loaded; published as a service so dialogs and agents can start there.
 */
@FunctionalInterface
public interface ProjectDirectory extends Supplier<String> {
}
