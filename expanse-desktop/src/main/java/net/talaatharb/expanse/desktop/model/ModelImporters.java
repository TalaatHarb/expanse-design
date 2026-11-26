package net.talaatharb.expanse.desktop.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Registry of available importers, selected by file extension. */
public final class ModelImporters {
    static final long MAX_BYTES = 256L * 1024 * 1024;
    private final List<ModelImporter> importers;

    public ModelImporters(List<ModelImporter> importers) {
        this.importers = List.copyOf(importers);
    }

    public static ModelImporters defaults() {
        return new ModelImporters(List.of(new StlImporter(), new ObjImporter()));
    }

    public List<String> extensions() {
        return importers.stream().flatMap(i -> i.extensions().stream()).toList();
    }

    public MeshData read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        ModelImporter importer = importers.stream().filter(i -> i.extensions().contains(extension)).findFirst()
                .orElseThrow(() -> new IOException("Unsupported model format '" + extension + "'; supported: "
                        + extensions()));
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("Model file exceeds " + MAX_BYTES / (1024 * 1024) + " MiB");
        }
        try (InputStream input = Files.newInputStream(file)) {
            return importer.read(input);
        }
    }
}
