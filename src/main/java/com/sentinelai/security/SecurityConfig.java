package com.sentinelai.security;

import com.sentinelai.config.SecurityProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // Cost 10 keeps login responsive while remaining non-trivial to brute force.
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                     JwtAuthenticationFilter jwtFilter,
                                     AuthenticationEntryPoint apiAuthenticationEntryPoint,
                                     AccessDeniedHandler apiAccessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.disable()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // The handshake is unauthenticated because the browser cannot set
                        // headers on a WebSocket upgrade; the STOMP CONNECT frame carries
                        // the token and every destination is authorised from there.
                        .requestMatchers("/ws/**").permitAll()
                        // Producers write events. Viewers may read but not write.
                        .requestMatchers(HttpMethod.POST, "/api/v1/events").hasAnyRole("ADMIN", "ENGINEER")
                        .requestMatchers("/api/v1/events/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents/**").authenticated()
                        .requestMatchers("/api/v1/incidents/**").hasAnyRole("ADMIN", "ENGINEER")
                        // Read-only. The raw-response sub-resource narrows to ADMIN
                        // with @PreAuthorize rather than a path rule, so the rule and the
                        // handler that enforces it stay visible in one place.
                        .requestMatchers("/api/v1/analyses/**").authenticated()
                        .requestMatchers("/api/v1/detection-rules/**").authenticated()
                        .requestMatchers("/api/v1/services/**", "/api/v1/metrics/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Unauthenticated API calls return the same JSON envelope as every other
     * failure instead of Spring's default error page.
     */
    @Bean
    @Order(0)
    AuthenticationEntryPoint apiAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"status":401,"code":"UNAUTHENTICATED","message":"Authentication required","path":"%s"}"""
                    .formatted(request.getRequestURI()));
        };
    }

    /**
     * An authenticated caller who is not allowed to do what they asked gets 403, not 401.
     *
     * <p>This exists because the default handler loses the answer. {@code
     * AccessDeniedHandlerImpl} responds with {@code sendError(403)}, which makes the
     * servlet container re-dispatch through {@code /error}; the security chain runs
     * again on that dispatch with no principal, and the {@link AuthenticationEntryPoint}
     * answers instead. The caller sees "401 UNAUTHENTICATED, Authentication required" for
     * a request they were perfectly well authenticated for — a viewer attempting a write
     * is told to log in again, which is both wrong and unactionable.
     *
     * <p>MockMvc does not reproduce the re-dispatch, so a test using it sees the correct
     * 403 and misses this entirely. {@code AuthorizationStatusCodeTest} covers it over real
     * HTTP for that reason.
     *
     * <p>Writing the response directly also avoids the second problem: {@code sendError}
     * produces Boot's default error body, which has no {@code code} field and so does not
     * match the envelope every other failure in this API uses.
     */
    @Bean
    AccessDeniedHandler apiAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"status":403,"code":"FORBIDDEN","message":"Insufficient permissions for this action","path":"%s"}"""
                    .formatted(request.getRequestURI()));
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(properties.allowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
