package net.talaatharb.expanse.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import net.talaatharb.expanse.dto.ApiModels.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = WorkbenchClient.class)
public class ConnectorConfiguration {
    @Bean
    @Lazy
    RequestInterceptor credentials(@Value("${expanse.api-token:${EXPANSE_API_TOKEN:}}") String token,
                                   @Value("${expanse.server-url:http://127.0.0.1:8765}") String url) {
        if (token.length() < 32 || !token.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Set EXPANSE_API_TOKEN to at least 32 URL-safe characters");
        }
        URI uri = URI.create(url);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())
                || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (!"https".equals(uri.getScheme())
                && !("http".equals(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException("Use HTTPS for remote servers; HTTP is allowed only on loopback");
        }
        return template -> template.header("Authorization", "Bearer " + token);
    }

    @Bean
    Retryer noMutationRetries() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    Request.Options requestOptions() {
        return new Request.Options(3, TimeUnit.SECONDS, 20, TimeUnit.SECONDS, false);
    }

    @Bean
    ErrorDecoder apiErrors(ObjectMapper mapper) {
        return (method, response) -> {
            if (response.body() != null) {
                try (var input = response.body().asInputStream()) {
                    ApiError error = mapper.readValue(input, ApiError.class);
                    if (error == null || error.code() == null || error.message() == null) {
                        throw new IOException("Missing structured API error fields");
                    }
                    return new ConnectorException(response.status(), error);
                } catch (IOException e) {
                    return new ConnectorException(response.status(), new ApiError("INVALID_SERVER_RESPONSE",
                            "Server returned HTTP " + response.status() + " without a readable API error.", null));
                }
            }
            return new ConnectorException(response.status(), new ApiError("REMOTE_ERROR",
                    "Server returned HTTP " + response.status(), null));
        };
    }
}
