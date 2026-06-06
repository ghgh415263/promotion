package com.example.apigateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class JWTService {

    @Value("${jwt.secret}")
    private String secretKey;

    public Claims validateToken(String token) {
        try {
            return parseJwtClaims(token);
        } catch (Exception e) {
            log.error("Token validation error: ", e);
            throw new IllegalArgumentException("Invalid token");
        }
    }

    public Claims parseJwtClaims(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
