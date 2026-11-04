package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.goal.entity.Goal;
import com.onrelearn.goal.repository.GoalRepository;
import com.onrelearn.member.repository.MemberRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "onrelearn.auth.jwt.secret=synthetic-test-secret-at-least-32-bytes-long"
})
class GoalApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean GoalRepository goals;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void ownerCanReadAndSaveGoalsWithoutChangingOtherUsers() throws Exception {
        String email = "goal-api-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "goal-api-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(email, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            String other = members.findByEmail(otherEmail).orElseThrow().getId();
            String jwt = tokens.issue(owner);
            String otherJwt = tokens.issue(other);
            String csrf = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];

            assertEquals(401, get(null).statusCode());
            var defaults = get(jwt);
            assertEquals(200, defaults.statusCode());
            assertTrue(defaults.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals(10, json.readTree(defaults.body()).get("goal").get("dailyQuizGoal").intValue());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM goal WHERE user_id=?", Integer.class, owner));

            String valid = "{\"dailyQuizGoal\":0,\"weeklyQuizGoal\":70,\"dailyMaterialGoal\":4,\"weeklyMaterialGoal\":0}";
            assertEquals(403, put(jwt, null, valid).statusCode());
            assertEquals(401, put(null, csrf, valid).statusCode());
            for (String invalid : new String[] {
                "{}", "null", "[]",
                "{\"dailyQuizGoal\":-1,\"weeklyQuizGoal\":70,\"dailyMaterialGoal\":3,\"weeklyMaterialGoal\":15}",
                "{\"dailyQuizGoal\":1.5,\"weeklyQuizGoal\":70,\"dailyMaterialGoal\":3,\"weeklyMaterialGoal\":15}",
                "{\"dailyQuizGoal\":\"2\",\"weeklyQuizGoal\":70,\"dailyMaterialGoal\":3,\"weeklyMaterialGoal\":15}",
                "{\"dailyQuizGoal\":1,\"weeklyQuizGoal\":70,\"dailyMaterialGoal\":3,\"weeklyMaterialGoal\":15,\"userId\":\"other\"}"
            }) assertEquals(400, put(jwt, csrf, invalid).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM goal WHERE user_id=?", Integer.class, owner));

            var saved = put(jwt, csrf, valid);
            assertEquals(200, saved.statusCode());
            assertTrue(saved.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals(0, json.readTree(saved.body()).get("goal").get("dailyQuizGoal").intValue());
            assertEquals(4, json.readTree(get(jwt).body()).get("goal").get("dailyMaterialGoal").intValue());
            assertEquals(10, json.readTree(get(otherJwt).body()).get("goal").get("dailyQuizGoal").intValue());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM goal WHERE user_id=?", Integer.class, owner));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM goal WHERE user_id=?", Integer.class, other));
            String goalId = goals.findByUser_Id(owner).orElseThrow().getId();
            assertEquals(200, put(jwt, csrf, "{\"dailyQuizGoal\":2,\"weeklyQuizGoal\":3,\"dailyMaterialGoal\":4,\"weeklyMaterialGoal\":5}").statusCode());
            assertEquals(goalId, goals.findByUser_Id(owner).orElseThrow().getId());

            doThrow(new DataAccessResourceFailureException("synthetic-private-detail"))
                .when(goals).saveAndFlush(any(Goal.class));
            var failed = put(jwt, csrf, valid);
            assertEquals(500, failed.statusCode());
            assertFalse(failed.body().contains("synthetic-private-detail"));
            reset(goals);
            assertEquals(2, json.readTree(get(jwt).body()).get("goal").get("dailyQuizGoal").intValue());

            doThrow(new DataAccessResourceFailureException("synthetic-private-detail"))
                .when(goals).findByUser_Id(owner);
            var readFailed = get(jwt);
            assertEquals(500, readFailed.statusCode());
            assertFalse(readFailed.body().contains("synthetic-private-detail"));
            reset(goals);
            assertEquals(2, json.readTree(get(jwt).body()).get("goal").get("dailyQuizGoal").intValue());
        } finally {
            reset(goals);
            for (String address : new String[] {email, otherEmail}) {
                assertTrue(address.matches("goal-api-[0-9a-f-]{36}@example\\.invalid"));
                jdbc.update("DELETE FROM app_user WHERE email=?", address);
                assertFalse(members.existsByEmail(address));
            }
        }
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> get(String jwt) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/goals"));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> put(String jwt, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/goals")).header("Content-Type", "application/json");
        if (jwt != null) request.header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        else if (csrf != null) request.header("Cookie", csrf);
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
