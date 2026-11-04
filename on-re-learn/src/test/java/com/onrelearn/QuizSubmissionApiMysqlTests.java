package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.repository.MemberRepository;
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
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "onrelearn.auth.jwt.secret=synthetic-test-secret-at-least-32-bytes-long"
})
class QuizSubmissionApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.onrelearn.summary.ai.SummaryProvider provider;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired com.onrelearn.quiz.repository.QuizSetRepository sets;
    @Autowired com.onrelearn.quiz.repository.QuizItemRepository items;
    @Autowired com.onrelearn.quiz.repository.QuizAnswerRepository answers;

    @Autowired com.onrelearn.quiz.service.QuizAttemptStartService starts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.onrelearn.quiz.repository.QuizAttemptRepository attempts;
    @Test void submitsWithHttpValidationAndSafeFailures() throws Exception {
        String email="attempt-api-"+UUID.randomUUID()+"@example.invalid";
        String otherEmail="attempt-api-"+UUID.randomUUID()+"@example.invalid";
        String materialId=null;
        try {
            registration.register(email,"synthetic-password"); registration.register(otherEmail,"synthetic-password");
            String owner=members.findByEmail(email).orElseThrow().getId();
            String other=members.findByEmail(otherEmail).orElseThrow().getId();
            var subject=subjects.saveAndFlush(new Subject(grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0),"Start API"));
            var material=materials.saveAndFlush(new Material(subject,1,"Quiz","users/synthetic/test.pdf"));
            materialId=material.getId();
            var set=sets.saveAndFlush(com.onrelearn.quiz.entity.QuizSet.builder().material(material).sourceHash("hash").purpose(com.onrelearn.quiz.model.QuizPurpose.GENERAL).build());
            var item=items.saveAndFlush(com.onrelearn.quiz.entity.QuizItem.builder().quizSet(set).position(1).type("tf").question("Question").answerKey("{\"correct\":true}").explanation("Hidden explanation").points(100).build());
            String jwt=tokens.issue(owner);
            var csrfResponse=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.discarding());
            String csrf=csrfResponse.headers().allValues("set-cookie").stream().filter(c->c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";",2)[0];

            String attempt=starts.start(owner,set.getId()).attemptId();

            var json=JsonMapper.builder().build();
            String body=json.writeValueAsString(java.util.Map.of("attemptId",attempt));
            assertEquals(401,post(null,csrf,body).statusCode());
            assertEquals(403,post(jwt,null,body).statusCode());
            assertEquals(403,post(tokens.issue(other),csrf,body).statusCode());
            for(String invalid:new String[]{"null","[]","{","{}","{\"attemptId\":42}","{\"attemptId\":null}","{\"attemptId\":\" \"}"})
                assertEquals(400,post(jwt,csrf,invalid).statusCode());
            assertEquals(403,post(jwt,csrf,"{\"attemptId\":\"missing\"}").statusCode());
            assertEquals(0,answers.findByAttempt_Id(attempt).size());
            assertNull(attempts.findById(attempt).orElseThrow().getSubmittedAt());
            // ! A flush failure must not leave newly graded answers or a completed attempt behind.
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail"))
                .when(attempts).saveAndFlush(org.mockito.ArgumentMatchers.any(com.onrelearn.quiz.entity.QuizAttempt.class));
            var failed=post(jwt,csrf,body);
            assertEquals(500,failed.statusCode()); assertFalse(failed.body().contains("synthetic-private-detail"));
            org.mockito.Mockito.reset(attempts);
            assertEquals(0,answers.findByAttempt_Id(attempt).size());
            assertEquals(com.onrelearn.quiz.model.QuizAttemptStatus.IN_PROGRESS,attempts.findById(attempt).orElseThrow().getStatus());
            var response=post(jwt,csrf,body);
            assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var result=json.readTree(response.body());
            assertEquals(4,result.size()); assertTrue(result.get("ok").booleanValue());
            assertEquals(attempt,result.get("attemptId").stringValue());
            assertEquals(0,result.get("score").intValue()); assertEquals(100,result.get("maxScore").intValue());
            assertFalse(response.body().contains("answerKey"));
            var completed=attempts.findById(attempt).orElseThrow();
            assertEquals(com.onrelearn.quiz.model.QuizAttemptStatus.GRADED,completed.getStatus());
            assertNotNull(completed.getGradedAt());
            assertEquals(409,post(jwt,csrf,body).statusCode());
            assertEquals(completed.getGradedAt(),attempts.findById(attempt).orElseThrow().getGradedAt());
            assertEquals(1,answers.findByAttempt_Id(attempt).size());
            var submitted=attempts.saveAndFlush(new com.onrelearn.quiz.entity.QuizAttempt(owner,set));
            jdbc.update("UPDATE quiz_attempt SET status='SUBMITTED' WHERE id=?",submitted.getId());
            assertEquals(409,post(jwt,csrf,json.writeValueAsString(java.util.Map.of("attemptId",submitted.getId()))).statusCode());
            assertEquals(403,post(tokens.issue(other),csrf,body).statusCode());
            org.mockito.Mockito.verifyNoInteractions(provider);
        } finally {
            org.mockito.Mockito.reset(attempts);
            if(materialId!=null) jdbc.update("DELETE FROM material WHERE id=?",materialId);
            for(String address:new String[]{email,otherEmail}) {
                assertTrue(address.matches("attempt-api-[0-9a-f-]{36}@example\\.invalid"));
                jdbc.update("DELETE FROM app_user WHERE email=?",address);
                assertFalse(members.existsByEmail(address));
            }
        }
    }
    private HttpResponse<String> post(String jwt, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/attempt/submit"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

}
