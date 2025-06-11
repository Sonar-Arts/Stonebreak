# Plan to Revamp Terrain Generation

This document outlines the plan to revamp the terrain generation system based on a continentalness map and spline-based interpolation.

## 1. Introduce a `SplineInterpolator` utility

A new class, `SplineInterpolator`, will be created at `src/main/java/com/stonebreak/SplineInterpolator.java`.

- **Purpose**: To manage the control points that map continentalness values to terrain heights and to provide an interpolation method.
- **Benefits**: This encapsulates the spline logic, making it clean, reusable, and easy to modify.

## 2. Modify `World.java` for New Terrain Generation

The following modifications will be made to `src/main/java/com/stonebreak/World.java`:

### Constants and Fields:

-   **`SEA_LEVEL`**: A new `public static final int` constant set to `64`.
-   **`continentalnessNoise`**: A new `private final NoiseGenerator` field to generate the base continentalness values.
-   **`terrainSpline`**: A new `private final SplineInterpolator` field to handle the height mapping.

### Constructor (`World()`):

-   The `continentalnessNoise` generator will be initialized with a new seed (`seed + 2`).
-   The `terrainSpline` will be instantiated and populated with the following control points:
    -   `(-1.0, 70)` - Islands (raised from 20 to simulate islands above sea level)
    -   `(-0.8, 20)` - Deep ocean floor (new point for preserved deep ocean areas)
    -   `(-0.4, 60)` - Approaching coast
    -   `(-0.2, 70)` - "Just above sea level"
    -   `(0.1, 75)` - Lowlands
    -   `(0.3, 120)` - Mountain foothills
    -   `(0.7, 140)` - Common foothills (new point for enhanced foothill generation)
    -   `(1.0, 200)` - High peaks

### Update `generateTerrainHeight(int x, int z)`:

-   The current terrain generation logic will be completely replaced.
-   The new implementation will:
    1.  Get a `continentalness` value (between -1 and 1) from `continentalnessNoise.noise(x, z)`.
    2.  Pass this value to `terrainSpline.interpolate(continentalness)` to get the final terrain height.

### Update `generateBareChunk(int chunkX, int chunkZ)`:

-   The hardcoded water level check (`y < 64`) will be updated to use the new `SEA_LEVEL` constant, ensuring that any area below sea level that isn't solid ground becomes a water block.

## Data Flow Diagram

```mermaid
graph TD
    subgraph "World.java"
        A[Start Generation for (x,z)] --> B{continentalnessNoise.noise(x,z)};
        B --> C[Continentalness Value (-1 to 1)];
        C --> D{terrainSpline.interpolate(value)};
        D --> E[Final Terrain Height];
        E --> F{Fill blocks up to height};
        F --> G{Fill blocks below SEA_LEVEL with water};
    end

    subgraph "NoiseGenerator.java"
        B
    end

    subgraph "SplineInterpolator.java"
        D
    end