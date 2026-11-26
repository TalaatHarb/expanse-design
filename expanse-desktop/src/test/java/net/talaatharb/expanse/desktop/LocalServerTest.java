package net.talaatharb.expanse.desktop;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class LocalServerTest {
    @TempDir
    Path data;

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("expanse.data-dir", data.toString());
    }

    @Test
    void localModeCreatesOnePrivateTokenAndReusesItAcrossLaunches() throws Exception {
        var first = environment();
        LocalServer.provisionToken(first);
        String token = first.getProperty("expanse.api-token");
        assertThat(token).matches("[0-9a-f]{64}");
        assertThat(Files.readString(data.resolve(LocalServer.TOKEN_FILE))).isEqualTo(token);

        var second = environment();
        LocalServer.provisionToken(second);
        assertThat(second.getProperty("expanse.api-token")).isEqualTo(token);
    }

    @Test
    void configuredTokensAndRemoteModeAreLeftAlone() {
        var configured = environment().withProperty("EXPANSE_API_TOKEN", "x".repeat(40));
        LocalServer.provisionToken(configured);
        assertThat(configured.getProperty("expanse.api-token")).isNull();

        var remote = environment().withProperty("expanse.mode", "remote");
        LocalServer.provisionToken(remote);
        assertThat(remote.getProperty("expanse.api-token")).isNull();
        assertThat(data.resolve(LocalServer.TOKEN_FILE)).doesNotExist();
    }

    @Test
    void embeddedServerIsExtractedOncePerContentAndOldCopiesAreRemoved() throws Exception {
        Path runtime = data.resolve("runtime");
        Path v1 = LocalServer.extract(new ByteArrayInputStream("server v1".getBytes()), runtime);
        assertThat(LocalServer.extract(new ByteArrayInputStream("server v1".getBytes()), runtime)).isEqualTo(v1);
        Path v2 = LocalServer.extract(new ByteArrayInputStream("server v2".getBytes()), runtime);
        assertThat(v2).isNotEqualTo(v1).hasContent("server v2");
        try (var files = Files.list(runtime)) {
            assertThat(files).containsExactly(v2);
        }
        assertThat(LocalServer.serverJar(environment().withProperty("expanse.local-server-jar", "custom.jar"), data))
                .isEqualTo(Path.of("custom.jar").toAbsolutePath());
    }
}
