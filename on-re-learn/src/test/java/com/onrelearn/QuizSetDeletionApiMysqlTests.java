package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.quiz.entity.QuizAnswer;
import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.entity.QuizItem;
import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.model.QuizPurpose;
import com.onrelearn.quiz.repository.QuizAnswerRepository;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.quiz.repository.QuizItemRepository;
import com.onrelearn.quiz.repository.QuizSetRepository;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
class QuizSetDeletionApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @MockitoSpyBean QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void deletesOnlySelectedSetAndRollsBackFailure() throws Exception {
        String email = "delete-set-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "delete-set-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            registration.register(email, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            String other = members.findByEmail(otherEmail).orElseThrow().getId();
            var subject = subjects.saveAndFlush(new Subject(grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0), "Delete Set"));
            var material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/test.pdf"));
            materialId = material.getId();
            var selected = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash")
                .title("Selected").purpose(QuizPurpose.GENERAL).build());
            var retained = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash")
                .title("Retained").purpose(QuizPurpose.WRONG_REVIEW)
                .sourceAttemptId("snapshot-id").build());
            var item = items.saveAndFlush(QuizItem.builder().quizSet(selected).position(1).type("tf")
                .question("Question").answerKey("{\"correct\":false}").points(100).build());
            var inProgress = attempts.saveAndFlush(new QuizAttempt(owner, selected));
            var completed = attempts.saveAndFlush(new QuizAttempt(owner, selected));
            jdbc.update("UPDATE quiz_attempt SET status='GRADED',score=0,max_score=100 WHERE id=?", completed.getId());
            var answer = answers.saveAndFlush(new QuizAnswer(completed, item, "{\"value\":true}"));
            String jwt = tokens.issue(owner);
            String csrf = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
            String request = "{\"quizSetId\":\"" + selected.getId() + "\"}";
            assertEquals(401, delete(null, csrf, request).statusCode());
            assertEquals(403, delete(jwt, null, request).statusCode());
            assertEquals(403, delete(tokens.issue(other), csrf, request).statusCode());
            for (String invalid : new String[]{"null", "[]", "{}", "{\"quizSetId\":42}", "{\"quizSetId\":\" \"}"})
                assertEquals(400, delete(jwt, csrf, invalid).statusCode());
            assertEquals(2, count("quiz_attempt", "quiz_set_id", selected.getId()));
            assertEquals(1, totalAttempts(jwt, materialId));

            doAnswer(call -> { throw new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail"); })
                .when(sets).flush();
            try {
                var failed = delete(jwt, csrf, request);
                assertEquals(500, failed.statusCode());
                assertFalse(failed.body().contains("synthetic-private-detail"));
            } finally { reset(sets); }
            assertEquals(1, count("quiz_set", "id", selected.getId()));
            assertEquals(2, count("quiz_attempt", "quiz_set_id", selected.getId()));
            assertEquals(1, count("quiz_answer", "id", answer.getId()));

            var response = delete(jwt, csrf, request);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals("{\"ok\":true}", response.body());
            for (String table : new String[]{"quiz_set", "quiz_item", "quiz_attempt"})
                assertEquals(0, count(table, table.equals("quiz_set") ? "id" : "quiz_set_id", selected.getId()));
            assertEquals(0, count("quiz_answer", "id", answer.getId()));
            assertEquals(1, count("quiz_set", "id", retained.getId()));
            assertEquals(1, count("material", "id", material.getId()));
            assertEquals(0, totalAttempts(jwt, materialId));
            assertEquals(403, delete(jwt, csrf, request).statusCode());
            assertNotNull(inProgress.getId());
        } finally {
            reset(sets);
            // ! Cleanup is limited to the two synthetic accounts and their exact material.
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id=?", materialId);
            jdbc.update("DELETE FROM app_user WHERE email=?", email);
            jdbc.update("DELETE FROM app_user WHERE email=?", otherEmail);
            assertFalse(members.existsByEmail(email));
            assertFalse(members.existsByEmail(otherEmail));
        }
    }

    private int count(String table, String column, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, id);
    }
    private int totalAttempts(String jwt, String materialId) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri("/api/stats/material?materialId=" + materialId))
            .header("Cookie", "token=" + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).get("summary").get("totalAttempts").intValue();
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> delete(String jwt, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/quiz/quiz-sets"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
