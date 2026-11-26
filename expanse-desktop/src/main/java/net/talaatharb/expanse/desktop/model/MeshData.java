package net.talaatharb.expanse.desktop.model;

import java.util.Arrays;

/**
 * Toolkit-independent triangle mesh in model units (millimetres, Z up).
 *
 * @param points    x, y, z triples
 * @param triangles three point indices per triangle
 */
public record MeshData(float[] points, int[] triangles) {
    public MeshData {
        if (points.length % 3 != 0 || triangles.length % 3 != 0) {
            throw new IllegalArgumentException("Mesh arrays must contain triples");
        }
        int count = points.length / 3;
        for (int index : triangles) {
            if (index < 0 || index >= count) {
                throw new IllegalArgumentException("Triangle references missing vertex " + index);
            }
        }
    }

    public int vertexCount() {
        return points.length / 3;
    }

    public int triangleCount() {
        return triangles.length / 3;
    }

    /** Axis-aligned bounds as {minX, minY, minZ, maxX, maxY, maxZ}. */
    public float[] bounds() {
        if (points.length == 0) {
            return new float[6];
        }
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < points.length; i++) {
            bounds[i % 3] = Math.min(bounds[i % 3], points[i]);
            bounds[3 + i % 3] = Math.max(bounds[3 + i % 3], points[i]);
        }
        return bounds;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MeshData mesh && Arrays.equals(points, mesh.points)
                && Arrays.equals(triangles, mesh.triangles);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(points) + Arrays.hashCode(triangles);
    }

    @Override
    public String toString() {
        return "MeshData[vertices=" + vertexCount() + ", triangles=" + triangleCount() + "]";
    }
}
