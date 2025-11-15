# Grid Snapping System - Open Mason Viewport

## Overview

The Open Mason viewport uses a **unified grid snapping system** based on the standard block size of **1.0 world unit**. This document explains how grid snapping works, when to use different snap increments, and how it integrates with the transform gizmo.

## Standard Block Size

All grid snapping increments are based on the **canonical block size**:

```
STANDARD_BLOCK_SIZE = 1.0 world unit
```

This matches:
- **Stonebreak game blocks**: 1.0×1.0×1.0 cubes in world space
- **.OMO model files**: Blocks span from -0.5 to +0.5 on each axis when centered
- **Visual grid**: Grid lines rendered at 1.0 unit intervals

## Available Snap Increments

The system provides **five preset snap increments**, ordered from coarse to fine:

### 1. Full Block (1.0 units)
- **1 snap position per visual grid square**
- Use for: Coarse positioning, architectural layouts
- Example: Positioning models at exact block boundaries

### 2. Half Block (0.5 units) — **RECOMMENDED DEFAULT**
- **2 snap positions per visual grid square**
- Use for: General modeling work, good balance of precision and visual alignment
- Example: Centering models between grid lines

### 3. Quarter Block (0.25 units)
- **4 snap positions per visual grid square**
- Use for: Detailed work, fine adjustments
- Example: Creating models with quarter-block offsets

### 4. Eighth Block (0.125 units)
- **8 snap positions per visual grid square**
- Use for: Very fine positioning, technical work
- Example: Precise alignment for complex multi-part models

### 5. Sixteenth Block (0.0625 units)
- **16 snap positions per visual grid square**
- Use for: Ultra-fine positioning, sub-block details
- Example: Micro-adjustments for perfect alignment

## Visual Grid Alignment

The viewport renders an **infinite grid** with the following properties:

### Grid Scale
- **Primary grid lines**: 1.0 unit intervals (matches STANDARD_BLOCK_SIZE)
- **Secondary grid lines**: 10.0 unit intervals (for scale reference)
- **Axes**: X-axis (red), Z-axis (blue) at world origin

### Grid-Snap Relationship

| Snap Increment | Snaps per Grid Square | Visual Feedback Quality |
|----------------|----------------------|-------------------------|
| 1.0 (Full)     | 1                    | ⭐⭐⭐⭐⭐ Perfect      |
| 0.5 (Half)     | 2                    | ⭐⭐⭐⭐⭐ Excellent   |
| 0.25 (Quarter) | 4                    | ⭐⭐⭐⭐ Good         |
| 0.125 (Eighth) | 8                    | ⭐⭐⭐ Fair          |
| 0.0625 (16th)  | 16                   | ⭐⭐ Poor            |

**Recommendation**: Use **0.5 (Half Block)** for best visual alignment — you can see exactly where the model will snap (halfway between grid lines).

## Gizmo Integration

Grid snapping works seamlessly with all gizmo transform modes:

### Translation Mode
- Snaps position to nearest grid increment on active axis
- Applies to:
  - Single-axis dragging (X/Y/Z arrows)
  - Plane dragging (XY/XZ/YZ squares)
- **Example**: With 0.5 snap, dragging on X-axis snaps to ..., -1.0, -0.5, 0.0, 0.5, 1.0, ...

### Rotation Mode
- Snaps rotation angle to nearest increment (degrees)
- Applies to all rotation circles (X/Y/Z)
- **Example**: With 15° snap, rotations snap to 0°, 15°, 30°, 45°, ...

### Scale Mode
- Snaps scale factor to nearest increment
- Applies to:
  - Uniform scaling (center box)
  - Per-axis scaling (X/Y/Z boxes)
- **Example**: With 0.1 snap, scale snaps to 0.9, 1.0, 1.1, 1.2, ...

## Enabling Grid Snapping

### Via Preferences UI
1. Open **Preferences** (Edit → Preferences)
2. Navigate to **Model Viewer** tab
3. Check **Enable Grid Snapping**
4. Select desired **Snap Increment** from dropdown

### Via Code
```java
viewport.setGridSnappingEnabled(true);

// Update snap increment (use constants for consistency)
ViewportState newState = viewportState.toBuilder()
    .gridSnappingEnabled(true)
    .gridSnappingIncrement(SnappingUtil.SNAP_HALF_BLOCK)
    .build();
```

### Keyboard Shortcut
- **Toggle Grid Snapping**: Currently not implemented (future enhancement)

## Implementation Details

### Core Classes

**SnappingUtil.java**
- Provides `STANDARD_BLOCK_SIZE` constant (1.0)
- Defines recommended snap increments:
  - `SNAP_FULL_BLOCK` (1.0)
  - `SNAP_HALF_BLOCK` (0.5)
  - `SNAP_QUARTER_BLOCK` (0.25)
- Implements `snapToGrid(float value, float increment)` method

**ViewportState.java**
- Stores grid snapping enabled/disabled state
- Stores current snap increment
- Default: 0.5 (half block)

**GizmoInteractionHandler.java**
- Applies snapping during gizmo drag operations
- Reads snap settings from `ViewportState`
- Coordinates with `TransformState.setPosition()` for snapped positioning

**TransformState.java**
- `setPosition(x, y, z, snapEnabled, snapIncrement)` method
- Applies snapping to all three position components
- Ensures positions stay within grid boundaries

### Snapping Algorithm

```java
public static float snapToGrid(float value, float increment) {
    if (increment <= 0) {
        return value; // No snapping
    }
    return Math.round(value / increment) * increment;
}
```

**Example**:
- Value: 1.37
- Increment: 0.5
- Result: `Math.round(1.37 / 0.5) * 0.5 = Math.round(2.74) * 0.5 = 3 * 0.5 = 1.5`

## Best Practices

### For General Work
1. **Enable grid snapping by default** for consistency
2. **Use 0.5 (half block) increment** for best visual alignment
3. **Adjust increment as needed** for fine vs. coarse work

### For Precision Work
1. Start with coarse increment (1.0) for rough positioning
2. Refine with finer increment (0.25 or 0.125)
3. Use ultra-fine (0.0625) only when absolutely necessary

### For Performance
1. Grid snapping is **lightweight** (simple multiplication/rounding)
2. No performance impact from using finer increments
3. Visual grid rendering is **optimized** with LOD (level of detail)

## Common Use Cases

### Aligning to Block Grid
```
Snap Increment: 1.0 (Full Block)
Result: Model positioned exactly on block boundaries
Use: Building aligned with Stonebreak blocks
```

### Centering Between Blocks
```
Snap Increment: 0.5 (Half Block)
Result: Model centered between grid lines
Use: Creating models that span multiple blocks
```

### Quarter-Block Details
```
Snap Increment: 0.25 (Quarter Block)
Result: Four possible positions within each block
Use: Creating stairs, slabs, or quarter-block features
```

## Troubleshooting

### Snapping Not Working
**Problem**: Gizmo dragging doesn't snap to grid

**Solutions**:
1. Check grid snapping is enabled in Preferences
2. Verify snap increment is not zero
3. Ensure gizmo is in translate mode (snapping applies to translation)
4. Check that `ViewportState` is properly passed to `GizmoRenderer`

### Visual Grid Doesn't Match Snap Grid
**Problem**: Snap positions don't align with visible grid lines

**Solution**: This is expected for fine increments (0.125, 0.0625). Use 0.5 or 1.0 for perfect visual alignment.

### Snapping Too Coarse/Fine
**Problem**: Can't position model precisely / Model jumps too far

**Solution**: Adjust snap increment in Preferences to match your precision needs.

## Technical Notes

### Grid Boundary Limits
- Position is constrained to ±10 blocks (±10.0 units) from origin
- This is defined in `TransformState.GRID_SIZE = 10.0f`
- Prevents accidental infinite positioning

### Coordinate System
- Grid snapping uses the **unified coordinate system** (`CoordinateSystem.java`)
- All snap values are in **world units** (not screen pixels)
- Screen delta → world axis projection handles coordinate conversions

### Floating Point Precision
- Grid snapping uses `Math.round()` for robust rounding
- Avoids floating-point drift over many snap operations
- Values like 0.5 are exactly representable in IEEE 754

## Future Enhancements

Potential improvements for the grid snapping system:

1. **Keyboard Toggle**: Quick enable/disable with hotkey (e.g., Shift)
2. **Temporary Disable**: Hold key to temporarily disable snapping
3. **Smart Increment**: Auto-adjust increment based on zoom level
4. **Custom Increments**: User-defined snap values beyond presets
5. **Rotation Snapping UI**: Separate angle snap increment control
6. **Visual Snap Indicators**: Show snap points when dragging gizmo

## References

- **SnappingUtil.java**: Core snapping implementation
- **COORDINATE_SYSTEMS.md**: Unified coordinate system documentation
- **ViewportState.java**: State management for grid snapping
- **GizmoInteractionHandler.java**: Gizmo-based snap application

---

**Version**: 1.0
**Last Updated**: January 2025
**Author**: Open Mason Development Team
