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
class QuizAttemptStartApiMysqlTests {
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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.onrelearn.quiz.repository.QuizAnswerRepository answers;

    @Test void startsRestoresAndReturnsSubmittedConflictWithCsrf() throws Exception {
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
            String body="{\"quizSetId\":\""+set.getId()+"\"}";
            assertEquals(401,post(null,csrf,body).statusCode());
            assertEquals(403,post(jwt,null,body).statusCode());
            assertEquals(403,post(tokens.issue(other),csrf,body).statusCode());
            for(String invalid:new String[]{"null","{}","[]","{","{\"quizSetId\":42}","{\"quizSetId\":\" \"}"})
                assertEquals(400,post(jwt,csrf,invalid).statusCode());
            assertEquals(403,post(jwt,csrf,"{\"quizSetId\":\"missing\"}").statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_set_id=?",Integer.class,set.getId()));
            var json=JsonMapper.builder().build();
            var response=post(jwt,csrf,body);
            assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var data=json.readTree(response.body());
            assertEquals(3,data.size());
            String attempt=data.get("attemptId").stringValue();
            assertTrue(data.get("existingAnswers").isEmpty());
            assertEquals(7,data.get("quizSet").get("items").get(0).size());
            assertFalse(response.body().contains("answerKey")); assertFalse(response.body().contains("Hidden"));
            jdbc.update("INSERT INTO quiz_answer(id,attempt_id,quiz_item_id,response,created_at,updated_at) VALUES(?,?,?, ?,NOW(6),NOW(6))",UUID.randomUUID().toString(),attempt,item.getId(),"{\"value\":false}");
            var restored=json.readTree(post(jwt,csrf,body).body());
            assertEquals(attempt,restored.get("attemptId").stringValue());
            assertFalse(restored.get("existingAnswers").get(0).get("response").get("value").booleanValue());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_set_id=?",Integer.class,set.getId()));
            // ! A restore read failure must remain an error, never an empty successful restoration.
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail"))
                .when(answers).findByAttempt_Id(attempt);
            var failed=post(jwt,csrf,body);
            assertEquals(500,failed.statusCode()); assertFalse(failed.body().contains("synthetic-private-detail"));
            org.mockito.Mockito.reset(answers);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer WHERE attempt_id=?",Integer.class,attempt));
            for(String status:new String[]{"SUBMITTED","GRADED"}) {
                jdbc.update("UPDATE quiz_attempt SET status=? WHERE id=?",status,attempt);
                var submitted=post(jwt,csrf,body);
                assertEquals(409,submitted.statusCode());
                assertTrue(submitted.headers().firstValue("cache-control").orElse("").contains("no-store"));
                var error=json.readTree(submitted.body());
                assertEquals("ALREADY_SUBMITTED",error.get("code").stringValue());
                assertEquals(attempt,error.get("attemptId").stringValue()); assertEquals(3,error.size());
                assertEquals(403,post(tokens.issue(other),csrf,body).statusCode());
            }
            org.mockito.Mockito.verifyNoInteractions(provider);
        } finally {
            org.mockito.Mockito.reset(answers);
            if(materialId!=null) jdbc.update("DELETE FROM material WHERE id=?",materialId);
            for(String address:new String[]{email,otherEmail}) {
                assertTrue(address.matches("attempt-api-[0-9a-f-]{36}@example\\.invalid"));
                jdbc.update("DELETE FROM app_user WHERE email=?",address);
                assertFalse(members.existsByEmail(address));
            }
        }
    }
    private HttpResponse<String> post(String jwt, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/attempt/start"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

}
