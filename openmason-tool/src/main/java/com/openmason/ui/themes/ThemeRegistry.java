package com.openmason.ui.themes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.function.Predicate;

/**
 * Thread-safe registry for theme storage and management.
 * Part of Phase 1 decomposition of ThemeManager monolith.
 * 
 * Responsibilities:
 * - Registry for built-in, community, and user themes
 * - Thread-safe theme lookup and storage operations
 * - Category-based organization and filtering
 * - Theme validation and duplicate detection
 * - Comprehensive error handling and logging
 * 
 * Estimated size: ~250 lines (extracted from ThemeManager registry functionality)
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
        public long getRegistrationTime() { return registrationTime; }
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
    
    // Statistics tracking
    private volatile long totalRegistrations = 0;
    private volatile long totalRemovals = 0;
    
    /**
     * Initialize registry with built-in themes
     */
    public ThemeRegistry() {
        initializeBuiltInThemes();
        logger.info("ThemeRegistry initialized with {} built-in themes", getThemeCount(ThemeCategory.BUILT_IN));
    }
    
    /**
     * Register a theme in the registry
     * 
     * @param name Theme name (used as unique identifier)
     * @param theme Theme definition to register
     * @param category Theme category for organization
     * @return true if registration successful, false if theme already exists
     * @throws IllegalArgumentException if validation fails
     */
    public boolean registerTheme(String name, ThemeDefinition theme, ThemeCategory category) {
        return registerTheme(name, theme, category, "manual");
    }
    
    /**
     * Register a theme in the registry with source tracking
     * 
     * @param name Theme name (used as unique identifier)
     * @param theme Theme definition to register
     * @param category Theme category for organization
     * @param source Source of the theme registration
     * @return true if registration successful, false if theme already exists
     * @throws IllegalArgumentException if validation fails
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
            totalRegistrations++;
            
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
     * Get a theme entry (including metadata) by name
     * 
     * @param name Theme name
     * @return Theme entry or null if not found
     */
    public ThemeEntry getThemeEntry(String name) {
        return themeRegistry.get(name);
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
     * Get all theme entries (including metadata)
     * 
     * @return Unmodifiable map of all theme entries
     */
    public Map<String, ThemeEntry> getAllThemeEntries() {
        return Collections.unmodifiableMap(new HashMap<>(themeRegistry));
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
     * Get theme names by category
     * 
     * @param category Theme category to filter by
     * @return List of theme names in the specified category
     */
    public List<String> getThemeNamesByCategory(ThemeCategory category) {
        if (category == null) {
            return Collections.emptyList();
        }
        
        return themeRegistry.entrySet().stream()
                .filter(entry -> entry.getValue().getCategory() == category)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
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
                totalRemovals++;
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
     * Check if a theme is registered
     * 
     * @param name Theme name to check
     * @return true if theme is registered
     */
    public boolean isThemeRegistered(String name) {
        return name != null && themeRegistry.containsKey(name);
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
     * Clear all themes from a specific category
     * 
     * @param category Category to clear
     * @return Number of themes removed
     */
    public int clearCategory(ThemeCategory category) {
        if (category == null) {
            return 0;
        }
        
        registryLock.writeLock().lock();
        try {
            List<String> toRemove = themeRegistry.entrySet().stream()
                    .filter(entry -> entry.getValue().getCategory() == category)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            
            int removedCount = 0;
            for (String name : toRemove) {
                if (themeRegistry.remove(name) != null) {
                    removedCount++;
                }
            }
            
            totalRemovals += removedCount;
            logger.info("Cleared {} themes from category {}", removedCount, category);
            return removedCount;
            
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * Find themes matching a predicate
     * 
     * @param predicate Filter condition
     * @return Map of matching themes
     */
    public Map<String, ThemeDefinition> findThemes(Predicate<ThemeEntry> predicate) {
        if (predicate == null) {
            return Collections.emptyMap();
        }
        
        registryLock.readLock().lock();
        try {
            return themeRegistry.entrySet().stream()
                    .filter(entry -> predicate.test(entry.getValue()))
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
     * Get registry statistics
     * 
     * @return Statistics string
     */
    public String getStatistics() {
        Map<ThemeCategory, Integer> counts = getCategoryCounts();
        
        return String.format(
                "ThemeRegistry Statistics: %d total themes (%d built-in, %d community, %d user, %d imported). " +
                "Lifetime: %d registrations, %d removals.",
                getThemeCount(),
                counts.get(ThemeCategory.BUILT_IN),
                counts.get(ThemeCategory.COMMUNITY), 
                counts.get(ThemeCategory.USER),
                counts.get(ThemeCategory.IMPORTED),
                totalRegistrations,
                totalRemovals
        );
    }
    
    /**
     * Validate registry integrity
     * 
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateRegistry() {
        List<String> errors = new ArrayList<>();
        
        registryLock.readLock().lock();
        try {
            for (Map.Entry<String, ThemeEntry> entry : themeRegistry.entrySet()) {
                String name = entry.getKey();
                ThemeEntry themeEntry = entry.getValue();
                ThemeDefinition theme = themeEntry.getTheme();
                
                // Validate name consistency
                if (name == null || name.trim().isEmpty()) {
                    errors.add("Invalid theme name: '" + name + "'");
                }
                
                // Validate theme definition
                if (theme == null) {
                    errors.add("Null theme definition for name: '" + name + "'");
                    continue;
                }
                
                // Validate theme itself
                try {
                    theme.validate();
                } catch (Exception e) {
                    errors.add("Theme validation failed for '" + name + "': " + e.getMessage());
                }
                
                // Validate category consistency
                if (themeEntry.getCategory() == null) {
                    errors.add("Null category for theme: '" + name + "'");
                }
            }
            
        } finally {
            registryLock.readLock().unlock();
        }
        
        if (errors.isEmpty()) {
            logger.debug("Registry validation passed for {} themes", getThemeCount());
        } else {
            logger.warn("Registry validation found {} errors", errors.size());
        }
        
        return errors;
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
    
    /**
     * Clear the entire registry (use with caution)
     */
    public void clearRegistry() {
        registryLock.writeLock().lock();
        try {
            int clearedCount = themeRegistry.size();
            themeRegistry.clear();
            totalRemovals += clearedCount;
            
            logger.warn("Cleared entire theme registry ({} themes removed)", clearedCount);
            
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * Get registry size information
     * 
     * @return Human-readable size information
     */
    public String getSizeInfo() {
        return String.format("Registry contains %d themes using approximately %d KB memory",
                           getThemeCount(), estimateMemoryUsage() / 1024);
    }
    
    /**
     * Estimate memory usage (rough approximation)
     */
    private long estimateMemoryUsage() {
        // Rough estimate: each theme entry ~2KB (theme data + metadata)
        return (long) getThemeCount() * 2048;
    }
    
    @Override
    public String toString() {
        return String.format("ThemeRegistry[themes=%d, categories=%s]", 
                           getThemeCount(), getCategoryCounts());
    }
}