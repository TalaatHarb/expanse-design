package net.talaatharb.expanse.desktop;

import feign.RetryableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.talaatharb.expanse.api.WorkbenchApi;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Connects to the local server, starting a child process when none is listening. The server jar is, in order:
 * {@code expanse.local-server-jar}, the copy embedded in a release desktop jar (extracted into the data directory),
 * or the development build under {@code expanse-server/target}.
 */
final class LocalServer implements AutoCloseable {
    static final String EMBEDDED_SERVER = "/local-server/expanse-server.jar";
    static final String TOKEN_FILE = "local-api-token";
    private Process process;

    static Path dataDirectory(Environment environment) {
        return Path.of(environment.getProperty("expanse.data-dir", environment.getProperty("EXPANSE_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".expanse-design").toString()))).toAbsolutePath();
    }

    /**
     * In local mode without a configured token, reuses or creates a random per-user token in the data directory so
     * the desktop works without setup. Remote mode always requires an explicit token.
     */
    static void provisionToken(ConfigurableEnvironment environment) {
        if (!"local".equals(environment.getProperty("expanse.mode", "local"))
                || !environment.getProperty("expanse.api-token", environment.getProperty("EXPANSE_API_TOKEN", ""))
                        .isBlank()) {
            return;
        }
        Path file = dataDirectory(environment).resolve(TOKEN_FILE);
        try {
            String token = Files.isRegularFile(file) ? Files.readString(file).strip() : "";
            if (!token.matches("[A-Za-z0-9]{32,}")) {
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                token = HexFormat.of().formatHex(random);
                Files.createDirectories(file.getParent());
                Path temp = Files.createTempFile(file.getParent(), TOKEN_FILE, ".tmp");
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                }
                Files.writeString(temp, token);
                move(temp, file);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("expanse-local-token",
                    Map.of("expanse.api-token", token)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create the local API token " + file, e);
        }
    }

    static Path serverJar(Environment environment, Path dataDirectory) throws IOException {
        String configured = environment.getProperty("expanse.local-server-jar");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        try (InputStream embedded = LocalServer.class.getResourceAsStream(EMBEDDED_SERVER)) {
            if (embedded != null) {
                return extract(embedded, dataDirectory.resolve("runtime"));
            }
        }
        return Path.of("expanse-server", "target", "expanse-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    }

    /** Copies the jar to {@code expanse-server-<sha256 prefix>.jar}, reusing an identical earlier copy. */
    static Path extract(InputStream jar, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "expanse-server", ".tmp");
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (var digesting = new DigestInputStream(jar, sha256)) {
                Files.copy(digesting, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            String name = "expanse-server-" + HexFormat.of().formatHex(sha256.digest()).substring(0, 16) + ".jar";
            Path target = directory.resolve(name);
            if (!Files.isRegularFile(target) || Files.size(target) != Files.size(temp)) {
                move(temp, target);
            }
            try (var old = Files.newDirectoryStream(directory, "expanse-server-*.jar")) {
                for (Path stale : old) {
                    if (!stale.equals(target)) {
                        try {
                            Files.deleteIfExists(stale);
                        } catch (IOException inUse) {
                            // Another running instance may still hold an older server jar open.
                        }
                    }
                }
            }
            return target;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void connect(Environment environment, WorkbenchApi api) throws IOException, InterruptedException {
        String mode = environment.getProperty("expanse.mode", "local");
        if ("remote".equals(mode)) {
            api.capabilities();
            return;
        }
        if (!"local".equals(mode)) {
            throw new IllegalArgumentException("expanse.mode must be local or remote");
        }
        URI uri = URI.create(environment.getProperty("expanse.server-url", "http://127.0.0.1:8765"));
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Local mode requires http://127.0.0.1:<port>; use remote mode for other URLs");
        }
        try {
            api.capabilities();
            return;
        } catch (RetryableException unavailable) {
            // Only a transport failure may start a local child; authentication failures must remain visible.
        }
        Path dataDirectory = dataDirectory(environment);
        Path jar = serverJar(environment, dataDirectory);
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Build the server with 'mvn package' first, or set expanse.local-server-jar: " + jar);
        }
        Path log = dataDirectory.resolve("server.log");
        Files.createDirectories(log.getParent());
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-jar", jar.toString(), "--server.address=127.0.0.1", "--server.port=" + uri.getPort())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("EXPANSE_API_TOKEN",
                environment.getProperty("expanse.api-token", environment.getProperty("EXPANSE_API_TOKEN", "")));
        builder.environment().put("EXPANSE_DATA_DIR", dataDirectory.toString());
        process = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        RetryableException lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("Local server exited with code " + process.exitValue() + "; see " + log);
            }
            try {
                api.capabilities();
                return;
            } catch (RetryableException starting) {
                lastFailure = starting;
                Thread.sleep(250);
            }
        }
        throw new IOException("Local server did not become ready; see " + log, lastFailure);
    }

    @Override
    public void close() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
