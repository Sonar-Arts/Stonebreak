package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * 4-sided pyramid with apex at +Y and quad base at -Y.
 * <p>16 vertices (12 for sides + 4 for base), 18 indices, 5 logical faces.
 * Mixed topology: sides are 1 triangle each, base is 2 triangles.
 */
public final class PyramidShape implements PartShape {

    @Override public String displayName() { return "Pyramid"; }
    @Override public String description() { return "4-sided pyramid with triangular faces and a quad base"; }
    @Override public String iconFilename() { return "pyramid.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        float hw = size.x / 2.0f;
        float hh = size.y / 2.0f;
        float hd = size.z / 2.0f;

        // Base corners and apex
        float bfl_x = -hw, bfl_y = -hh, bfl_z =  hd;
        float bfr_x =  hw, bfr_y = -hh, bfr_z =  hd;
        float bbr_x =  hw, bbr_y = -hh, bbr_z = -hd;
        float bbl_x = -hw, bbl_y = -hh, bbl_z = -hd;
        float ax = 0, ay = hh, az = 0;

        float[] vertices = {
            // Face 0: Front side
            ax, ay, az,   bfl_x, bfl_y, bfl_z,   bfr_x, bfr_y, bfr_z,
            // Face 1: Right side
            ax, ay, az,   bfr_x, bfr_y, bfr_z,   bbr_x, bbr_y, bbr_z,
            // Face 2: Back side
            ax, ay, az,   bbr_x, bbr_y, bbr_z,   bbl_x, bbl_y, bbl_z,
            // Face 3: Left side
            ax, ay, az,   bbl_x, bbl_y, bbl_z,   bfl_x, bfl_y, bfl_z,
            // Face 4: Base quad
            bfl_x, bfl_y, bfl_z,
            bfr_x, bfr_y, bfr_z,
            bbr_x, bbr_y, bbr_z,
            bbl_x, bbl_y, bbl_z,
        };

        float[] texCoords = {
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            0.5f, 0.0f,   0.0f, 1.0f,   1.0f, 1.0f,
            0.0f, 0.0f,   1.0f, 0.0f,   1.0f, 1.0f,   0.0f, 1.0f,
        };

        int[] indices = {
            0, 1, 2,
            3, 4, 5,
            6, 7, 8,
            9, 10, 11,
            12, 13, 14,
            14, 15, 12,
        };

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        // 6 triangles: faces 0-3 get 1 triangle each, face 4 gets 2 triangles
        return new int[]{0, 1, 2, 3, 4, 4};
    }
}
