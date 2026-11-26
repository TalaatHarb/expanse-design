package net.talaatharb.expanse.desktop.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Adapter for 3D model formats. Implementations convert a stream into a toolkit-independent {@link MeshData}; a
 * third-party importer (for example InteractiveMesh's jfx3dimporter) can be plugged in behind this interface.
 */
public interface ModelImporter {
    /** Lower-case file extensions without the dot. */
    List<String> extensions();

    MeshData read(InputStream input) throws IOException;
}
