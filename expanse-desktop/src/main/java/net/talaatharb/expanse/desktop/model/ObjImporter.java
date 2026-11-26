package net.talaatharb.expanse.desktop.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Wavefront OBJ geometry ({@code v} and {@code f}); polygons are fan-triangulated, negative (relative)
 * indices are supported and texture/normal references, groups and materials are ignored.
 */
public final class ObjImporter implements ModelImporter {
    @Override
    public List<String> extensions() {
        return List.of("obj");
    }

    @Override
    public MeshData read(InputStream input) throws IOException {
        var builder = new MeshBuilder(false);
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String raw;
        int lineNumber = 0;
        var face = new ArrayList<Integer>();
        while ((raw = reader.readLine()) != null) {
            lineNumber++;
            String line = raw.strip();
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            try {
                switch (parts[0]) {
                    case "v" -> {
                        if (parts.length < 4) {
                            throw new IOException("Vertex needs three coordinates at line " + lineNumber);
                        }
                        builder.vertex(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                                Float.parseFloat(parts[3]));
                    }
                    case "f" -> {
                        if (parts.length < 4) {
                            throw new IOException("Face needs at least three vertices at line " + lineNumber);
                        }
                        face.clear();
                        for (int i = 1; i < parts.length; i++) {
                            int slash = parts[i].indexOf('/');
                            int index = Integer.parseInt(slash < 0 ? parts[i] : parts[i].substring(0, slash));
                            int resolved = index > 0 ? index - 1 : builder.vertexCount() + index;
                            if (index == 0 || resolved < 0 || resolved >= builder.vertexCount()) {
                                throw new IOException("Face references missing vertex " + index + " at line "
                                        + lineNumber);
                            }
                            face.add(resolved);
                        }
                        for (int i = 1; i + 1 < face.size(); i++) {
                            builder.triangle(face.get(0), face.get(i), face.get(i + 1));
                        }
                    }
                    default -> {
                        // vt, vn, g, o, s, usemtl and mtllib carry no geometry needed here.
                    }
                }
            } catch (NumberFormatException e) {
                throw new IOException("Malformed number at line " + lineNumber, e);
            }
        }
        if (builder.triangleCount() == 0) {
            throw new IOException("OBJ file contains no faces");
        }
        return builder.build();
    }
}
