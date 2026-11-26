package net.talaatharb.expanse.desktop.model;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Accumulates vertices (merging exact duplicates) and triangles with size limits. */
final class MeshBuilder {
    static final int MAX_TRIANGLES = 2_000_000;
    private final Map<Long, Integer> merged = new HashMap<>();
    private final boolean merge;
    private float[] points = new float[3 * 1024];
    private int[] triangles = new int[3 * 1024];
    private int pointCount;
    private int triangleCount;

    MeshBuilder(boolean merge) {
        this.merge = merge;
    }

    int vertex(float x, float y, float z) throws IOException {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IOException("Mesh contains non-finite coordinates");
        }
        Long key = null;
        if (merge) {
            key = (long) Float.floatToIntBits(x) * 31L * 31L + (long) Float.floatToIntBits(y) * 31L
                    + Float.floatToIntBits(z);
            Integer existing = merged.get(key);
            if (existing != null && points[existing * 3] == x && points[existing * 3 + 1] == y
                    && points[existing * 3 + 2] == z) {
                return existing;
            }
        }
        if (pointCount * 3 + 3 > points.length) {
            points = Arrays.copyOf(points, points.length * 2);
        }
        points[pointCount * 3] = x;
        points[pointCount * 3 + 1] = y;
        points[pointCount * 3 + 2] = z;
        if (key != null) {
            merged.putIfAbsent(key, pointCount);
        }
        return pointCount++;
    }

    int vertexCount() {
        return pointCount;
    }

    void triangle(int a, int b, int c) throws IOException {
        if (a == b || b == c || a == c) {
            return;
        }
        if (triangleCount >= MAX_TRIANGLES) {
            throw new IOException("Mesh exceeds " + MAX_TRIANGLES + " triangles");
        }
        if (triangleCount * 3 + 3 > triangles.length) {
            triangles = Arrays.copyOf(triangles, triangles.length * 2);
        }
        triangles[triangleCount * 3] = a;
        triangles[triangleCount * 3 + 1] = b;
        triangles[triangleCount * 3 + 2] = c;
        triangleCount++;
    }

    int triangleCount() {
        return triangleCount;
    }

    MeshData build() {
        return new MeshData(Arrays.copyOf(points, pointCount * 3), Arrays.copyOf(triangles, triangleCount * 3));
    }
}
