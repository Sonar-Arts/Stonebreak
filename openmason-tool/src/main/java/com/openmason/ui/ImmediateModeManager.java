package com.openmason.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Immediate Mode Manager for Dear ImGui UI system.
 * Replaces JavaFX property binding with immediate mode updates and state management.
 */
public class ImmediateModeManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ImmediateModeManager.class);
    
    private static ImmediateModeManager instance;
    
    // State management
    private final Map<String, Object> globalState = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Object>>> stateListeners = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Object>> stateProviders = new ConcurrentHashMap<>();
    
    // Update callbacks
    private final Map<String, List<Runnable>> updateCallbacks = new ConcurrentHashMap<>();
    private final Set<String> dirtyStates = ConcurrentHashMap.newKeySet();
    
    // Component registry
    private final Map<String, ImmediateModeComponent> components = new ConcurrentHashMap<>();
    private final List<ImmediateModeComponent> renderOrder = new ArrayList<>();
    
    // Performance tracking
    private long lastUpdateTime = 0;
    private int updateCount = 0;
    private final Map<String, Long> componentUpdateTimes = new ConcurrentHashMap<>();
    
    /**
     * Interface for components using immediate mode updates
     */
    public interface ImmediateModeComponent {
        String getComponentId();
        void update();
        void render();
        Map<String, Object> getState();
        void setState(String key, Object value);
        boolean isDirty();
        void markClean();
    }
    
    /**
     * State change listener interface
     */
    public interface StateChangeListener<T> {
        void onStateChanged(String key, T oldValue, T newValue);
    }
    
    private ImmediateModeManager() {
        logger.info("ImmediateModeManager initialized");
    }
    
    public static synchronized ImmediateModeManager getInstance() {
        if (instance == null) {
            instance = new ImmediateModeManager();
        }
        return instance;
    }
    
    // State Management Methods
    
    /**
     * Set a global state value
     */
    @SuppressWarnings("unchecked")
    public <T> void setState(String key, T value) {
        Object oldValue = globalState.get(key);
        globalState.put(key, value);
        
        // Mark state as dirty
        dirtyStates.add(key);
        
        // Notify listeners
        List<Consumer<Object>> listeners = stateListeners.get(key);
        if (listeners != null) {
            for (Consumer<Object> listener : listeners) {
                try {
                    listener.accept(value);
                } catch (Exception e) {
                    logger.error("Error in state listener for key: {}", key, e);
                }
            }
        }
        
        logger.debug("State updated: {} = {} (was: {})", key, value, oldValue);
    }
    
    /**
     * Get a global state value
     */
    @SuppressWarnings("unchecked")
    public <T> T getState(String key) {
        return (T) globalState.get(key);
    }
    
    /**
     * Get a global state value with default
     */
    @SuppressWarnings("unchecked")
    public <T> T getState(String key, T defaultValue) {
        return (T) globalState.getOrDefault(key, defaultValue);
    }
    
    /**
     * Check if state exists
     */
    public boolean hasState(String key) {
        return globalState.containsKey(key);
    }
    
    /**
     * Remove a state value
     */
    public void removeState(String key) {
        globalState.remove(key);
        dirtyStates.remove(key);
        logger.debug("State removed: {}", key);
    }
    
    /**
     * Add a state change listener
     */
    public void addStateListener(String key, Consumer<Object> listener) {
        stateListeners.computeIfAbsent(key, k -> new ArrayList<>()).add(listener);
        logger.debug("Added state listener for key: {}", key);
    }
    
    /**
     * Remove a state change listener
     */
    public void removeStateListener(String key, Consumer<Object> listener) {
        List<Consumer<Object>> listeners = stateListeners.get(key);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                stateListeners.remove(key);
            }
        }
        logger.debug("Removed state listener for key: {}", key);
    }
    
    /**
     * Register a state provider for dynamic state values
     */
    public void registerStateProvider(String key, Supplier<Object> provider) {
        stateProviders.put(key, provider);
        logger.debug("Registered state provider for key: {}", key);
    }
    
    /**
     * Update all state providers
     */
    public void updateStateProviders() {
        for (Map.Entry<String, Supplier<Object>> entry : stateProviders.entrySet()) {
            String key = entry.getKey();
            Supplier<Object> provider = entry.getValue();
            
            try {
                Object newValue = provider.get();
                Object currentValue = globalState.get(key);
                
                if (!Objects.equals(currentValue, newValue)) {
                    setState(key, newValue);
                }
            } catch (Exception e) {
                logger.error("Error updating state provider for key: {}", key, e);
            }
        }
    }
    
    // Component Management
    
    /**
     * Register a component
     */
    public void registerComponent(ImmediateModeComponent component) {
        String id = component.getComponentId();
        components.put(id, component);
        
        // Add to render order if not already present
        if (!renderOrder.contains(component)) {
            renderOrder.add(component);
        }
        
        logger.debug("Registered component: {}", id);
    }
    
    /**
     * Unregister a component
     */
    public void unregisterComponent(String componentId) {
        ImmediateModeComponent component = components.remove(componentId);
        if (component != null) {
            renderOrder.remove(component);
            logger.debug("Unregistered component: {}", componentId);
        }
    }
    
    /**
     * Get a component by ID
     */
    public ImmediateModeComponent getComponent(String componentId) {
        return components.get(componentId);
    }
    
    /**
     * Update all registered components
     */
    public void updateComponents() {
        long startTime = System.currentTimeMillis();
        
        // Update state providers first
        updateStateProviders();
        
        // Update all components
        for (ImmediateModeComponent component : renderOrder) {
            if (component.isDirty()) {
                long componentStart = System.currentTimeMillis();
                
                try {
                    component.update();
                    component.markClean();
                } catch (Exception e) {
                    logger.error("Error updating component: {}", component.getComponentId(), e);
                }
                
                long componentTime = System.currentTimeMillis() - componentStart;
                componentUpdateTimes.put(component.getComponentId(), componentTime);
            }
        }
        
        // Clear dirty states
        dirtyStates.clear();
        
        lastUpdateTime = System.currentTimeMillis() - startTime;
        updateCount++;
        
        if (lastUpdateTime > 16) { // Warn if update takes longer than one frame
            logger.debug("Long update cycle: {}ms", lastUpdateTime);
        }
    }
    
    /**
     * Render all registered components
     */
    public void renderComponents() {
        for (ImmediateModeComponent component : renderOrder) {
            try {
                component.render();
            } catch (Exception e) {
                logger.error("Error rendering component: {}", component.getComponentId(), e);
            }
        }
    }
    
    // Update Callbacks
    
    /**
     * Add an update callback for a specific key
     */
    public void addUpdateCallback(String key, Runnable callback) {
        updateCallbacks.computeIfAbsent(key, k -> new ArrayList<>()).add(callback);
        logger.debug("Added update callback for key: {}", key);
    }
    
    /**
     * Remove an update callback
     */
    public void removeUpdateCallback(String key, Runnable callback) {
        List<Runnable> callbacks = updateCallbacks.get(key);
        if (callbacks != null) {
            callbacks.remove(callback);
            if (callbacks.isEmpty()) {
                updateCallbacks.remove(key);
            }
        }
        logger.debug("Removed update callback for key: {}", key);
    }
    
    /**
     * Trigger update callbacks for a key
     */
    public void triggerUpdateCallbacks(String key) {
        List<Runnable> callbacks = updateCallbacks.get(key);
        if (callbacks != null) {
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    logger.error("Error in update callback for key: {}", key, e);
                }
            }
        }
    }
    
    // Utility Methods
    
    /**
     * Create a state binding between two keys
     */
    public void bindStates(String sourceKey, String targetKey) {
        addStateListener(sourceKey, value -> setState(targetKey, value));
        logger.debug("Created state binding: {} -> {}", sourceKey, targetKey);
    }
    
    /**
     * Create a state binding with transformation
     */
    public <S, T> void bindStatesWithTransform(String sourceKey, String targetKey, 
                                             java.util.function.Function<S, T> transform) {
        addStateListener(sourceKey, value -> {
            try {
                @SuppressWarnings("unchecked")
                S sourceValue = (S) value;
                T transformedValue = transform.apply(sourceValue);
                setState(targetKey, transformedValue);
            } catch (Exception e) {
                logger.error("Error in state transformation: {} -> {}", sourceKey, targetKey, e);
            }
        });
        logger.debug("Created state binding with transform: {} -> {}", sourceKey, targetKey);
    }
    
    /**
     * Create a two-way state binding
     */
    public void bindStatesBidirectional(String key1, String key2) {
        // Prevent infinite loops with a simple flag mechanism
        final boolean[] updating = {false};
        
        addStateListener(key1, value -> {
            if (!updating[0]) {
                updating[0] = true;
                setState(key2, value);
                updating[0] = false;
            }
        });
        
        addStateListener(key2, value -> {
            if (!updating[0]) {
                updating[0] = true;
                setState(key1, value);
                updating[0] = false;
            }
        });
        
        logger.debug("Created bidirectional state binding: {} <-> {}", key1, key2);
    }
    
    /**
     * Batch state updates to reduce listener notifications
     */
    public void batchStateUpdates(Runnable updateBlock) {
        // This would temporarily disable listener notifications
        // For simplicity, just run the updates
        try {
            updateBlock.run();
        } catch (Exception e) {
            logger.error("Error in batch state updates", e);
        }
    }
    
    /**
     * Get all state keys
     */
    public Set<String> getAllStateKeys() {
        return new HashSet<>(globalState.keySet());
    }
    
    /**
     * Clear all state
     */
    public void clearAllState() {
        globalState.clear();
        dirtyStates.clear();
        stateListeners.clear();
        stateProviders.clear();
        logger.info("Cleared all state");
    }
    
    /**
     * Get performance statistics
     */
    public Map<String, Object> getPerformanceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("lastUpdateTime", lastUpdateTime);
        stats.put("updateCount", updateCount);
        stats.put("componentCount", components.size());
        stats.put("stateCount", globalState.size());
        stats.put("listenerCount", stateListeners.size());
        stats.put("providerCount", stateProviders.size());
        stats.put("dirtyStateCount", dirtyStates.size());
        stats.put("componentUpdateTimes", new HashMap<>(componentUpdateTimes));
        
        return stats;
    }
    
    /**
     * Debug method to dump all state
     */
    public void dumpState() {
        logger.info("=== Immediate Mode State Dump ===");
        logger.info("Global State ({} items):", globalState.size());
        for (Map.Entry<String, Object> entry : globalState.entrySet()) {
            logger.info("  {} = {}", entry.getKey(), entry.getValue());
        }
        
        logger.info("Dirty States ({} items): {}", dirtyStates.size(), dirtyStates);
        
        logger.info("Components ({} items):", components.size());
        for (String componentId : components.keySet()) {
            Long updateTime = componentUpdateTimes.get(componentId);
            logger.info("  {} (last update: {}ms)", componentId, updateTime != null ? updateTime : "N/A");
        }
        
        logger.info("Listeners ({} keys):", stateListeners.size());
        for (Map.Entry<String, List<Consumer<Object>>> entry : stateListeners.entrySet()) {
            logger.info("  {} -> {} listeners", entry.getKey(), entry.getValue().size());
        }
        
        logger.info("Performance: {} updates, last cycle: {}ms", updateCount, lastUpdateTime);
        logger.info("=== End State Dump ===");
    }
    
    /**
     * Validate state consistency
     */
    public List<String> validateState() {
        List<String> issues = new ArrayList<>();
        
        // Check for orphaned listeners
        for (String key : stateListeners.keySet()) {
            if (!globalState.containsKey(key) && !stateProviders.containsKey(key)) {
                issues.add("Orphaned listener for non-existent state: " + key);
            }
        }
        
        // Check for orphaned providers
        for (String key : stateProviders.keySet()) {
            if (!globalState.containsKey(key)) {
                issues.add("State provider without corresponding state: " + key);
            }
        }
        
        // Check component consistency
        for (ImmediateModeComponent component : components.values()) {
            if (!renderOrder.contains(component)) {
                issues.add("Component not in render order: " + component.getComponentId());
            }
        }
        
        if (!issues.isEmpty()) {
            logger.warn("State validation found {} issues", issues.size());
            for (String issue : issues) {
                logger.warn("  {}", issue);
            }
        }
        
        return issues;
    }
}