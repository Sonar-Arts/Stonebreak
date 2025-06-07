# Memory Debugging System for StoneBreak

## Overview
We have successfully enhanced StoneBreak with a comprehensive memory debugging and profiling system. This system helps track memory usage, detect potential memory leaks, and monitor performance during gameplay.

## Components Added

### 1. MemoryProfiler Class (`src/main/java/com/stonebreak/MemoryProfiler.java`)
- Advanced memory profiler with snapshot capabilities
- Allocation tracking and reporting
- Garbage collection monitoring
- Memory pressure detection

### 2. Enhanced Game Debug System
- Improved `displayDebugInfo()` method with more detailed information
- Memory logging methods (`logDetailedMemoryInfo`, `forceGCAndReport`)
- Integration with MemoryProfiler

### 3. Memory Monitoring Integration
- World class now tracks chunk allocation counts
- Main class logs memory usage during initialization and cleanup
- Automatic memory profiling during high-usage scenarios

## Current Features

### Automatic Debug Info (Every 5 seconds)
The game automatically displays debug information including:
- Memory usage (used/total/max MB and percentage)
- Free memory
- Chunk counts (loaded, pending mesh builds, pending GL uploads)
- Player position
- FPS and frame time
- Automatic warnings for high memory usage (>80%, >95%)

### Memory Snapshots
- Automatic snapshots during initialization phases
- Comparison between initialization stages
- Emergency snapshots during high memory usage

### Allocation Tracking
- Tracks chunk creation in World class
- Can be extended to track other object allocations

## Usage

### View Current Debug Info
Debug information is automatically printed to console every 5 seconds during gameplay.

### Manual Memory Reports
You can add manual logging calls in your code:
```java
// Log current memory state
Game.logDetailedMemoryInfo("Custom checkpoint");

// Force garbage collection and report results
Game.forceGCAndReport("After major operation");

// Get detailed memory profile
Game.printDetailedMemoryProfile();

// Get allocation statistics
Game.reportAllocations();
```

### Memory Snapshots
```java
// Take a snapshot
MemoryProfiler.getInstance().takeSnapshot("my_checkpoint");

// Compare two snapshots
MemoryProfiler.getInstance().compareSnapshots("before", "after");
```

## Console Output Examples

### Standard Debug Info (every 5 seconds):
```
========== MEMORY & PERFORMANCE DEBUG ==========
Memory Usage: 156/512 MB (15.6% of max 1024 MB)
Free Memory: 356 MB
Chunks: 45 loaded, 3 pending mesh, 1 pending GL
Player Position: 23.4, 78.2, -45.1
FPS: 144
Delta Time: 6.944 ms
===============================================
```

### Memory Snapshot Comparison:
```
========== MEMORY COMPARISON ==========
Comparing: before_initialization -> after_game_init
Heap change: +89 MB
Non-heap change: +12 MB
GC time increase: +45 ms
GC runs increase: +2
===============================================
```

### High Memory Warning:
```
⚠️  WARNING: High memory usage detected!
[MEMORY SNAPSHOT] high_memory_usage_1733515234567 - Heap: 820 MB, Non-heap: 45 MB
```

## Monitoring Key Areas

### Chunk Management
- Tracks chunk loading/unloading
- Monitors mesh building queue sizes
- Logs memory impact of chunk operations

### Initialization
- Memory usage before/after each major component
- Comparison snapshots to identify memory-heavy components

### Cleanup
- Memory release tracking during shutdown
- Garbage collection effectiveness reporting

## Future Enhancements (Ready to Implement)

### Interactive Debug Commands
The system is prepared for keyboard shortcuts:
- F3: Force immediate debug info display
- F3+M: Detailed memory profiling
- M: Allocation report and reset

### Additional Tracking
- Texture memory usage
- Mesh data memory usage
- UI component memory tracking

## Troubleshooting

### High Memory Usage
1. Check chunk count - too many loaded chunks
2. Look for memory leaks in allocation reports
3. Check GC pressure in detailed profiles

### Performance Issues
1. Monitor FPS in debug output
2. Check pending mesh/GL upload counts
3. Review GC frequency and duration

## Code Integration Points

### Adding New Allocation Tracking
```java
// In your class constructor or allocation method:
MemoryProfiler.getInstance().incrementAllocation("YourObjectType");
```

### Adding Custom Debug Points
```java
// Before major operation
MemoryProfiler.getInstance().takeSnapshot("before_operation");

// After major operation  
MemoryProfiler.getInstance().takeSnapshot("after_operation");
MemoryProfiler.getInstance().compareSnapshots("before_operation", "after_operation");
```

The memory debugging system is now fully integrated and will help identify performance bottlenecks and memory usage patterns in your StoneBreak game.