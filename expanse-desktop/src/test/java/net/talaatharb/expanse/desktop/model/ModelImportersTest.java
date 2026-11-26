package net.talaatharb.expanse.desktop.model;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelImportersTest {
    @TempDir
    Path directory;

    @Test
    void asciiAndBinaryStlProduceTheSameMergedMesh() throws Exception {
        String ascii = """
                solid square
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 10 0 0
                      vertex 10 10 0
                    endloop
                  endfacet
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 10 10 0
                      vertex 0 10 0
                    endloop
                  endfacet
                endsolid square
                """;
        MeshData fromAscii = new StlImporter().read(stream(ascii.getBytes(StandardCharsets.US_ASCII)));
        assertThat(fromAscii.vertexCount()).isEqualTo(4);
        assertThat(fromAscii.triangleCount()).isEqualTo(2);
        assertThat(fromAscii.bounds()).containsExactly(0, 0, 0, 10, 10, 0);

        var binary = ByteBuffer.allocate(84 + 2 * 50).order(ByteOrder.LITTLE_ENDIAN);
        binary.put("solid but actually binary".getBytes(StandardCharsets.US_ASCII));
        binary.position(80);
        binary.putInt(2);
        float[][][] facets = {{{0, 0, 0}, {10, 0, 0}, {10, 10, 0}}, {{0, 0, 0}, {10, 10, 0}, {0, 10, 0}}};
        for (float[][] facet : facets) {
            binary.putFloat(0).putFloat(0).putFloat(1);
            for (float[] v : facet) {
                binary.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
            }
            binary.putShort((short) 0);
        }
        Path file = directory.resolve("square.STL");
        Files.write(file, binary.array());
        assertThat(ModelImporters.defaults().read(file)).isEqualTo(fromAscii);
    }

    @Test
    void objQuadsAreTriangulatedAndRelativeIndicesResolve() throws Exception {
        String obj = """
                # unit cube face and a relative-index triangle
                o part
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vn 0 0 1
                f 1/1/1 2/1/1 3/1/1 4/1/1
                v 0 0 5
                f -1 1 2
                """;
        MeshData mesh = new ObjImporter().read(stream(obj.getBytes(StandardCharsets.UTF_8)));
        assertThat(mesh.vertexCount()).isEqualTo(5);
        assertThat(mesh.triangles()).containsExactly(0, 1, 2, 0, 2, 3, 4, 0, 1);
        assertThat(mesh.bounds()[5]).isEqualTo(5f);
    }

    @Test
    void malformedAndUnsupportedFilesAreRejectedWithReasons() throws Exception {
        assertThatThrownBy(() -> new ObjImporter().read(stream("v 0 0 0\nf 1 2 3\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IOException.class).hasMessageContaining("missing vertex 2");
        assertThatThrownBy(() -> new StlImporter().read(stream("solid x\nfacet\nouter loop\nvertex 0 0\nendloop\n"
                .getBytes(StandardCharsets.US_ASCII)))).isInstanceOf(IOException.class).hasMessageContaining("line 4");
        var truncated = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN).putInt(80, 1000).array();
        assertThatThrownBy(() -> new StlImporter().read(stream(truncated))).hasMessageContaining("truncated");
        Path step = Files.writeString(directory.resolve("part.step"), "ISO-10303-21;");
        assertThatThrownBy(() -> ModelImporters.defaults().read(step)).hasMessageContaining("Unsupported model format");
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
