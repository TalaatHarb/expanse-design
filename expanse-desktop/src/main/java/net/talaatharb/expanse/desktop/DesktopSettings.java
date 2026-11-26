package net.talaatharb.expanse.desktop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Connection settings edited in File > Settings and stored in {@code ~/.expanse-design/settings.properties}
 * (override the location with {@code expanse.settings-file}). The file sits below command-line options, system
 * properties and environment variables, so scripted launches keep working unchanged.
 */
final class DesktopSettings {
    static final String MODE = "expanse.mode";
    static final String SERVER_URL = "expanse.server-url";
    static final String API_TOKEN = "expanse.api-token";
    static final String DATA_DIR = "expanse.data-dir";
    static final String DEFAULT_URL = "http://127.0.0.1:8765";

    private DesktopSettings() {
    }

    /** Blank token or data directory means "use the default" (a generated local token, ~/.expanse-design). */
    record Values(String mode, String serverUrl, String apiToken, String dataDir) {
        Values {
            mode = blank(mode) ? "local" : mode.strip();
            serverUrl = blank(serverUrl) ? DEFAULT_URL : serverUrl.strip();
            apiToken = apiToken == null ? "" : apiToken.strip();
            dataDir = dataDir == null ? "" : dataDir.strip();
        }

        Map<String, Object> properties() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(MODE, mode);
            map.put(SERVER_URL, serverUrl);
            if (!apiToken.isEmpty()) {
                map.put(API_TOKEN, apiToken);
            }
            if (!dataDir.isEmpty()) {
                map.put(DATA_DIR, dataDir);
            }
            return map;
        }
    }

    static Path file(Environment environment) {
        String configured = environment.getProperty("expanse.settings-file");
        return blank(configured) ? Path.of(System.getProperty("user.home"), ".expanse-design", "settings.properties")
                : Path.of(configured).toAbsolutePath();
    }

    /** The values in effect, whichever source (command line, environment, settings file) supplied them. */
    static Values effective(Environment environment) {
        return new Values(environment.getProperty(MODE), environment.getProperty(SERVER_URL),
                environment.getProperty(API_TOKEN, environment.getProperty("EXPANSE_API_TOKEN", "")),
                environment.getProperty(DATA_DIR, environment.getProperty("EXPANSE_DATA_DIR", "")));
    }

    /** Adds the settings file just below the environment variables, so it only replaces built-in defaults. */
    static void load(ConfigurableEnvironment environment) {
        Path file = file(environment);
        if (!Files.isRegularFile(file)) {
            return;
        }
        var source = new MapPropertySource("expanse-settings", read(file).properties());
        var sources = environment.getPropertySources();
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
    }

    static Values read(Path file) {
        var properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the settings file " + file, e);
        }
        return new Values(properties.getProperty(MODE), properties.getProperty(SERVER_URL),
                properties.getProperty(API_TOKEN), properties.getProperty(DATA_DIR));
    }

    /** Writes atomically; the file may hold a token, so it is owner-only where the file system supports it. */
    static void write(Path file, Values values) throws IOException {
        var properties = new Properties();
        values.properties().forEach((key, value) -> properties.setProperty(key, value.toString()));
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "settings", ".tmp");
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            }
            try (Writer writer = Files.newBufferedWriter(temp)) {
                properties.store(writer, "Expanse Design connection settings (File > Settings)");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Returns a problem description, or null when the desktop can connect with these values. */
    static String validate(Values values) {
        URI uri;
        try {
            uri = URI.create(values.serverUrl());
        } catch (IllegalArgumentException e) {
            return "The server URL is not a valid URL";
        }
        boolean emptyPath = uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !emptyPath) {
            return "Use a server URL of the form scheme://host:port without a path";
        }
        if (!values.apiToken().isEmpty() && (values.apiToken().length() < 32
                || !values.apiToken().matches("[A-Za-z0-9._~-]+"))) {
            return "The API token must be at least 32 URL-safe characters";
        }
        switch (values.mode()) {
            case "local" -> {
                if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1) {
                    return "Local mode runs the server on this computer: use http://127.0.0.1:<port>";
                }
            }
            case "remote" -> {
                boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())
                        || "[::1]".equals(uri.getHost());
                if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
                    return "Use HTTPS for remote servers; HTTP is allowed only on loopback";
                }
                if (values.apiToken().isEmpty()) {
                    return "Remote mode needs the server's API token (owner token to edit, reader token to view)";
                }
            }
            default -> {
                return "Mode must be local or remote";
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
