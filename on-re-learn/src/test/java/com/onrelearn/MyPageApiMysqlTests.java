package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.profile.MyPageQuizService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "onrelearn.auth.jwt.secret=synthetic-test-secret-at-least-32-bytes-long"
})
class MyPageApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean MyPageQuizService quizzes;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void authenticatedCompositeReadKeepsEmptyStateAndSafeErrors() throws Exception {
        String email = "mypage-api-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(email, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            assertEquals(401, get(null).statusCode());
            String jwt = tokens.issue(owner);
            var response = get(jwt);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var body = json.readTree(response.body());
            assertEquals(email, body.get("email").asText());
            assertEquals(10, body.get("goal").get("dailyQuizGoal").intValue());
            assertEquals(0, body.get("quizProgress").get("todayCount").intValue());
            assertEquals(0, body.get("quiz").get("totalQuizCount").intValue());
            assertEquals(7, body.get("quiz").get("chartData").size());
            assertEquals(0, body.get("quiz").get("recentItems").size());
            assertFalse(body.has("materialActivity"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM goal WHERE user_id=?", Integer.class, owner));

            doThrow(new DataAccessResourceFailureException("synthetic-private-detail"))
                .when(quizzes).get(eq(owner), any(Instant.class));
            var failed = get(jwt);
            assertEquals(500, failed.statusCode());
            assertFalse(failed.body().contains("synthetic-private-detail"));
            reset(quizzes);
            assertEquals(200, get(jwt).statusCode());
        } finally {
            reset(quizzes);
            assertTrue(email.startsWith("mypage-api-") && email.endsWith("@example.invalid"));
            jdbc.update("DELETE FROM app_user WHERE email=?", email);
            assertFalse(members.existsByEmail(email));
        }
    }

    private HttpResponse<String> get(String jwt) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mypage"));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
