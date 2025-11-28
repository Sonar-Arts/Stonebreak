package com.openmason.main.systems.themes.registry;

import com.openmason.main.systems.themes.core.ThemeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for theme storage and management.
 */
public class ThemeRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(ThemeRegistry.class);
    
    /**
     * Theme categories for organization and filtering
     */
    public enum ThemeCategory {
        BUILT_IN("Built-in", "Themes provided with Open Mason"),
        COMMUNITY("Community", "Themes shared by the community"),
        USER("User", "Custom themes created by the user"),
        IMPORTED("Imported", "Themes imported from external sources");
        
        private final String displayName;
        private final String description;
        
        ThemeCategory(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        
        /**
         * Convert ThemeDefinition.ThemeType to ThemeCategory
         */
        public static ThemeCategory fromThemeType(ThemeDefinition.ThemeType themeType) {
            switch (themeType) {
                case BUILT_IN: return BUILT_IN;
                case USER_CUSTOM: return USER;
                case IMPORTED: return IMPORTED;
                default: return USER;
            }
        }
    }
    
    /**
     * Theme registry entry containing theme and metadata
     */
    public static class ThemeEntry {
        private final ThemeDefinition theme;
        private final ThemeCategory category;
        private final long registrationTime;
        private final String source;
        
        public ThemeEntry(ThemeDefinition theme, ThemeCategory category, String source) {
            this.theme = theme;
            this.category = category;
            this.registrationTime = System.currentTimeMillis();
            this.source = source != null ? source : "unknown";
        }
        
        public ThemeDefinition getTheme() { return theme; }
        public ThemeCategory getCategory() { return category; }
        public String getSource() { return source; }
        
        @Override
        public String toString() {
            return String.format("ThemeEntry{name='%s', category=%s, source='%s'}", 
                                theme.getName(), category, source);
        }
    }
    
    // Thread-safe storage using ConcurrentHashMap for high-performance reads
    private final ConcurrentHashMap<String, ThemeEntry> themeRegistry = new ConcurrentHashMap<>();

    // ReadWriteLock for complex operations requiring atomicity
    private final ReadWriteLock registryLock = new ReentrantReadWriteLock();
    
    /**
     * Initialize registry with built-in themes
     */
    public ThemeRegistry() {
        initializeBuiltInThemes();
        logger.info("ThemeRegistry initialized with {} built-in themes", getThemeCount(ThemeCategory.BUILT_IN));
    }

    
    /**
     * Register a theme in the registry with source tracking
     */
    public boolean registerTheme(String name, ThemeDefinition theme, ThemeCategory category, String source) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Theme name cannot be null or empty");
        }
        
        if (theme == null) {
            throw new IllegalArgumentException("Theme definition cannot be null");
        }
        
        if (category == null) {
            throw new IllegalArgumentException("Theme category cannot be null");
        }
        
        // Validate theme before registration
        try {
            theme.validate();
        } catch (Exception e) {
            String error = String.format("Theme validation failed for '%s': %s", name, e.getMessage());
            logger.error(error);
            throw new IllegalArgumentException(error, e);
        }
        
        // Use write lock for registration to ensure atomicity
        registryLock.writeLock().lock();
        try {
            // Check for duplicates
            if (themeRegistry.containsKey(name)) {
                logger.warn("Theme '{}' already registered, registration skipped", name);
                return false;
            }
            
            // Create entry and register
            ThemeEntry entry = new ThemeEntry(theme, category, source);
            themeRegistry.put(name, entry);

            logger.debug("Registered theme '{}' in category {} from source '{}'",
                        name, category, source);
            return true;
            
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * Get a theme by name
     * 
     * @param name Theme name
     * @return Theme definition or null if not found
     */
    public ThemeDefinition getTheme(String name) {
        if (name == null) {
            return null;
        }
        
        ThemeEntry entry = themeRegistry.get(name);
        return entry != null ? entry.getTheme() : null;
    }
    
    /**
     * Get all registered themes
     *
     * @return Unmodifiable map of all themes (name -> theme definition)
     */
    public Map<String, ThemeDefinition> getAllThemes() {
        registryLock.readLock().lock();
        try {
            Map<String, ThemeDefinition> result = new HashMap<>();
            for (Map.Entry<String, ThemeEntry> entry : themeRegistry.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getTheme());
            }
            return Collections.unmodifiableMap(result);
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Get themes by category
     *
     * @param category Theme category to filter by
     * @return Map of themes in the specified category
     */
    public Map<String, ThemeDefinition> getThemesByCategory(ThemeCategory category) {
        if (category == null) {
            return Collections.emptyMap();
        }
        
        registryLock.readLock().lock();
        try {
            return themeRegistry.entrySet().stream()
                    .filter(entry -> entry.getValue().getCategory() == category)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().getTheme(),
                            (existing, replacement) -> existing,
                            LinkedHashMap::new
                    ));
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Remove a theme from the registry
     *
     * @param name Theme name to remove
     * @return true if theme was removed, false if not found
     */
    public boolean removeTheme(String name) {
        if (name == null) {
            return false;
        }
        
        registryLock.writeLock().lock();
        try {
            ThemeEntry removedEntry = themeRegistry.remove(name);
            if (removedEntry != null) {
                logger.debug("Removed theme '{}' from category {}", name, removedEntry.getCategory());
                return true;
            } else {
                logger.debug("Theme '{}' not found for removal", name);
                return false;
            }
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * Get total number of registered themes
     *
     * @return Total theme count
     */
    public int getThemeCount() {
        return themeRegistry.size();
    }
    
    /**
     * Get number of themes in a specific category
     * 
     * @param category Theme category
     * @return Count of themes in the category
     */
    public int getThemeCount(ThemeCategory category) {
        if (category == null) {
            return 0;
        }
        
        return (int) themeRegistry.values().stream()
                .filter(entry -> entry.getCategory() == category)
                .count();
    }
    
    /**
     * Get all available theme categories with counts
     * 
     * @return Map of category to theme count
     */
    public Map<ThemeCategory, Integer> getCategoryCounts() {
        Map<ThemeCategory, Integer> counts = new EnumMap<>(ThemeCategory.class);
        
        for (ThemeCategory category : ThemeCategory.values()) {
            counts.put(category, getThemeCount(category));
        }
        
        return counts;
    }
    
    /**
     * Initialize built-in themes from ColorPalette
     */
    private void initializeBuiltInThemes() {
        try {
            List<ThemeDefinition> builtInThemes = ColorPalette.getAllBuiltInThemes();
            
            for (ThemeDefinition theme : builtInThemes) {
                boolean registered = registerTheme(theme.getId(), theme, ThemeCategory.BUILT_IN, "ColorPalette");
                if (!registered) {
                    logger.warn("Failed to register built-in theme: {}", theme.getName());
                }
            }
            
            logger.info("Initialized {} built-in themes", builtInThemes.size());
            
        } catch (Exception e) {
            logger.error("Failed to initialize built-in themes", e);
        }
    }
    
    @Override
    public String toString() {
        return String.format("ThemeRegistry[themes=%d, categories=%s]", 
                           getThemeCount(), getCategoryCounts());
    }
}