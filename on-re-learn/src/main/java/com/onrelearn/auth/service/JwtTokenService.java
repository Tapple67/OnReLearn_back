package com.onrelearn.auth.service;

import com.onrelearn.auth.config.JwtSettings;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final JwtSettings settings;

    public JwtTokenService(JwtSettings settings) {
        this.settings = settings;
    }

    public String issue(String memberId) {
        // * 회원 ID만 서명해 쿠키에 담는다. 수명과 비밀키는 설정에서 읽는다.
        if (memberId == null || memberId.isBlank()) throw new IllegalArgumentException("Member ID is required");
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(memberId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(validTtl())))
            .signWith(signingKey())
            .compact();
    }

    public Optional<String> verify(String token) {
        // * 서명·만료 검증에 실패한 쿠키는 인증되지 않은 요청으로 처리한다.
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            String subject = Jwts.parser().verifyWith(signingKey()).build()
                .parseSignedClaims(token).getPayload().getSubject();
            return subject == null || subject.isBlank() ? Optional.empty() : Optional.of(subject);
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private java.time.Duration validTtl() {
        if (settings.ttl() == null || settings.ttl().isZero() || settings.ttl().isNegative()) {
            throw new AuthenticationConfigurationException("JWT lifetime must be positive");
        }
        return settings.ttl();
    }

    private SecretKey signingKey() {
        String secret = settings.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new AuthenticationConfigurationException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
