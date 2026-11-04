package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import java.net.URI;
import java.net.http.*;
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
class GoalProgressApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean QuizAttemptRepository attempts;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void protectedProgressReadPreservesEmptyAccountAndHidesDatabaseFailure() throws Exception {
        String email = "goal-progress-api-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(email, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            assertEquals(401, get(null).statusCode());
            String jwt = tokens.issue(owner);
            var empty = get(jwt);
            assertEquals(200, empty.statusCode());
            assertTrue(empty.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var data = json.readTree(empty.body());
            assertEquals(0, data.get("todayCount").intValue());
            assertEquals(0, data.get("weeklyCount").intValue());
            assertEquals(0, data.get("todayPercent").intValue());
            assertEquals(0, data.get("weeklyPercent").intValue());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM goal WHERE user_id=?", Integer.class, owner));

            doThrow(new DataAccessResourceFailureException("synthetic-private-detail"))
                .when(attempts).completionTimesForUser(eq(owner), anyList());
            var failed = get(jwt);
            assertEquals(500, failed.statusCode());
            assertFalse(failed.body().contains("synthetic-private-detail"));
            reset(attempts);
            assertEquals(200, get(jwt).statusCode());
        } finally {
            reset(attempts);
            assertTrue(email.startsWith("goal-progress-api-") && email.endsWith("@example.invalid"));
            jdbc.update("DELETE FROM app_user WHERE email=?", email);
            assertFalse(members.existsByEmail(email));
        }
    }

    private HttpResponse<String> get(String jwt) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/goals/quiz-progress"));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
