package org.example.userservice.controller;

import org.example.userservice.dto.UserDto;
import org.example.userservice.entity.User;
import org.example.userservice.service.JWTService;
import org.example.userservice.service.UserService;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class AuthController {

    private final JWTService jwtService;
    private final UserService userService;

    public AuthController(JWTService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    /**
     * 사용자 로그인 API
     *
     * <p>이메일과 비밀번호를 검증하여 인증에 성공하면 JWT 토큰을 발급한다.</p>
     *
     * @param request 로그인 요청 정보
     *                - email: 사용자 이메일
     *                - password: 사용자 비밀번호
     * @return 로그인 성공 시 JWT 토큰과 사용자 정보를 포함한 응답
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody UserDto.LoginRequest request) {
        User user = userService.authenticate(request.getEmail(), request.getPassword());
        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(UserDto.LoginResponse.builder()
                .token(token)
                .user(UserDto.Response.from(user))
                .build());
    }

    /**
     * JWT 토큰의 유효성을 검증하고 사용자 정보를 반환한다.
     */
    @PostMapping("/validate-token")
    public ResponseEntity<?> validateToken(
            @RequestBody UserDto.TokenRequest request) {
        Claims claims = jwtService.validateToken(request.getToken());
        return ResponseEntity.ok(UserDto.TokenResponse.builder()
                .id(claims.get("id", Long.class))
                .email(claims.getSubject())
                .valid(true)
                .role(claims.get("role", String.class))
                .build());
    }

    /**
     * 기존 JWT를 읽어서 만료 시간을 연장한 새 JWT를 발급하는 기능
     */
    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, String>> refreshToken(
            @RequestBody UserDto.TokenRequest tokenRequest) {
        String newToken = jwtService.refreshToken(tokenRequest.getToken());
        return ResponseEntity.ok(Collections.singletonMap("token", newToken));
    }
}
