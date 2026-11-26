package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ConnectionSettingsTest {
    private static final String TOKEN = "remote-owner-0123456789012345678901234567";

    @Test
    void settingsRoundTripAndOnlyReplaceDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested").resolve("settings.properties");
        DesktopSettings.write(file, new DesktopSettings.Values("remote", " https://eda.example.com ", TOKEN, ""));
        assertThat(DesktopSettings.read(file))
                .isEqualTo(new DesktopSettings.Values("remote", "https://eda.example.com", TOKEN, ""));
        assertThat(Files.readString(file)).doesNotContain("expanse.data-dir");

        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("expanse.settings-file", file.toString(), "expanse.server-url", "https://override.example.com")));
        DesktopSettings.load(environment);
        var effective = DesktopSettings.effective(environment);
        assertThat(effective.mode()).isEqualTo("remote");
        assertThat(effective.apiToken()).isEqualTo(TOKEN);
        assertThat(effective.serverUrl()).isEqualTo("https://override.example.com");
    }

    @Test
    void validationMirrorsWhatTheConnectorAndLocalServerAccept() {
        assertThat(DesktopSettings.validate(new DesktopSettings.Values(null, null, null, null))).isNull();
        assertThat(DesktopSettings.validate(new DesktopSettings.Values("local", "http://localhost:8765", "", "")))
                .contains("127.0.0.1");
        assertThat(DesktopSettings.validate(new DesktopSettings.Values("remote", "https://eda.example.com", "", "")))
                .contains("token");
        assertThat(DesktopSettings.validate(new DesktopSettings.Values("remote", "http://eda.example.com", TOKEN, "")))
                .contains("HTTPS");
        assertThat(DesktopSettings.validate(new DesktopSettings.Values("remote", "https://eda.example.com/api", TOKEN,
                ""))).contains("without a path");
        assertThat(DesktopSettings.validate(new DesktopSettings.Values("remote", "https://eda.example.com", "short",
                ""))).contains("32");
        assertThat(DesktopSettings.validate(new DesktopSettings.Values("remote", "https://eda.example.com:8443", TOKEN,
                ""))).isNull();
    }

    @Test
    void healthDistinguishesUpDegradedAndUnreachable() throws Exception {
        String[] reply = {"200", "{\"status\":\"UP\",\"groups\":[\"liveness\"]}"};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            byte[] body = reply[1].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(reply[0]), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (var monitor = new HealthMonitor()) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertThat(monitor.check(url)).isEqualTo(new HealthMonitor.Health(HealthMonitor.Status.UP, "UP"));
            reply[0] = "503";
            reply[1] = "{\"status\":\"DOWN\"}";
            assertThat(monitor.check(url)).isEqualTo(new HealthMonitor.Health(HealthMonitor.Status.DEGRADED, "DOWN"));
            reply[0] = "404";
            reply[1] = "<html>not found</html>";
            assertThat(monitor.check(url)).isEqualTo(new HealthMonitor.Health(HealthMonitor.Status.DEGRADED, "HTTP 404"));
            assertThat(monitor.check("http://127.0.0.1:" + closedPort).status())
                    .isEqualTo(HealthMonitor.Status.UNREACHABLE);
        } finally {
            server.stop(0);
        }
    }
}
