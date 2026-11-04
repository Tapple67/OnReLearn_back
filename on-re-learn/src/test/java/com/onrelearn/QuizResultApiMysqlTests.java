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
class QuizResultApiMysqlTests {
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

    @Autowired com.onrelearn.quiz.service.QuizAttemptStartService starts;
    @Autowired com.onrelearn.quiz.service.QuizSubmissionService submissions;
    @Test void exposesOnlyOwnedCompletedResultsOverHttp() throws Exception {
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
            String attempt=starts.start(owner,set.getId()).attemptId();

            var json=JsonMapper.builder().build();
            assertEquals(401,get(null,attempt).statusCode());
            assertEquals(400,get(jwt,null).statusCode());
            assertEquals(400,get(jwt," ").statusCode());
            assertEquals(404,get(jwt,"missing").statusCode());
            assertEquals(404,get(tokens.issue(other),attempt).statusCode());
            var pending=get(jwt,attempt);
            assertEquals(400,pending.statusCode());
            assertFalse(pending.body().contains("answerKey")); assertFalse(pending.body().contains("Hidden explanation"));
            submissions.submit(owner,attempt);
            var response=get(jwt,attempt);
            assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var wrapper=json.readTree(response.body());
            assertEquals(1,wrapper.size());
            var result=wrapper.get("attempt");
            assertEquals(9,result.size()); assertEquals(attempt,result.get("id").stringValue());
            assertEquals("GRADED",result.get("status").stringValue());
            assertEquals(0,result.get("score").intValue()); assertEquals(100,result.get("maxScore").intValue());
            assertFalse(result.has("userId"));
            var question=result.get("quizSet").get("items").get(0);
            assertEquals(9,question.size());
            assertTrue(question.get("answerKey").get("correct").booleanValue());
            assertEquals("Hidden explanation",question.get("explanation").stringValue());
            var answer=result.get("answers").get(0);
            assertEquals(5,answer.size()); assertFalse(answer.get("isCorrect").booleanValue());
            assertEquals(0,answer.get("earned").intValue()); assertTrue(answer.get("feedback").isNull());
            assertEquals(wrapper,json.readTree(get(jwt,attempt).body()));
            assertEquals(404,get(tokens.issue(other),attempt).statusCode());
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail"))
                .when(answers).findByAttempt_Id(attempt);
            var failed=get(jwt,attempt);
            assertEquals(500,failed.statusCode()); assertFalse(failed.body().contains("synthetic-private-detail"));
            assertFalse(failed.body().contains("answerKey"));
            org.mockito.Mockito.reset(answers);
            assertEquals(wrapper,json.readTree(get(jwt,attempt).body()));
            jdbc.update("UPDATE quiz_attempt SET status='SUBMITTED',score=NULL,max_score=NULL,graded_at=NULL WHERE id=?",attempt);
            var submitted=json.readTree(get(jwt,attempt).body()).get("attempt");
            assertEquals("SUBMITTED",submitted.get("status").stringValue());
            assertTrue(submitted.get("score").isNull()); assertTrue(submitted.get("gradedAt").isNull());
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

    private HttpResponse<String> get(String jwt,String attemptId) throws Exception {
        String query=attemptId==null ? "" : "?attemptId="+java.net.URLEncoder.encode(attemptId,java.nio.charset.StandardCharsets.UTF_8);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/attempt/result"+query));
        if(jwt!=null) request.header("Cookie","token="+jwt);
        return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
}
