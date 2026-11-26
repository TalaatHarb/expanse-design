package net.talaatharb.expanse.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.talaatharb.expanse.dto.ApiModels.ApiError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper,
            @Value("${expanse.api-token:${EXPANSE_API_TOKEN:}}") String token,
            @Value("${expanse.read-token:${EXPANSE_READ_TOKEN:}}") String readToken) throws Exception {
        validateToken(token);
        if (!readToken.isEmpty()) {
            validateToken(readToken);
            if (token.equals(readToken)) {
                throw new IllegalArgumentException("Read-only and owner tokens must be different");
            }
        }
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Liveness and API documentation carry no project data.
                        .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
                        // Read-only computations that never persist anything.
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/changes/preview",
                                "/api/v1/projects/*/simulations").authenticated()
                        .requestMatchers("/api/v1/**").hasAuthority("WRITE")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, e) -> error(res, mapper, 401, "UNAUTHORIZED", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, e) -> error(res, mapper, 403, "FORBIDDEN", "This token cannot perform this operation")))
                .addFilterBefore(new TokenFilter(token, readToken), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void validateToken(String token) {
        if (token.length() < 32 || !token.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Set EXPANSE_API_TOKEN / EXPANSE_READ_TOKEN to distinct tokens of at least 32 URL-safe characters");
        }
    }

    private static void error(HttpServletResponse response, ObjectMapper mapper, int status,
                              String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, null));
    }

    @Slf4j
    private static final class TokenFilter extends OncePerRequestFilter {
        private final byte[] owner;
        private final byte[] reader;

        private TokenFilter(String owner, String reader) {
            this.owner = ("Bearer " + owner).getBytes(StandardCharsets.UTF_8);
            this.reader = reader.isEmpty() ? null : ("Bearer " + reader).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null) {
                byte[] candidate = header.getBytes(StandardCharsets.UTF_8);
                boolean write = MessageDigest.isEqual(owner, candidate);
                boolean read = reader != null && MessageDigest.isEqual(reader, candidate);
                if (write || read) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(write ? "owner" : "reader", null,
                                    List.of(new SimpleGrantedAuthority(write ? "WRITE" : "READ"))));
                }
            }
            try {
                chain.doFilter(request, response);
            } finally {
                if (!"GET".equals(request.getMethod())) {
                    var caller = SecurityContextHolder.getContext().getAuthentication();
                    LOG.info("caller={} method={} path={} status={}",
                            caller == null ? "unauthenticated" : caller.getName(),
                            request.getMethod(), request.getRequestURI(), response.getStatus());
                }
            }
        }
    }
}
