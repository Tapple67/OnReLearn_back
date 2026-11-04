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
class SummaryApiMysqlTests {
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

    @Test
    void readsAndGeneratesOwnedSummariesWithCsrfAndFailurePreservation() throws Exception {
        String ownerEmail = "summary-api-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "summary-api-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String ownerId = members.findByEmail(ownerEmail).orElseThrow().getId();
            String otherId = members.findByEmail(otherEmail).orElseThrow().getId();
            Subject subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0), "Note read"));
            Material material = materials.saveAndFlush(new Material(subject, 1, "Notes", "users/synthetic/materials/test.pdf"));
            materialId = material.getId();
            String jwt = tokens.issue(ownerId);
            String query = "materialId=" + materialId;
            assertEquals(401, get(null, query).statusCode());
            assertEquals(403, get(tokens.issue(otherId), query).statusCode());
            assertEquals(400, get(jwt, "").statusCode());
            var json = JsonMapper.builder().build();
            assertTrue(json.readTree(get(jwt, query).body()).get("summary").isNull());
            org.mockito.Mockito.verifyNoInteractions(provider);
            var csrfResponse = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.discarding());
            String csrf = csrfResponse.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
            String body = "{\"materialId\":\"" + materialId + "\"}";
            assertEquals(403, post(jwt, null, body).statusCode());
            assertEquals(403, post(tokens.issue(otherId), csrf, body).statusCode());
            for (String invalid : new String[] {"null", "{}", "{", "{\"materialId\":42}", "{\"materialId\":\" \"}"}) {
                assertEquals(400, post(jwt, csrf, invalid).statusCode());
            }
            org.mockito.Mockito.verifyNoInteractions(provider);
            org.mockito.Mockito.doReturn(new com.onrelearn.summary.ai.SummaryResult("Synthetic summary", null, null,
                "synthetic", "test-model", com.onrelearn.summary.ai.SummaryPrompt.VERSION)).when(provider).generate(org.mockito.ArgumentMatchers.any());
            var created = post(jwt, csrf, body);
            assertEquals(200, created.statusCode());
            assertTrue(created.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertFalse(json.readTree(created.body()).get("reused").booleanValue());
            assertTrue(json.readTree(created.body()).get("ok").booleanValue());
            assertNull(json.readTree(created.body()).get("packet"));
            assertTrue(json.readTree(post(jwt, csrf, body).body()).get("reused").booleanValue());
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.any());
            var read = get(jwt, query);
            assertEquals(200, read.statusCode());
            assertTrue(read.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals("Synthetic summary", json.readTree(read.body()).get("summary").get("content").stringValue());
            // * A changed note invalidates reuse; provider failures keep the saved summary readable.
            jdbc.update("INSERT INTO note (id, material_id, page, content, created_at, updated_at) VALUES (?, ?, 1, ?, NOW(6), NOW(6))",
                UUID.randomUUID().toString(), materialId, "Changed input");
            for (int status : new int[] {502, 503, 504}) {
                org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.valueOf(status), "Summary unavailable")).when(provider).generate(org.mockito.ArgumentMatchers.any());
                assertEquals(status, post(jwt, csrf, body).statusCode());
                assertEquals("Synthetic summary", json.readTree(get(jwt, query).body()).get("summary").get("content").stringValue());
            }
        } finally {
            // ! 이 시험에서 생성한 자료와 계정만 정리한다. 노트는 자료 FK로 함께 정리된다.
            SyntheticMaterialCleanup.delete(jdbc, materialId);
            for (String email : new String[] {ownerEmail, otherEmail}) {
                assertTrue(email.matches("summary-api-[0-9a-f-]{36}@example\\.invalid"));
                // ! 합성 계정의 자식부터 정리해 FK RESTRICT에서도 정상/오류 시험 결과를 보존한다.
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material m JOIN subject s ON s.id=m.subject_id JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", Integer.class, email));
                jdbc.update("DELETE s FROM subject s JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
                jdbc.update("DELETE g FROM grade g JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
                jdbc.update("DELETE FROM app_user WHERE email = ?", email);
                assertFalse(members.existsByEmail(email));
            }
        }
    }

    private HttpResponse<String> post(String jwt, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/summary/summaries"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String jwt, String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/summary/summaries?" + query));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
