package com.stonebreak.world.generation.animals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.animals.animalRegistry.CowAnimal;

/**
 * Handles spawning of animals in the world during chunk generation.
 * Uses a registry system to manage different animal types.
 */
public class AnimalGenerator {
    
    private static final List<Animal> registeredAnimals = new ArrayList<>();
    
    static {
        // Register all available animal types
        registerAnimal(new CowAnimal());
        // Future animals can be added here:
        // registerAnimal(new SheepAnimal());
        // registerAnimal(new PigAnimal());
    }
    
    /**
     * Registers a new animal type with the generator.
     * 
     * @param animal The animal implementation to register
     */
    public static void registerAnimal(Animal animal) {
        registeredAnimals.add(animal);
    }
    
    /**
     * Spawns animals in the given chunk based on registered animal types.
     *
     * @param world The world instance
     * @param chunk The chunk to spawn animals in
     * @param random Random number generator
     * @param randomLock Synchronization lock for random access
     */
    public static void spawnAnimals(World world, Chunk chunk, Random random, Object randomLock) {
        for (Animal animal : registeredAnimals) {
            if (animal.canSpawnInChunk(chunk)) {
                double spawnChance;
                synchronized (randomLock) {
                    spawnChance = random.nextDouble();
                }

                if (spawnChance < animal.getSpawnProbability()) {
                    animal.spawn(world, chunk, random, randomLock);
                }
            }
        }
    }
    
    /**
     * Legacy method for spawning cows.
     * Kept for backward compatibility - delegates to the new registry system.
     *
     * @param world The world instance
     * @param chunk The chunk to spawn cows in
     * @param random Random number generator
     * @param randomLock Synchronization lock for random access
     */
    @Deprecated
    public static void spawnCows(World world, Chunk chunk, Random random, Object randomLock) {
        // Find cow animal and spawn it
        for (Animal animal : registeredAnimals) {
            if ("cow".equals(animal.getName()) && animal.canSpawnInChunk(chunk)) {
                animal.spawn(world, chunk, random, randomLock);
                break;
            }
        }
    }
    
    /**
     * Gets all registered animal types.
     * 
     * @return List of registered animals
     */
    public static List<Animal> getRegisteredAnimals() {
        return new ArrayList<>(registeredAnimals);
    }
    
    /**
     * Gets a specific animal type by name.
     * 
     * @param name The name of the animal to find
     * @return The animal implementation, or null if not found
     */
    public static Animal getAnimal(String name) {
        for (Animal animal : registeredAnimals) {
            if (animal.getName().equals(name)) {
                return animal;
            }
        }
        return null;
    }
}