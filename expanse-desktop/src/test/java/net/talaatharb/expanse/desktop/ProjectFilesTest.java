package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesTest {
    @TempDir
    Path directory;

    @Test
    void atomicExportPreservesPreviousFileAndSupportsNonAsciiPaths() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("project \u00e9"));
        Path target = folder.resolve("circuit.json");
        ProjectFiles.save(target, "{\"revision\":0}");
        ProjectFiles.save(target, "{\"revision\":1}");
        assertThat(Files.readString(target)).isEqualTo("{\"revision\":1}");
        assertThat(Files.readString(folder.resolve("circuit.json.bak"))).isEqualTo("{\"revision\":0}");
        try (var files = Files.list(folder)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("circuit.json", "circuit.json.bak");
        }
    }
}
