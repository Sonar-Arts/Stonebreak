package com.stonebreak.rendering;

import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.core.Game;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Advanced water effects system with realistic physics, wave simulation,
 * and visual enhancements for immersive water rendering.
 */
public class WaterEffects {
    // Particle system constants
    private static final int MAX_PARTICLES = 200;
    private static final int MAX_SPLASH_PARTICLES = 100;
    private static final float PARTICLE_LIFETIME = 2.0f;
    private static final float SPLASH_LIFETIME = 1.0f;
    private static final float PARTICLE_SPAWN_INTERVAL = 0.05f;
    private static final float PARTICLE_SIZE = 0.08f;
    private static final float BUBBLE_SIZE = 0.05f;
    
    // Wave simulation constants
    private static final float WAVE_AMPLITUDE = 0.15f;
    private static final float WAVE_SPEED = 1.5f;
    private static final float RIPPLE_SPEED = 3.0f;
    private static final float RIPPLE_DECAY = 2.0f;
    
    // Water flow constants
    private static final float FLOW_UPDATE_INTERVAL = 0.1f;
    private static final int MAX_WATER_LEVEL = 8;
    private static final float FLOW_DAMPING = 0.95f;
    
    // Visual enhancement constants
    private static final float CAUSTIC_INTENSITY = 0.3f;
    private static final float REFRACTION_STRENGTH = 0.02f;
    
    // Core systems
    private final List<WaterParticle> particles;
    private final List<WaterParticle> splashParticles;
    private final List<BubbleParticle> bubbles;
    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final Set<Vector3i> waterSources;
    private final Queue<Vector3i> waterUpdateQueue;
    private final List<WaterRipple> ripples;
    private final Map<Vector3i, WaveData> waveField;
    
    // Timing and state
    private float timeSinceLastSpawn;
    private float timeSinceWaterUpdate;
    private float totalTime;
    private final Random random;
    
    // Performance optimization
    private final Map<Vector3i, Float> heightCache;
    private final Set<Vector3i> dirtyBlocks;
    private boolean needsWaveUpdate;
    
    public WaterEffects() {
        this.particles = Collections.synchronizedList(new ArrayList<>());
        this.splashParticles = Collections.synchronizedList(new ArrayList<>());
        this.bubbles = Collections.synchronizedList(new ArrayList<>());
        this.waterBlocks = new ConcurrentHashMap<>();
        this.waterSources = Collections.synchronizedSet(new HashSet<>());
        this.waterUpdateQueue = new ConcurrentLinkedQueue<>();
        this.ripples = Collections.synchronizedList(new ArrayList<>());
        this.waveField = new ConcurrentHashMap<>();
        
        this.random = new Random();
        this.timeSinceLastSpawn = 0.0f;
        this.timeSinceWaterUpdate = 0.0f;
        this.totalTime = 0.0f;
        
        this.heightCache = new ConcurrentHashMap<>();
        this.dirtyBlocks = Collections.synchronizedSet(new HashSet<>());
        this.needsWaveUpdate = false;
    }
    
    /**
     * Main update method handling all water effects systems.
     */
    public void update(Player player, float deltaTime) {
        totalTime += deltaTime;
        
        // Update all systems
        updateParticles(player, deltaTime);
        updateSplashParticles(deltaTime);
        updateBubbles(deltaTime);
        updateRipples(deltaTime);
        updateWaveSimulation(deltaTime);
        updateWaterFlow(deltaTime);
        
        // Clear per-frame caches – wave height depends on time, so cached heights
        // must be invalidated every update to avoid appearing static.
        if (needsWaveUpdate) {
            heightCache.clear();
            needsWaveUpdate = false;
        }
    }
    
    /**
     * Updates water particles with enhanced physics.
     */
    private void updateParticles(Player player, float deltaTime) {
        // Spawn particles based on player movement
        if (player.isInWater()) {
            Vector3f velocity = player.getVelocity();
            float speed = velocity.length();
            
            if (speed > 0.5f) {
                timeSinceLastSpawn += deltaTime;
                
                if (timeSinceLastSpawn >= PARTICLE_SPAWN_INTERVAL && particles.size() < MAX_PARTICLES) {
                    spawnMovementParticles(player, speed);
                    timeSinceLastSpawn = 0.0f;
                }
                
                // Create ripples from movement
                if (speed > 1.0f && random.nextFloat() < 0.3f) {
                    createRipple(player.getPosition(), speed * 0.5f, speed * 2.0f);
                }
            }
            
            // Spawn bubbles occasionally
            if (random.nextFloat() < 0.02f && bubbles.size() < 50) {
                spawnBubble(player.getPosition());
            }
        }
        
        // Update existing particles
        particles.removeIf(particle -> {
            particle.update(deltaTime);
            return particle.isDead();
        });
    }
    
    /**
     * Updates splash particles with gravity and spread.
     */
    private void updateSplashParticles(float deltaTime) {
        splashParticles.removeIf(particle -> {
            particle.update(deltaTime);
            particle.velocity.y -= 9.8f * deltaTime; // Gravity
            return particle.isDead();
        });
    }
    
    /**
     * Updates bubble particles with buoyancy.
     */
    private void updateBubbles(float deltaTime) {
        bubbles.removeIf(bubble -> {
            bubble.update(deltaTime);
            bubble.position.y += bubble.riseSpeed * deltaTime;
            
            // Check if bubble reached surface
            World world = Game.getWorld();
            if (world != null) {
                BlockType above = world.getBlockAt(
                    (int)bubble.position.x,
                    (int)(bubble.position.y + 0.5f),
                    (int)bubble.position.z
                );
                
                if (above != BlockType.WATER) {
                    // Bubble popped at surface
                    if (random.nextFloat() < 0.3f) {
                        createSmallSplash(bubble.position);
                    }
                    return true;
                }
            }
            
            return bubble.lifetime <= 0;
        });
    }
    
    /**
     * Updates water ripples with expansion and decay.
     */
    private void updateRipples(float deltaTime) {
        ripples.removeIf(ripple -> {
            ripple.update(deltaTime);
            return ripple.isDead();
        });
    }
    
    /**
     * Updates wave simulation for realistic water surface.
     */
    private void updateWaveSimulation(float deltaTime) {
        // Update wave field based on wind and time
        for (Map.Entry<Vector3i, WaveData> entry : waveField.entrySet()) {
            WaveData wave = entry.getValue();
            wave.update(deltaTime, totalTime);
        }
        
        // Mark for height cache update
        needsWaveUpdate = true;
    }
    
    /**
     * Updates water flow physics with improved algorithm.
     */
    private void updateWaterFlow(float deltaTime) {
        timeSinceWaterUpdate += deltaTime;
        
        if (timeSinceWaterUpdate >= FLOW_UPDATE_INTERVAL) {
            processWaterFlow();
            timeSinceWaterUpdate = 0.0f;
        }
    }
    
    /**
     * Processes water flow using cellular automaton approach.
     */
    private void processWaterFlow() {
        World world = Game.getWorld();
        if (world == null) return;
        
        Set<Vector3i> processed = new HashSet<>();
        List<FlowUpdate> updates = new ArrayList<>();
        
        // Process update queue
        while (!waterUpdateQueue.isEmpty()) {
            Vector3i pos = waterUpdateQueue.poll();
            if (processed.contains(pos)) continue;
            processed.add(pos);
            
            WaterBlock water = waterBlocks.get(pos);
            if (water == null) continue;
            
            // Check if still valid
            if (world.getBlockAt(pos.x, pos.y, pos.z) != BlockType.WATER) {
                waterBlocks.remove(pos);
                waterSources.remove(pos);
                continue;
            }
            
            // Process flow
            processWaterBlockFlow(pos, water, world, updates);
        }
        
        // Apply all updates
        for (FlowUpdate update : updates) {
            applyFlowUpdate(update, world);
        }
    }
    
    /**
     * Processes flow for a single water block.
     */
    private void processWaterBlockFlow(Vector3i pos, WaterBlock water, World world, List<FlowUpdate> updates) {
        // Sources maintain full level
        if (waterSources.contains(pos)) {
            water.level = MAX_WATER_LEVEL;
            water.pressure = MAX_WATER_LEVEL;
        }
        
        // Calculate pressure based on water above
        float pressure = calculatePressure(pos, world);
        water.pressure = pressure;
        
        // Priority 1: Flow downward
        Vector3i down = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (canFlowTo(down, world)) {
            float flowRate = Math.min(water.level, pressure);
            updates.add(new FlowUpdate(pos, down, flowRate, true));
            return;
        }
        
        // Priority 2: Spread horizontally based on pressure
        if (water.level > 1 || pressure > 2) {
            spreadHorizontally(pos, water, world, updates);
        }
        
        // Update flow direction for visual effects
        updateFlowDirection(pos, water);
    }
    
    /**
     * Calculates water pressure at a position.
     */
    private float calculatePressure(Vector3i pos, World world) {
        float pressure = 0;
        int y = pos.y + 1;
        
        // Check water column above
        while (y < WorldConfiguration.WORLD_HEIGHT) {
            if (world.getBlockAt(pos.x, y, pos.z) == BlockType.WATER) {
                WaterBlock above = waterBlocks.get(new Vector3i(pos.x, y, pos.z));
                if (above != null) {
                    pressure += above.level * 0.5f;
                }
                y++;
            } else {
                break;
            }
        }
        
        return Math.min(pressure, MAX_WATER_LEVEL);
    }
    
    /**
     * Spreads water horizontally based on pressure differentials.
     */
    private void spreadHorizontally(Vector3i pos, WaterBlock water, World world, List<FlowUpdate> updates) {
        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };
        
        // Calculate flow to each direction
        for (Vector3i dir : directions) {
            Vector3i target = new Vector3i(pos).add(dir);
            
            if (canFlowTo(target, world)) {
                WaterBlock targetWater = waterBlocks.get(target);
                float targetLevel = targetWater != null ? targetWater.level : 0;
                
                // Flow based on level difference and pressure
                float levelDiff = water.level - targetLevel;
                float flowRate = (levelDiff + water.pressure * 0.2f) * FLOW_DAMPING;
                
                if (flowRate > 0.1f) {
                    updates.add(new FlowUpdate(pos, target, Math.min(flowRate, water.level - 1), false));
                }
            }
        }
    }
    
    /**
     * Updates flow direction for visual current effects.
     */
    private void updateFlowDirection(Vector3i pos, WaterBlock water) {
        Vector3f flowDir = new Vector3f();
        float totalFlow = 0;
        
        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };
        
        for (Vector3i dir : directions) {
            Vector3i neighbor = new Vector3i(pos).add(dir);
            WaterBlock neighborWater = waterBlocks.get(neighbor);
            
            if (neighborWater != null) {
                float diff = water.level - neighborWater.level;
                if (diff > 0) {
                    flowDir.add(dir.x * diff, 0, dir.z * diff);
                    totalFlow += diff;
                }
            }
        }
        
        if (totalFlow > 0) {
            flowDir.normalize();
            water.flowDirection.lerp(flowDir, 0.3f);
        }
    }
    
    /**
     * Spawns movement particles with velocity based on player motion.
     */
    private void spawnMovementParticles(Player player, float speed) {
        Vector3f pos = new Vector3f(player.getPosition());
        Vector3f playerVel = player.getVelocity();
        
        // Spawn multiple particles in a spread pattern
        int count = Math.min(5, (int)(speed * 2));
        for (int i = 0; i < count; i++) {
            Vector3f offset = new Vector3f(
                (random.nextFloat() - 0.5f) * 0.5f,
                random.nextFloat() * 0.3f,
                (random.nextFloat() - 0.5f) * 0.5f
            );
            
            Vector3f velocity = new Vector3f(
                -playerVel.x * 0.3f + (random.nextFloat() - 0.5f) * 0.5f,
                random.nextFloat() * 0.8f + 0.2f,
                -playerVel.z * 0.3f + (random.nextFloat() - 0.5f) * 0.5f
            );
            
            WaterParticle particle = new WaterParticle(
                new Vector3f(pos).add(offset),
                velocity,
                PARTICLE_LIFETIME,
                ParticleType.SPRAY
            );
            
            particles.add(particle);
        }
    }
    
    /**
     * Spawns a bubble particle with random properties.
     */
    private void spawnBubble(Vector3f position) {
        Vector3f pos = new Vector3f(
            position.x + (random.nextFloat() - 0.5f) * 0.5f,
            position.y - random.nextFloat() * 0.5f,
            position.z + (random.nextFloat() - 0.5f) * 0.5f
        );
        
        BubbleParticle bubble = new BubbleParticle(
            pos,
            BUBBLE_SIZE + random.nextFloat() * 0.03f,
            0.5f + random.nextFloat() * 0.3f
        );
        
        bubbles.add(bubble);
    }
    
    /**
     * Creates a water ripple at the specified position.
     */
    public void createRipple(Vector3f position, float strength, float maxRadius) {
        if (ripples.size() < 50) {
            ripples.add(new WaterRipple(new Vector3f(position), strength, maxRadius));
        }
    }
    
    /**
     * Creates a splash effect when something enters water.
     */
    public void createSplash(Vector3f position, float intensity) {
        // Create ripple
        createRipple(position, intensity * 2, intensity * 5);
        
        // Create splash particles
        int count = (int)(intensity * 20);
        for (int i = 0; i < count && splashParticles.size() < MAX_SPLASH_PARTICLES; i++) {
            float angle = random.nextFloat() * (float)Math.PI * 2;
            float speed = random.nextFloat() * intensity * 2;
            
            Vector3f velocity = new Vector3f(
                (float)Math.cos(angle) * speed,
                random.nextFloat() * intensity * 3 + 1,
                (float)Math.sin(angle) * speed
            );
            
            WaterParticle particle = new WaterParticle(
                new Vector3f(position),
                velocity,
                SPLASH_LIFETIME,
                ParticleType.SPLASH
            );
            
            splashParticles.add(particle);
        }
        
        // Spawn bubbles
        for (int i = 0; i < intensity * 5 && bubbles.size() < 100; i++) {
            spawnBubble(position);
        }
    }
    
    /**
     * Creates a small splash effect for bubbles popping.
     */
    private void createSmallSplash(Vector3f position) {
        for (int i = 0; i < 3; i++) {
            Vector3f velocity = new Vector3f(
                (random.nextFloat() - 0.5f) * 0.3f,
                random.nextFloat() * 0.5f + 0.2f,
                (random.nextFloat() - 0.5f) * 0.3f
            );
            
            WaterParticle particle = new WaterParticle(
                new Vector3f(position),
                velocity,
                0.5f,
                ParticleType.DROPLET
            );
            
            splashParticles.add(particle);
        }
    }
    
    /**
     * Gets the water surface height at a position including waves.
     */
    public float getWaterSurfaceHeight(float x, float z) {
        Vector3i blockPos = new Vector3i((int)x, 0, (int)z);
        
        // Check cache first
        Float cached = heightCache.get(blockPos);
        if (cached != null) {
            return cached;
        }
        
        // Calculate base height
        float baseHeight = 0.875f; // Default water height
        
        // Add wave displacement
        float waveHeight = calculateWaveHeight(x, z);
        
        // Add ripple effects
        float rippleHeight = calculateRippleHeight(x, z);
        
        float totalHeight = baseHeight + waveHeight + rippleHeight;
        heightCache.put(blockPos, totalHeight);
        
        return totalHeight;
    }
    
    /**
     * Calculates wave height using multiple octaves with seamless tiling.
     */
    private float calculateWaveHeight(float x, float z) {
        float height = 0;
        
        // Use consistent frequencies that ensure seamless tiling
        final float seamlessScale = 0.5f; // Scale for world-space seamless tiling
        
        // Primary wave - consistent with texture generation
        height += Math.sin(x * seamlessScale + totalTime * WAVE_SPEED) * 
                 Math.cos(z * seamlessScale * 0.8f + totalTime * WAVE_SPEED * 0.9f) * 
                 WAVE_AMPLITUDE;
        
        // Secondary wave - smaller, faster, still seamless
        height += Math.sin(x * seamlessScale * 2.0f + z * seamlessScale * 1.5f + totalTime * WAVE_SPEED * 2.0f) * 
                 WAVE_AMPLITUDE * 0.3f;
        
        // Tertiary wave - detail with seamless frequency
        height += Math.sin(x * seamlessScale * 4.0f + totalTime * 3.0f) * 
                 Math.cos(z * seamlessScale * 3.0f + totalTime * 2.5f) * 
                 WAVE_AMPLITUDE * 0.1f;
        
        return height;
    }
    
    /**
     * Calculates ripple displacement at a position.
     */
    private float calculateRippleHeight(float x, float z) {
        float height = 0;
        
        for (WaterRipple ripple : ripples) {
            float dist = (float)Math.sqrt(
                Math.pow(x - ripple.center.x, 2) + 
                Math.pow(z - ripple.center.z, 2)
            );
            
            if (dist < ripple.radius && dist > ripple.radius - 1.0f) {
                float wave = (float)Math.sin((dist - ripple.radius) * Math.PI) * 
                            ripple.strength * 
                            (1.0f - ripple.radius / ripple.maxRadius);
                height += wave;
            }
        }
        
        return height;
    }
    
    /**
     * Updates the height cache for changed blocks.
     */
    private void updateHeightCache() {
        for (Vector3i dirty : dirtyBlocks) {
            heightCache.remove(dirty);
        }
        dirtyBlocks.clear();
    }
    
    /**
     * Checks if water can flow to a position.
     */
    private boolean canFlowTo(Vector3i pos, World world) {
        BlockType block = world.getBlockAt(pos.x, pos.y, pos.z);
        return block == BlockType.AIR || block == BlockType.WATER;
    }
    
    /**
     * Applies a flow update to the visual water system (no chunk modification).
     */
    private void applyFlowUpdate(FlowUpdate update, World world) {
        WaterBlock source = waterBlocks.get(update.from);
        if (source == null || source.level < update.amount) return;
        
        // Create or update target (visual only - no chunk modification)
        WaterBlock target = waterBlocks.get(update.to);
        if (target == null) {
            // Only create visual water block - do not modify world chunks
            target = new WaterBlock();
            waterBlocks.put(update.to, target);
        }
        
        // Transfer water (visual only)
        if (!waterSources.contains(update.from)) {
            source.level -= update.amount;
            if (source.level <= 0) {
                // Remove from visual system only - do not modify world chunks
                waterBlocks.remove(update.from);
            }
        }
        
        target.level = Math.min(MAX_WATER_LEVEL, target.level + update.amount);
        
        // Queue neighbors for visual update only
        queueNeighbors(update.to);
    }
    
    /**
     * Queues neighboring blocks for update.
     */
    private void queueNeighbors(Vector3i pos) {
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y + 1, pos.z),
            new Vector3i(pos.x, pos.y - 1, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1)
        };
        
        for (Vector3i neighbor : neighbors) {
            if (waterBlocks.containsKey(neighbor)) {
                waterUpdateQueue.offer(neighbor);
            }
        }
    }
    
    /**
     * Adds a water source block.
     */
    public void addWaterSource(int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null) return;
        
        Vector3i pos = new Vector3i(x, y, z);
        world.setBlockAt(x, y, z, BlockType.WATER);
        
        WaterBlock water = new WaterBlock();
        water.level = MAX_WATER_LEVEL;
        
        waterBlocks.put(pos, water);
        waterSources.add(pos);
        waterUpdateQueue.offer(pos);
        
        // Create initial wave data
        waveField.put(pos, new WaveData());
    }
    
    /**
     * Gets foam intensity at a position.
     */
    public float getFoamIntensity(float x, float y, float z) {
        // Check for water turbulence
        Vector3i pos = new Vector3i((int)x, (int)y, (int)z);
        WaterBlock water = waterBlocks.get(pos);
        
        if (water == null) return 0;
        
        // Foam based on flow speed and pressure changes
        float foam = water.flowDirection.length() * 0.5f;
        
        // Add foam near edges
        World world = Game.getWorld();
        if (world != null) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    
                    BlockType neighbor = world.getBlockAt((int)x + dx, (int)y, (int)z + dz);
                    if (neighbor != BlockType.WATER && neighbor != BlockType.AIR) {
                        foam += 0.3f;
                    }
                }
            }
        }
        
        return Math.min(foam, 1.0f);
    }
    
    /**
     * Gets caustic light pattern intensity with seamless tiling.
     */
    public float getCausticIntensity(float x, float z) {
        // Use seamless frequencies for animated caustic pattern
        final float seamlessScale = 0.5f; // Match wave calculation scale
        
        float pattern1 = (float)(Math.sin(x * seamlessScale * 6.0f + totalTime * 0.5) * 
                                Math.cos(z * seamlessScale * 6.0f + totalTime * 0.3));
        float pattern2 = (float)(Math.sin(x * seamlessScale * 10.0f - totalTime * 0.7) * 
                                Math.cos(z * seamlessScale * 8.0f - totalTime * 0.4));
        
        return (pattern1 + pattern2 * 0.5f) * CAUSTIC_INTENSITY + CAUSTIC_INTENSITY;
    }
    
    /**
     * Gets refraction offset for underwater distortion with seamless tiling.
     */
    public Vector2f getRefractionOffset(float x, float z) {
        // Use seamless scale for refraction offset
        final float seamlessScale = 0.5f; // Match other calculations
        
        float offsetX = (float)Math.sin(x * seamlessScale * 4.0f + totalTime) * REFRACTION_STRENGTH;
        float offsetZ = (float)Math.cos(z * seamlessScale * 4.0f + totalTime * 0.8f) * REFRACTION_STRENGTH;
        
        return new Vector2f(offsetX, offsetZ);
    }
    
    /**
     * Detects existing water blocks in the world.
     */
    public void detectExistingWater() {
        World world = Game.getWorld();
        if (world == null) return;
        
        waterBlocks.clear();
        waterSources.clear();
        waterUpdateQueue.clear();
        
        // For now, we'll rely on the addWaterSource method being called
        // when water blocks are placed or generated in the world
        // This avoids the need to scan all chunks which may not be loaded yet
    }
    
    /**
     * Removes a water source at the specified position.
     */
    public void removeWaterSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        waterSources.remove(pos);
        
        // If not a source anymore, queue for flow update
        if (waterBlocks.containsKey(pos)) {
            waterUpdateQueue.offer(pos);
        }
    }
    
    /**
     * Gets the water level at a specific position.
     */
    public float getWaterLevel(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock water = waterBlocks.get(pos);
        
        if (water != null) {
            return water.level / (float)MAX_WATER_LEVEL;
        }
        
        // Check if water block exists in world
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return 1.0f; // Full water block
        }
        
        return 0.0f;
    }
    
    /**
     * Checks if a position contains a water source.
     */
    public boolean isWaterSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        return waterSources.contains(pos);
    }
    
    /**
     * Gets the visual height of water for rendering.
     */
    public float getWaterVisualHeight(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock water = waterBlocks.get(pos);
        
        if (water != null) {
            // Add wave effects to visual height
            float baseHeight = water.getVisualHeight();
            float waveOffset = calculateWaveHeight(x + 0.5f, z + 0.5f) * 0.5f;
            return baseHeight + waveOffset;
        }
        
        // Default water height if block exists but not in system
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            float waveOffset = calculateWaveHeight(x + 0.5f, z + 0.5f) * 0.5f;
            return 0.875f + waveOffset;
        }
        
        return 0.0f;
    }
    
    // Getters for rendering
    public List<WaterParticle> getParticles() { return new ArrayList<>(particles); }
    public List<WaterParticle> getSplashParticles() { return new ArrayList<>(splashParticles); }
    public List<BubbleParticle> getBubbles() { return new ArrayList<>(bubbles); }
    public List<WaterRipple> getRipples() { return new ArrayList<>(ripples); }
    
    /**
     * Water block with enhanced properties.
     */
    private static class WaterBlock {
        float level = MAX_WATER_LEVEL;
        float pressure = 0;
        Vector3f flowDirection = new Vector3f();
        
        public float getVisualHeight() {
            return Math.max(0.125f, level / (float)MAX_WATER_LEVEL);
        }
    }
    
    /**
     * Wave data for surface animation.
     */
    private static class WaveData {
        
        void update(float deltaTime, float totalTime) {
            // Wave data update logic can be extended here if needed
        }
    }
    
    /**
     * Flow update information.
     */
    private static class FlowUpdate {
        Vector3i from;
        Vector3i to;
        float amount;
        
        FlowUpdate(Vector3i from, Vector3i to, float amount, boolean isVertical) {
            this.from = from;
            this.to = to;
            this.amount = amount;
        }
    }
    
    /**
     * Enhanced water particle with physics.
     */
    public static class WaterParticle {
        private final Vector3f position;
        private final Vector3f velocity;
        private float lifetime;
        private final float initialLifetime;
        private final ParticleType type;
        private final float size;
        
        public WaterParticle(Vector3f position, Vector3f velocity, float lifetime, ParticleType type) {
            this.position = position;
            this.velocity = velocity;
            this.lifetime = lifetime;
            this.initialLifetime = lifetime;
            this.type = type;
            this.size = type == ParticleType.SPLASH ? PARTICLE_SIZE * 1.5f : PARTICLE_SIZE;
        }
        
        public void update(float deltaTime) {
            position.add(velocity.x * deltaTime, velocity.y * deltaTime, velocity.z * deltaTime);
            
            // Physics based on type
            switch (type) {
                case SPRAY -> {
                    velocity.mul(0.98f); // Air resistance
                    velocity.y -= 4.0f * deltaTime; // Light gravity
                }
                case SPLASH -> {
                    velocity.mul(0.95f);
                    velocity.y -= 9.8f * deltaTime; // Full gravity
                }
                case DROPLET -> {
                    velocity.mul(0.99f);
                    velocity.y -= 6.0f * deltaTime;
                }
            }
            
            lifetime -= deltaTime;
        }
        
        public boolean isDead() { return lifetime <= 0; }
        public Vector3f getPosition() { return position; }
        public float getOpacity() { return Math.max(0, lifetime / initialLifetime); }
        public float getSize() { return size * getOpacity(); }
        public ParticleType getType() { return type; }
    }
    
    /**
     * Bubble particle with buoyancy.
     */
    public static class BubbleParticle {
        private final Vector3f position;
        private final float size;
        private final float riseSpeed;
        private float lifetime;
        private float wobble;
        
        public BubbleParticle(Vector3f position, float size, float riseSpeed) {
            this.position = position;
            this.size = size;
            this.riseSpeed = riseSpeed;
            this.lifetime = 5.0f;
            this.wobble = (float)(Math.random() * Math.PI * 2);
        }
        
        public void update(float deltaTime) {
            wobble += deltaTime * 3;
            position.x += Math.sin(wobble) * 0.02f;
            position.z += Math.cos(wobble * 0.7f) * 0.02f;
            lifetime -= deltaTime;
        }
        
        public Vector3f getPosition() { return position; }
        public float getSize() { return size; }
        public float getOpacity() { return Math.min(1, lifetime); }
    }
    
    /**
     * Water ripple effect.
     */
    public static class WaterRipple {
        private final Vector3f center;
        private float radius;
        private final float maxRadius;
        private float strength;
        
        public WaterRipple(Vector3f center, float strength, float maxRadius) {
            this.center = center;
            this.strength = strength;
            this.maxRadius = maxRadius;
            this.radius = 0;
        }
        
        public void update(float deltaTime) {
            radius += RIPPLE_SPEED * deltaTime;
            strength *= Math.pow(0.5f, deltaTime * RIPPLE_DECAY);
        }
        
        public boolean isDead() { return radius > maxRadius || strength < 0.01f; }
    }
    
    /**
     * Particle type enumeration.
     */
    public enum ParticleType {
        SPRAY,    // Light mist particles
        SPLASH,   // Heavy splash particles
        DROPLET   // Small water droplets
    }
}
