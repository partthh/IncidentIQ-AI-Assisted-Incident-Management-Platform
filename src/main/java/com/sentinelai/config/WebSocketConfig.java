package com.sentinelai.config;

import com.sentinelai.realtime.StompDestinationAuthorizationInterceptor;
import com.sentinelai.realtime.StompJwtAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Wires the STOMP-over-WebSocket transport.
 *
 * <p>A simple in-memory {@link org.springframework.messaging.simp.broker
 * .SimpleMessageBroker} is used deliberately. It is sufficient for a single
 * node, adds no infrastructure to run, and keeps the deployment a modular
 * monolith. The interfaces chosen here (publish to destination, send to user)
 * are the same ones a relay-backed broker would satisfy, so swapping in RabbitMQ
 * later changes configuration rather than code.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompJwtAuthChannelInterceptor authenticationInterceptor;
    private final StompDestinationAuthorizationInterceptor authorizationInterceptor;

    public WebSocketConfig(StompJwtAuthChannelInterceptor authenticationInterceptor,
                           StompDestinationAuthorizationInterceptor authorizationInterceptor) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.authorizationInterceptor = authorizationInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        // Clients publish commands to /app/**; the broker relays to /topic/**.
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // A browser cannot set headers on the upgrade request, so the
                // handshake is permitted here and the token is verified on the
                // STOMP CONNECT frame instead.
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor, authorizationInterceptor);
    }
}
