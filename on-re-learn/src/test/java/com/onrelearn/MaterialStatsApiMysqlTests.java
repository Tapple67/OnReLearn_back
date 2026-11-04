package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.quiz.entity.QuizAnswer;
import com.onrelearn.quiz.entity.QuizAttempt;
import com.onrelearn.quiz.entity.QuizItem;
import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.model.QuizPurpose;
import com.onrelearn.quiz.repository.QuizAnswerRepository;
import com.onrelearn.quiz.repository.QuizAttemptRepository;
import com.onrelearn.quiz.repository.QuizItemRepository;
import com.onrelearn.quiz.repository.QuizSetRepository;
import com.onrelearn.stats.MaterialStatsRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "onrelearn.auth.jwt.secret=synthetic-test-secret-at-least-32-bytes-long"
})
class MaterialStatsApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired QuizSetRepository sets;
    @Autowired QuizItemRepository items;
    @Autowired QuizAttemptRepository attempts;
    @Autowired QuizAnswerRepository answers;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean MaterialStatsRepository statistics;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void returnsOwnedStatisticsAndSafeErrors() throws Exception {
        String email = "stats-api-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "stats-api-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            registration.register(email, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            String other = members.findByEmail(otherEmail).orElseThrow().getId();
            var subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0), "Statistics"));
            var material = materials.saveAndFlush(new Material(subject, 3, "Lecture", "users/synthetic/stats-api.pdf"));
            materialId = material.getId();
            String jwt = tokens.issue(owner);

            assertEquals(401, get(null, materialId).statusCode());
            assertEquals(400, get(jwt, null).statusCode());
            assertEquals(400, get(jwt, " ").statusCode());
            assertEquals(403, get(jwt, "missing").statusCode());
            assertEquals(403, get(tokens.issue(other), materialId).statusCode());
            var empty = get(jwt, materialId);
            assertEquals(200, empty.statusCode());
            assertTrue(empty.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var emptyJson = json.readTree(empty.body());
            assertEquals(0, emptyJson.get("summary").get("totalAttempts").intValue());
            assertEquals("NONE", emptyJson.get("understanding").get("level").stringValue());
            assertEquals(0, emptyJson.get("trend").size());
            assertEquals(0, emptyJson.get("weakTopics").size());
            assertEquals(materialId, emptyJson.get("material").get("id").stringValue());

            var set = sets.saveAndFlush(QuizSet.builder().material(material).sourceHash("hash")
                .purpose(QuizPurpose.GENERAL).build());
            var item = items.saveAndFlush(QuizItem.builder().quizSet(set).position(1).type("tf")
                .question("Private question").answerKey("{\"correct\":true}").points(100)
                .topic("Statistics topic").build());
            var attempt = attempts.saveAndFlush(new QuizAttempt(owner, set));
            var answer = new QuizAnswer(attempt, item, "{\"value\":false}");
            answer.recordGrade(false, 0);
            answers.saveAndFlush(answer);
            attempt.completeGrading(0, 100, Instant.now());
            attempts.saveAndFlush(attempt);
            var complete = get(jwt, materialId);
            assertEquals(200, complete.statusCode());
            var body = json.readTree(complete.body());
            assertEquals(8, body.size());
            assertEquals(1, body.get("summary").get("totalAttempts").intValue());
            assertEquals(1, body.get("summary").get("totalWrong").intValue());
            assertEquals(0, body.get("summary").get("latestScore").intValue());
            assertEquals(1, body.get("typeStats").get("tf").get("wrong").intValue());
            assertEquals(1, body.get("purposeStats").get("GENERAL").get("attempts").intValue());
            assertEquals(attempt.getId(), body.get("trend").get(0).get("attemptId").stringValue());
            assertFalse(complete.body().contains("Private question"));
            assertFalse(complete.body().contains("answerKey"));
            assertFalse(complete.body().contains("response"));
            assertEquals(403, get(tokens.issue(other), materialId).statusCode());

            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail"))
                .when(statistics).completedRows(
                    org.mockito.ArgumentMatchers.eq(owner),
                    org.mockito.ArgumentMatchers.eq(materialId),
                    org.mockito.ArgumentMatchers.anyList());
            var failure = get(jwt, materialId);
            assertEquals(500, failure.statusCode());
            assertFalse(failure.body().contains("synthetic-private-detail"));
            assertFalse(failure.body().contains("Private question"));
            org.mockito.Mockito.reset(statistics);
            assertEquals(body, json.readTree(get(jwt, materialId).body()));
            assertFalse(answers.findById(answer.getId()).orElseThrow().getIsCorrect());
        } finally {
            org.mockito.Mockito.reset(statistics);
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id=?", materialId);
            for (String address : new String[] {email, otherEmail}) {
                assertTrue(address.matches("stats-api-[0-9a-f-]{36}@example\\.invalid"));
                jdbc.update("DELETE FROM app_user WHERE email=?", address);
                assertFalse(members.existsByEmail(address));
            }
        }
    }

    private HttpResponse<String> get(String jwt, String materialId) throws Exception {
        String query = materialId == null ? "" :
            "?materialId=" + URLEncoder.encode(materialId, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stats/material" + query));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
