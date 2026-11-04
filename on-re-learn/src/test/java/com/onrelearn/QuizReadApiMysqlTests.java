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
class QuizReadApiMysqlTests {
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

    @Test void listsOnlyOwnedSetsWithCountsAndNoAnswers() throws Exception {
        String ownerEmail="quiz-read-"+UUID.randomUUID()+"@example.invalid";
        String otherEmail="quiz-read-"+UUID.randomUUID()+"@example.invalid";
        java.util.List<String> materialIds=new java.util.ArrayList<>();
        try {
            registration.register(ownerEmail,"synthetic-password");
            registration.register(otherEmail,"synthetic-password");
            String owner=members.findByEmail(ownerEmail).orElseThrow().getId();
            String other=members.findByEmail(otherEmail).orElseThrow().getId();
            Subject subject=subjects.saveAndFlush(new Subject(grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0),"Quiz read"));
            Material material=materials.saveAndFlush(new Material(subject,1,"Quiz","users/synthetic/test.pdf"));
            materialIds.add(material.getId());
            Material empty=materials.saveAndFlush(new Material(subject,2,"Empty","users/synthetic/empty.pdf"));
            materialIds.add(empty.getId());
            String query="materialId="+material.getId();
            String jwt=tokens.issue(owner);
            assertEquals(401,get(null,query).statusCode());
            assertEquals(403,get(tokens.issue(other),query).statusCode());
            assertEquals(403,get(jwt,"materialId=missing").statusCode());
            assertEquals(400,get(jwt,"").statusCode());
            assertEquals(400,get(jwt,"materialId=%20").statusCode());
            var json=JsonMapper.builder().build();
            var emptyResponse=get(jwt,query);
            assertEquals(200,emptyResponse.statusCode());
            assertEquals(0,json.readTree(emptyResponse.body()).get("quizSets").size());
            var older=sets.saveAndFlush(com.onrelearn.quiz.entity.QuizSet.builder().material(material).sourceHash("hash")
                .purpose(com.onrelearn.quiz.model.QuizPurpose.GENERAL).build());
            var newer=sets.saveAndFlush(com.onrelearn.quiz.entity.QuizSet.builder().material(material).sourceHash("hash")
                .title("Synthetic title").purpose(com.onrelearn.quiz.model.QuizPurpose.GENERAL).build());
            jdbc.update("UPDATE quiz_set SET created_at='2020-01-01 00:00:00' WHERE id=?",older.getId());
            for(int pos=1;pos<=2;pos++) items.saveAndFlush(com.onrelearn.quiz.entity.QuizItem.builder()
                .quizSet(newer).position(pos).type("tf").question("Private question").answerKey("{\"correct\":true}")
                .explanation("Private explanation").points(50).build());
            var response=get(jwt,query);
            assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var list=json.readTree(response.body()).get("quizSets");
            assertEquals(2,list.size());
            assertEquals(newer.getId(),list.get(0).get("id").stringValue());
            assertEquals("Synthetic title",list.get(0).get("title").stringValue());
            assertEquals(2,list.get(0).get("itemCount").intValue());
            assertEquals(5,list.get(0).size());
            assertTrue(list.get(0).get("latestAttempt").isNull());
            assertTrue(list.get(1).get("title").isNull());
            assertEquals(0,list.get(1).get("itemCount").intValue());
            assertEquals(0,json.readTree(get(jwt,"materialId="+empty.getId()).body()).get("quizSets").size());
            assertFalse(response.body().contains("Private"));
            assertFalse(response.body().contains("answerKey"));
            String progress=insertAttempt(owner,newer.getId(),"IN_PROGRESS","2020-01-02 00:00:00");
            String newestProgress=insertAttempt(owner,newer.getId(),"IN_PROGRESS","2020-01-05 00:00:00");
            assertEquals(newestProgress,json.readTree(get(jwt,query).body()).get("quizSets").get(0).get("latestAttempt").get("id").stringValue());
            String graded=insertAttempt(owner,newer.getId(),"GRADED","2020-01-03 00:00:00");
            jdbc.update("UPDATE quiz_attempt SET score=0,max_score=100,submitted_at='2020-01-03 00:01:00' WHERE id=?",graded);
            var preferred=json.readTree(get(jwt,query).body()).get("quizSets").get(0).get("latestAttempt");
            assertEquals(graded,preferred.get("id").stringValue());
            assertEquals("GRADED",preferred.get("status").stringValue());
            assertEquals(0,preferred.get("score").intValue());
            assertEquals(100,preferred.get("maxScore").intValue());
            assertNotNull(preferred.get("submittedAt").stringValue());
            assertEquals(6,preferred.size());
            String submitted=insertAttempt(owner,newer.getId(),"SUBMITTED","2020-01-04 00:00:00");
            String foreign=insertAttempt(other,newer.getId(),"GRADED","2020-01-06 00:00:00");
            String otherOnly=insertAttempt(other,older.getId(),"GRADED","2020-01-06 00:00:00");
            var withAttempts=json.readTree(get(jwt,query).body()).get("quizSets");
            assertEquals(submitted,withAttempts.get(0).get("latestAttempt").get("id").stringValue());
            assertTrue(withAttempts.get(0).get("latestAttempt").get("score").isNull());
            assertTrue(withAttempts.get(1).get("latestAttempt").isNull());
            assertEquals(2,withAttempts.get(0).get("itemCount").intValue());
            assertFalse(withAttempts.toString().contains(foreign));
            assertFalse(withAttempts.toString().contains(otherOnly));
            // ! Reading preferred history must preserve every superseded attempt.
            assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE quiz_set_id IN (?,?)",Integer.class,newer.getId(),older.getId()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE id=?",Integer.class,progress));
            org.mockito.Mockito.verifyNoInteractions(provider);
        } finally {
            // ! Clean up only the material IDs and accounts allocated above.
            for(String id:materialIds) SyntheticMaterialCleanup.delete(jdbc, id);
            for(String email:new String[]{ownerEmail,otherEmail}) {
                assertTrue(email.matches("quiz-read-[0-9a-f-]{36}@example\\.invalid"));
                // ! 합성 계정의 자식부터 정리해 FK RESTRICT에서도 정상/오류 시험 결과를 보존한다.
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material m JOIN subject s ON s.id=m.subject_id JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", Integer.class, email));
                jdbc.update("DELETE s FROM subject s JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
                jdbc.update("DELETE g FROM grade g JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
                jdbc.update("DELETE FROM app_user WHERE email=?",email);
                assertFalse(members.existsByEmail(email));
            }
        }
    }
    private String insertAttempt(String owner,String setId,String status,String started) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO quiz_attempt(id,user_id,quiz_set_id,status,started_at) VALUES (?,?,?,?,?)",id,owner,setId,status,started);
        return id;
    }

    private HttpResponse<String> get(String jwt, String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/quiz/quiz-sets?" + query));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
