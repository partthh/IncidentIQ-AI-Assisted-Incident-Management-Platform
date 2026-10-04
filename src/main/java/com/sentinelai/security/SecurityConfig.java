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
                                     AuthenticationEntryPoint apiAuthenticationEntryPoint) throws Exception {
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
                .exceptionHandling(ex -> ex.authenticationEntryPoint(apiAuthenticationEntryPoint))
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
