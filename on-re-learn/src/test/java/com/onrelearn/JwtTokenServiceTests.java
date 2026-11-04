package com.onrelearn;

import com.onrelearn.auth.config.JwtSettings;
import com.onrelearn.auth.service.JwtTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTests {
    private static final String SYNTHETIC_SECRET = "synthetic-test-secret-at-least-32-bytes-long";

    @Test
    void issuesAndVerifiesThreeHourToken() {
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(SYNTHETIC_SECRET, Duration.ofHours(3)));
        String token = tokens.issue("synthetic-member-id");

        assertEquals("synthetic-member-id", tokens.verify(token).orElseThrow());
        assertTrue(tokens.verify(null).isEmpty());
        assertTrue(tokens.verify("malformed-token").isEmpty());
    }

    @Test
    void rejectsExpiredAndAlteredTokens() {
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(SYNTHETIC_SECRET, Duration.ofHours(3)));
        String expired = Jwts.builder().subject("synthetic-member-id")
            .expiration(Date.from(Instant.now().minusSeconds(60)))
            .signWith(Keys.hmacShaKeyFor(SYNTHETIC_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertTrue(tokens.verify(expired).isEmpty());

        String issued = tokens.issue("synthetic-member-id");
        String[] parts = issued.split("\\.");
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        assertTrue(tokens.verify(String.join(".", parts)).isEmpty());
    }

    @Test
    void refusesMissingOrWeakSigningSettings() {
        assertThrows(IllegalStateException.class,
            () -> new JwtTokenService(new JwtSettings("short", Duration.ofHours(3))).issue("member"));
        assertThrows(IllegalStateException.class,
            () -> new JwtTokenService(new JwtSettings(SYNTHETIC_SECRET, Duration.ZERO)).issue("member"));
    }
}
