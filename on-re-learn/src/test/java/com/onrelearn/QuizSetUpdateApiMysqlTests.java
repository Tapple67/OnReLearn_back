package com.onrelearn;

import com.onrelearn.auth.service.*;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.model.QuizPurpose;
import com.onrelearn.quiz.repository.QuizSetRepository;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "onrelearn.auth.jwt.secret=synthetic-test-secret-at-least-32-bytes-long"
})
class QuizSetUpdateApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @MockitoSpyBean QuizSetRepository sets;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void renamesWithStrictInputOwnershipCsrfAndRollback() throws Exception {
        String email = "rename-api-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "rename-api-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            registration.register(email, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            String other = members.findByEmail(otherEmail).orElseThrow().getId();
            var subject = subjects.saveAndFlush(new Subject(grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0), "Rename API"));
            var material = materials.saveAndFlush(new Material(subject, 1, "Quiz", "users/synthetic/test.pdf"));
            materialId = material.getId();
            var set = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash")
                .title("Original").purpose(QuizPurpose.GENERAL).build());
            var before = jdbc.queryForMap("SELECT * FROM quiz_set WHERE id=?", set.getId());
            String jwt = tokens.issue(owner);
            var csrfResponse = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.discarding());
            String csrf = csrfResponse.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
            String body = body(set.getId(), "  New title  ");
            assertEquals(401, patch(null, csrf, body).statusCode());
            assertEquals(403, patch(jwt, null, body).statusCode());
            assertEquals(403, patch(tokens.issue(other), csrf, body).statusCode());
            assertEquals(403, patch(jwt, csrf, body("missing", "Title")).statusCode());
            for (String invalid : new String[]{"null", "[]", "{", "{}", "{\"quizSetId\":42,\"title\":\"Title\"}",
                    "{\"quizSetId\":\"x\",\"title\":false}", "{\"quizSetId\":\"x\",\"title\":null}",
                    "{\"quizSetId\":\"x\",\"title\":[]}"})
                assertEquals(400, patch(jwt, csrf, invalid).statusCode());
            assertEquals(400, patch(jwt, csrf, body(" ", "Title")).statusCode());
            for (String invalid : new String[]{"", "   ", "a".repeat(101)})
                assertEquals(400, patch(jwt, csrf, body(set.getId(), invalid)).statusCode());
            assertEquals(before, jdbc.queryForMap("SELECT * FROM quiz_set WHERE id=?", set.getId()));
            var response = patch(jwt, csrf, body);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var result = json.readTree(response.body());
            assertEquals(2, result.size()); assertTrue(result.get("ok").booleanValue());
            assertEquals(3, result.get("quizSet").size());
            assertEquals(set.getId(), result.get("quizSet").get("id").stringValue());
            assertEquals("New title", result.get("quizSet").get("title").stringValue());
            assertNotNull(result.get("quizSet").get("createdAt"));
            var expected = new HashMap<>(before); expected.put("title", "New title");
            assertEquals(expected, jdbc.queryForMap("SELECT * FROM quiz_set WHERE id=?", set.getId()));
            assertEquals(200, patch(jwt, csrf, body(set.getId(), "a".repeat(100))).statusCode());
            var saved = jdbc.queryForMap("SELECT * FROM quiz_set WHERE id=?", set.getId());
            doAnswer(call -> {
                entityManager.flush();
                throw new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail");
            }).when(sets).saveAndFlush(any(QuizSet.class));
            try {
                var failed = patch(jwt, csrf, body(set.getId(), "Rollback"));
                assertEquals(500, failed.statusCode());
                assertFalse(failed.body().contains("synthetic-private-detail"));
            } finally { reset(sets); }
            assertEquals(saved, jdbc.queryForMap("SELECT * FROM quiz_set WHERE id=?", set.getId()));
            assertEquals("a".repeat(100), sets.listForMaterial(materialId).get(0).getTitle());
        } finally {
            reset(sets);
            // ! Delete only the material/accounts created by this synthetic test.
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id=?", materialId);
            for (String address : new String[]{email, otherEmail}) {
                jdbc.update("DELETE FROM app_user WHERE email=?", address);
                assertFalse(members.existsByEmail(address));
            }
        }
    }
    private String body(String id, String title) {
        return json.writeValueAsString(Map.of("quizSetId", id, "title", title));
    }
    private HttpResponse<String> patch(String jwt, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/quiz/quiz-sets"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
