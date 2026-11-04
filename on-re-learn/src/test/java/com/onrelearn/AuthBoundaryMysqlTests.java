package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "onrelearn.auth.jwt.secret=synthetic-test-secret-at-least-32-bytes-long"
})
class AuthBoundaryMysqlTests {
    private static final String SECRET = "synthetic-test-secret-at-least-32-bytes-long";
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;
    @Value("${local.server.port}") int port;

    @Test
    void httpRejectsMissingExpiredAndTamperedCookies() throws Exception {
        String email = "boundary-" + UUID.randomUUID() + "@example.invalid";
        Member member = members.saveAndFlush(new Member(email, "synthetic-hash-not-for-login"));
        try {
            String valid = tokens.issue(member.getId());
            String expired = Jwts.builder().subject(member.getId())
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
            String[] parts = valid.split("\\.");
            char first = parts[2].charAt(0);
            parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
            String tampered = String.join(".", parts);

            assertEquals(200, getMe(valid));
            assertEquals(401, getMe(null));
            assertEquals(401, getMe(expired));
            assertEquals(401, getMe(tampered));
        } finally {
            assertTrue(email.matches("boundary-[0-9a-f-]{36}@example\\.invalid"));
            assertEquals(1, jdbc.update("DELETE FROM app_user WHERE id = ? AND email = ?", member.getId(), email));
            assertFalse(members.existsById(member.getId()));
        }
    }

    @Test
    void httpRejectsMissingAndWrongCsrfEvenWhenLogoutIsPublic() throws Exception {
        var cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertEquals(403, logout(client, null));

        var csrf = HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build();
        assertEquals(200, client.send(csrf, HttpResponse.BodyHandlers.discarding()).statusCode());
        String token = cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
            .findFirst().orElseThrow().getValue();
        assertEquals(403, logout(client, "wrong-token"));
        assertEquals(200, logout(client, token));
    }

    private int getMe(String token) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/auth/me"));
        if (token != null) request.header("Cookie", "token=" + token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.discarding())
            .statusCode();
    }

    private int logout(HttpClient client, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/auth/logout"))
            .header("Content-Type", "application/json");
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
