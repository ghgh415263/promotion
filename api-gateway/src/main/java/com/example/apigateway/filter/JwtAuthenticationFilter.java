package com.example.apigateway.filter;

import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
public class JwtAuthenticationFilter extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    private final JWTService jwtService;

    public JwtAuthenticationFilter(JWTService jwtService) {
        super(Config.class);
        this.jwtService = jwtService;
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {

            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return chain.filter(exchange);
            }

            try {
                String token = authHeader.substring(7);

                Claims claims = jwtService.validateToken(token);

                Long userId = claims.get("id", Integer.class).longValue();
                String role = claims.get("role", String.class);

                ServerHttpRequest request = exchange.getRequest()
                        .mutate()
                        .header("X-USER-ID", String.valueOf(userId))
                        .header("X-USER-ROLE", role)
                        .build();

                return chain.filter(
                        exchange.mutate()
                                .request(request)
                                .build()
                );

            } catch (Exception e) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        };
    }

    public static class Config {
    }
}