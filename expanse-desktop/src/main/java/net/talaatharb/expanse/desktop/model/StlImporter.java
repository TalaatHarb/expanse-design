package net.talaatharb.expanse.desktop.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Reads ASCII and binary STL; coincident vertices are merged. */
public final class StlImporter implements ModelImporter {
    @Override
    public List<String> extensions() {
        return List.of("stl");
    }

    @Override
    public MeshData read(InputStream input) throws IOException {
        byte[] bytes = input.readAllBytes();
        return isBinary(bytes) ? readBinary(bytes) : readAscii(new String(bytes, StandardCharsets.US_ASCII));
    }

    private static boolean isBinary(byte[] bytes) {
        if (bytes.length >= 84) {
            long count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
            if (84 + count * 50 == bytes.length) {
                return true;
            }
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.US_ASCII)
                .trim().toLowerCase(Locale.ROOT);
        return !(head.startsWith("solid") && (head.contains("facet") || head.contains("endsolid")));
    }

    private static MeshData readBinary(byte[] bytes) throws IOException {
        if (bytes.length < 84) {
            throw new IOException("Binary STL header is truncated");
        }
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long count = buffer.getInt(80) & 0xffffffffL;
        if (count > MeshBuilder.MAX_TRIANGLES || 84 + count * 50 > bytes.length) {
            throw new IOException("Binary STL is truncated or declares too many triangles (" + count + ")");
        }
        var builder = new MeshBuilder(true);
        int[] corner = new int[3];
        for (int t = 0; t < count; t++) {
            int base = 84 + t * 50 + 12;
            for (int v = 0; v < 3; v++) {
                int at = base + v * 12;
                corner[v] = builder.vertex(buffer.getFloat(at), buffer.getFloat(at + 4), buffer.getFloat(at + 8));
            }
            builder.triangle(corner[0], corner[1], corner[2]);
        }
        return builder.build();
    }

    private static MeshData readAscii(String text) throws IOException {
        var builder = new MeshBuilder(true);
        int[] corner = new int[3];
        int corners = 0;
        int lineNumber = 0;
        for (String raw : text.split("\\R")) {
            lineNumber++;
            String line = raw.trim();
            if (line.regionMatches(true, 0, "vertex", 0, 6)) {
                String[] parts = line.split("\\s+");
                if (parts.length != 4 || corners == 3) {
                    throw new IOException("Malformed STL vertex at line " + lineNumber);
                }
                try {
                    corner[corners++] = builder.vertex(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3]));
                } catch (NumberFormatException e) {
                    throw new IOException("Malformed STL number at line " + lineNumber, e);
                }
            } else if (line.regionMatches(true, 0, "endloop", 0, 7)) {
                if (corners != 3) {
                    throw new IOException("STL facet ending at line " + lineNumber + " does not have three vertices");
                }
                builder.triangle(corner[0], corner[1], corner[2]);
                corners = 0;
            }
        }
        if (builder.triangleCount() == 0) {
            throw new IOException("STL file contains no facets");
        }
        return builder.build();
    }
}
