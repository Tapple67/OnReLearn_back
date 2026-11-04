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
class QuizGenerationApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.onrelearn.summary.ai.SummaryProvider summaries;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.onrelearn.quiz.ai.QuizProvider provider;
    @Autowired com.onrelearn.quiz.repository.QuizSetRepository sets;
    @Autowired com.onrelearn.quiz.repository.QuizItemRepository quizItems;
    @Autowired com.onrelearn.quiz.repository.QuizAttemptRepository attempts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.onrelearn.quiz.repository.QuizAnswerRepository answers;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();


    @Test void validatesGeneratesAndPreservesSavedSetsOnFailure() throws Exception {
        String ownerEmail = "quiz-api-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "quiz-api-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String ownerId = members.findByEmail(ownerEmail).orElseThrow().getId();
            String otherId = members.findByEmail(otherEmail).orElseThrow().getId();
            Subject subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0), "Quiz API"));
            Material material = materials.saveAndFlush(new Material(subject, 1, "Notes", "users/synthetic/materials/test.pdf"));
            materialId = material.getId();
            String jwt = tokens.issue(ownerId);
            var csrfResponse = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.discarding());
            String csrf = csrfResponse.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
            String prefix = "{\"materialId\":\"" + materialId + "\"";
            String body = prefix + ",\"spec\":{\"mcqCount\":0,\"tfCount\":1,\"shortCount\":0}}";
            assertEquals(401, post(null, csrf, body).statusCode());
            assertEquals(403, post(jwt, null, body).statusCode());
            assertEquals(403, post(tokens.issue(otherId), csrf, body).statusCode());
            for (String invalid : new String[]{"null", "{}", "{", "[]", "{\"materialId\":42}", "{\"materialId\":\" \"}",
                    prefix + ",\"purpose\":\"WRONG_REVIEW\"}", prefix + ",\"purpose\":\"WEAK_TOPIC\"}",
                    prefix + ",\"purpose\":\"unknown\"}", prefix + ",\"sourceTopics\":[\"topic\"]}",
                    prefix + ",\"sourceAttemptId\":\"attempt\"}"}) {
                assertEquals(400, post(jwt, csrf, invalid).statusCode());
            }
            for (String spec : new String[]{"[]", "{}", "{\"mcqCount\":\"1\",\"tfCount\":0,\"shortCount\":0}",
                    "{\"mcqCount\":1.5,\"tfCount\":0,\"shortCount\":0}",
                    "{\"mcqCount\":-1,\"tfCount\":1,\"shortCount\":0}",
                    "{\"mcqCount\":0,\"tfCount\":0,\"shortCount\":0}",
                    "{\"mcqCount\":2147483648,\"tfCount\":0,\"shortCount\":0}",
                    "{\"mcqCount\":2147483647,\"tfCount\":1,\"shortCount\":0}"}) {
                assertEquals(400, post(jwt, csrf, prefix + ",\"spec\":" + spec + "}").statusCode());
            }
            org.mockito.Mockito.verifyNoInteractions(provider, summaries);
            org.mockito.Mockito.when(summaries.generate(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.onrelearn.summary.ai.SummaryResult("Summary", null, null, "synthetic", "model", com.onrelearn.summary.ai.SummaryPrompt.VERSION));
            org.mockito.Mockito.doAnswer(call -> {
                com.onrelearn.quiz.model.QuizSpec spec = call.getArgument(1);
                var json = JsonMapper.builder().build();
                var output = json.createObjectNode();
                var items = output.putArray("items");
                for (int i = 0; i < spec.totalCount(); i++) {
                    String type = i < spec.mcqCount() ? "mcq" : i < spec.mcqCount()+spec.tfCount() ? "tf" : "short";
                    var item = items.addObject().put("type", type).put("question", "Question").put("explanation", "Explanation");
                    var answer = item.putObject("answerKey");
                    if (type.equals("mcq")) { item.putArray("choices").add("A").add("B"); answer.put("correctIndex", 0); }
                    else if (type.equals("tf")) answer.put("correct", true);
                    else answer.putArray("accepted").add("Answer");
                }
                return new com.onrelearn.quiz.ai.QuizResult(output, spec, "synthetic", "model", com.onrelearn.quiz.ai.QuizPrompt.VERSION);
            }).when(provider).generate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            var json = JsonMapper.builder().build();
            var first = post(jwt, csrf, body);
            assertEquals(200, first.statusCode());
            assertTrue(first.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var created = json.readTree(first.body());
            assertTrue(created.get("ok").booleanValue());
            assertEquals(5, created.size());
            assertNotNull(created.get("createdAt"));
            assertNotNull(created.get("summaryId"));
            var second = post(jwt, csrf, body);
            assertEquals(200, second.statusCode());
            assertNotEquals(created.get("quizSetId"), json.readTree(second.body()).get("quizSetId"));
            assertEquals(created.get("sourceHash"), json.readTree(second.body()).get("sourceHash"));
            var defaults = post(jwt, csrf, prefix + ",\"purpose\":\"GENERAL\",\"sourceTopics\":[]}");
            assertEquals(200, defaults.statusCode());
            String setId = json.readTree(defaults.body()).get("quizSetId").stringValue();
            assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_item WHERE quiz_set_id=?", Integer.class, setId));
            org.mockito.Mockito.verify(summaries,org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.any());

            var sourceSet=sets.findById(created.get("quizSetId").stringValue()).orElseThrow();
            var sourceItem=quizItems.findByQuizSet_IdOrderByPositionAsc(sourceSet.getId()).get(0);
            var attempt=attempts.saveAndFlush(new com.onrelearn.quiz.entity.QuizAttempt(ownerId,sourceSet));
            var answer=new com.onrelearn.quiz.entity.QuizAnswer(attempt,sourceItem,"{\"value\":false}");
            answer.recordGrade(false,0); answers.saveAndFlush(answer);
            String review=prefix+",\"purpose\":\"WRONG_REVIEW\",\"sourceAttemptId\":\""+attempt.getId()+"\"}";
            assertEquals(404,post(jwt,csrf,review).statusCode());
            attempt.completeGrading(0,100,java.time.Instant.now());attempts.saveAndFlush(attempt);
            assertEquals(401,post(null,csrf,review).statusCode());
            assertEquals(403,post(jwt,null,review).statusCode());
            assertEquals(404,post(tokens.issue(otherId),csrf,review).statusCode());
            assertEquals(404,post(jwt,csrf,review.replace(materialId,"other-material")).statusCode());
            for (String invalid : new String[]{
                    prefix+",\"purpose\":1}",
                    prefix+",\"purpose\":\"WRONG_REVIEW\",\"sourceAttemptId\":false}",
                    prefix+",\"purpose\":\"WRONG_REVIEW\",\"sourceAttemptId\":\" \"}",
                    prefix+",\"purpose\":\"WEAK_TOPIC\",\"sourceTopics\":\"Topic\"}",
                    prefix+",\"purpose\":\"WEAK_TOPIC\",\"sourceTopics\":[]}",
                    prefix+",\"purpose\":\"WEAK_TOPIC\",\"sourceTopics\":[\"Topic\"],\"sourceAttemptId\":\"bad\"}"})
                assertEquals(400,post(jwt,csrf,invalid).statusCode());
            var reviewed=post(jwt,csrf,review);
            assertEquals(200,reviewed.statusCode());
            assertTrue(reviewed.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var reviewResponse=json.readTree(reviewed.body());
            assertEquals(5,reviewResponse.size());
            String reviewId=reviewResponse.get("quizSetId").stringValue();
            assertEquals(5L,quizItems.findByQuizSet_IdOrderByPositionAsc(reviewId).stream().filter(i->i.getType().equals("mcq")).count());
            assertEquals(3L,quizItems.findByQuizSet_IdOrderByPositionAsc(reviewId).stream().filter(i->i.getType().equals("tf")).count());
            assertEquals(2L,quizItems.findByQuizSet_IdOrderByPositionAsc(reviewId).stream().filter(i->i.getType().equals("short")).count());
            assertEquals(attempt.getId(),sets.findById(reviewId).orElseThrow().getSourceAttemptId());
            var weak=post(jwt,csrf,prefix+",\"purpose\":\"WEAK_TOPIC\",\"sourceTopics\":[\" Topic \",\"Topic\"]}");
            assertEquals(200,weak.statusCode());
            String weakId=json.readTree(weak.body()).get("quizSetId").stringValue();
            assertEquals("Topic",json.readTree(sets.findById(weakId).orElseThrow().getSourceTopics()).get(0).stringValue());
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-detail"))
                    .when(answers).findByAttempt_Id(attempt.getId());
            try {
                var failed=post(jwt,csrf,review);
                assertEquals(500,failed.statusCode());
                assertFalse(failed.body().contains("synthetic-private-detail"));
                assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_set WHERE material_id=?",Integer.class,materialId));
            } finally { org.mockito.Mockito.reset(answers); }
            assertEquals(1,answers.findByAttempt_Id(attempt.getId()).size());
            jdbc.update("UPDATE quiz_answer SET is_correct=TRUE WHERE id=?",answer.getId());
            assertEquals(400,post(jwt,csrf,review).statusCode());
            jdbc.update("UPDATE quiz_answer SET is_correct=FALSE WHERE id=?",answer.getId());
            for (int status : new int[]{502,503,504}) {
                org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.valueOf(status), "Quiz unavailable"))
                    .when(provider).generate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.isNull());
                assertEquals(status, post(jwt,csrf,body).statusCode());
                assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_set WHERE material_id=?",Integer.class,materialId));
            }
        } finally {
            // ! Only this test's synthetic material and accounts are removed.
            SyntheticMaterialCleanup.delete(jdbc, materialId);
            for(String email : new String[]{ownerEmail,otherEmail}) {
                assertTrue(email.matches("quiz-api-[0-9a-f-]{36}@example\\.invalid"));
                // ! 합성 계정의 자식부터 정리해 FK RESTRICT에서도 정상/오류 시험 결과를 보존한다.
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material m JOIN subject s ON s.id=m.subject_id JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", Integer.class, email));
                jdbc.update("DELETE s FROM subject s JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
                jdbc.update("DELETE g FROM grade g JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
                jdbc.update("DELETE FROM app_user WHERE email=?",email);
                assertFalse(members.existsByEmail(email));
            }
        }
    }
    private HttpResponse<String> post(String jwt, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/quiz/quiz-sets"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

}
