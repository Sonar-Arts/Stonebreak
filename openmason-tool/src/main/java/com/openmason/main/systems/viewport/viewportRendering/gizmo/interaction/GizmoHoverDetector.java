package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import com.openmason.main.systems.viewport.util.RaycastUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Detects which gizmo part the mouse is hovering over by raycasting
 * against the list of interactive gizmo parts.
 */
public class GizmoHoverDetector {

    /**
     * Finds the closest gizmo part intersected by a ray cast from the given
     * screen-space mouse position.
     *
     * @param mouseX         Mouse X in screen space
     * @param mouseY         Mouse Y in screen space
     * @param viewportWidth  Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix     Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param gizmoParts     List of interactive gizmo parts to test
     * @return The closest intersected part, or null if nothing was hit
     */
    public GizmoPart detectHover(float mouseX, float mouseY,
                                 int viewportWidth, int viewportHeight,
                                 Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                 List<GizmoPart> gizmoParts) {
        CoordinateSystem.Ray ray = RaycastUtil.createRayFromScreen(
                mouseX, mouseY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix
        );

        GizmoPart closestPart = null;
        float closestDistance = Float.POSITIVE_INFINITY;

        for (GizmoPart part : gizmoParts) {
            float distance = intersectPart(ray, part);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestPart = part;
            }
        }

        return closestPart;
    }

    /**
     * Tests intersection between a ray and a single gizmo part.
     *
     * @param ray  The ray to test
     * @param part The gizmo part
     * @return Distance to intersection, or Float.POSITIVE_INFINITY if no hit
     */
    private float intersectPart(CoordinateSystem.Ray ray, GizmoPart part) {
        return switch (part.getType()) {
            case ARROW, BOX, CENTER ->
                    RaycastUtil.intersectRaySphere(ray, part.getCenter(), part.getInteractionRadius());

            case PLANE ->
                    RaycastUtil.intersectRaySphere(ray, part.getCenter(), part.getInteractionRadius());

            case CIRCLE -> {
                Vector3f normal = getCircleNormal(part.getConstraint());
                yield RaycastUtil.intersectRayCircle(
                        ray,
                        part.getCenter(),
                        normal,
                        part.getInteractionRadius(),
                        part.getInteractionRadius() * 0.2f // Doubled thickness for easier clicking
                );
            }
        };
    }

    /**
     * Gets the normal vector for a rotation circle's plane.
     */
    private Vector3f getCircleNormal(AxisConstraint constraint) {
        return switch (constraint) {
            case X -> new Vector3f(1, 0, 0);
            case Y -> new Vector3f(0, 1, 0);
            case Z -> new Vector3f(0, 0, 1);
            default -> new Vector3f(0, 0, 0);
        };
    }
}
