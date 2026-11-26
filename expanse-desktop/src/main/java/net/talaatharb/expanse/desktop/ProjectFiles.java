package net.talaatharb.expanse.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Local project files: atomic save with a {@code .bak} of the previous version, bounded reads and peeking. */
final class ProjectFiles {
    static final long MAX_BYTES = 8_388_608;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record DocumentInfo(UUID id, String name, String schemaVersion, long revision) {
    }

    private ProjectFiles() {
    }

    static void save(Path target, String json) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".expanse-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            if (Files.exists(absolute)) {
                Files.copy(absolute, absolute.resolveSibling(absolute.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("Project file exceeds 8 MiB: " + file);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Reads only the identity fields; full validation and migration happen on the server. */
    static DocumentInfo peek(String json) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Not a JSON project file: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject() || !root.path("id").isTextual()) {
            throw new IOException("Not an Expanse project file: missing project id");
        }
        try {
            return new DocumentInfo(UUID.fromString(root.path("id").asText()), root.path("name").asText(""),
                    root.path("schemaVersion").asText(""), root.path("revision").asLong(0));
        } catch (IllegalArgumentException e) {
            throw new IOException("Not an Expanse project file: invalid project id", e);
        }
    }
}
