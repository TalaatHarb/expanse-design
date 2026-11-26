package net.talaatharb.expanse.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Polls the server's anonymous Spring Boot Actuator health endpoint ({@code /actuator/health}) on a daemon thread.
 * It needs no token, so it also distinguishes "server down" from "token rejected".
 */
final class HealthMonitor implements AutoCloseable {
    static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "server-health");
        thread.setDaemon(true);
        return thread;
    });

    enum Status {
        /** Health reported UP. */
        UP,
        /** Reachable, but reporting DOWN or OUT_OF_SERVICE, or answering without a health endpoint. */
        DEGRADED,
        /** No answer: not running, wrong address, or blocked by the network. */
        UNREACHABLE
    }

    record Health(Status status, String detail) {
    }

    /** Calls {@code listener} from the polling thread after every check, immediately and then every interval. */
    void start(Supplier<String> serverUrl, Consumer<Health> listener) {
        timer.scheduleWithFixedDelay(() -> listener.accept(check(serverUrl.get())), 0, INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** Requests an immediate check, for example after reconnecting. */
    void refresh(Supplier<String> serverUrl, Consumer<Health> listener) {
        timer.execute(() -> listener.accept(check(serverUrl.get())));
    }

    Health check(String serverUrl) {
        try {
            URI uri = URI.create(serverUrl.replaceAll("/+$", "") + "/actuator/health");
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String status = "";
            try {
                status = JSON.readTree(response.body()).path("status").asText("");
            } catch (Exception notJson) {
                // a proxy page or an older server without Actuator
            }
            if (response.statusCode() == 200 && "UP".equals(status)) {
                return new Health(Status.UP, "UP");
            }
            return new Health(Status.DEGRADED, status.isEmpty() ? "HTTP " + response.statusCode() : status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Health(Status.UNREACHABLE, "interrupted");
        } catch (Exception e) {
            return new Health(Status.UNREACHABLE, e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        http.close();
    }
}
