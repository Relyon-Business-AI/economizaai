package com.relyon.economizaai.security;

import com.relyon.economizaai.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-key-must-be-at-least-256-bits-long-for-hs256-signing-algorithm");
        ReflectionTestUtils.setField(jwtService, "expiration", 3600000L);
    }

    @Test
    void generateToken_shouldReturnValidToken() {
        var user = User.builder().email("test@test.com").password("encoded").build();

        var token = jwtService.generateToken(user);

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void extractUsername_shouldReturnEmail() {
        var user = User.builder().email("test@test.com").password("encoded").build();
        var token = jwtService.generateToken(user);

        var username = jwtService.extractUsername(token);

        assertEquals("test@test.com", username);
    }

    @Test
    void isTokenValid_shouldReturnTrueForValidToken() {
        var user = User.builder().email("test@test.com").password("encoded").build();
        var token = jwtService.generateToken(user);

        assertTrue(jwtService.isTokenValid(token, user));
    }

    @Test
    void isTokenValid_shouldReturnFalseForWrongUser() {
        var user = User.builder().email("test@test.com").password("encoded").build();
        var otherUser = User.builder().email("other@test.com").password("encoded").build();
        var token = jwtService.generateToken(user);

        assertFalse(jwtService.isTokenValid(token, otherUser));
    }

    @Test
    void isTokenValid_shouldReturnFalseForExpiredToken() {
        ReflectionTestUtils.setField(jwtService, "expiration", -1000L);
        var user = User.builder().email("test@test.com").password("encoded").build();
        var token = jwtService.generateToken(user);

        assertFalse(jwtService.isTokenValid(token, user));
    }
}
