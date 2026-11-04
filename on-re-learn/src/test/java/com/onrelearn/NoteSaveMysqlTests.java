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
class NoteSaveMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.onrelearn.note.repository.NoteRepository notes;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.onrelearn.activity.repository.MaterialStudyDayRepository studyDays;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void savesWithOwnershipCsrfAndConcurrentUpsert() throws Exception {
        String ownerEmail = "note-save-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "note-save-" + UUID.randomUUID() + "@example.invalid";
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
            var csrfResponse = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.discarding());
            String csrf = csrfResponse.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
            String body = "{\"materialId\":\"" + materialId + "\",\"content\":\"한글 노트\"}";
            assertEquals(403, post(jwt, null, body).statusCode());
            assertEquals(403, post(tokens.issue(otherId), csrf, body).statusCode());
            assertEquals(403, post(null, csrf, body).statusCode());
            assertEquals(0, studyFacts(ownerId, materialId));
            var created = post(jwt, csrf, body);
            assertEquals(200, created.statusCode());
            assertEquals(1, studyFacts(ownerId, materialId));
            assertTrue(created.headers().firstValue("cache-control").orElse("").contains("no-store"));
            String noteId = JsonMapper.builder().build().readTree(created.body()).get("note").get("id").stringValue();
            jdbc.update("UPDATE note SET signals = ? WHERE id = ?", "{\"keep\":true}", noteId);
            for (String invalid : new String[] {"null", "{}", "{", body.replace("한글 노트", "changed").replace("}", ",\"page\":0}"), body.replace("}", ",\"page\":1.5}"), body.replace("\"한글 노트\"", "null"), body.replace("\"한글 노트\"", "42")}) {
                assertEquals(400, post(jwt, csrf, invalid).statusCode());
            }
            assertEquals("한글 노트", notes.findById(noteId).orElseThrow().getContent());
            assertEquals(200, post(jwt, csrf, body.replace("한글 노트", "")).statusCode());
            assertEquals("", notes.findById(noteId).orElseThrow().getContent());
            assertTrue(notes.findById(noteId).orElseThrow().getSignals().contains("true"));
            // ! 동시에 처음 저장하는 페이지에서도 하나의 행만 생성되어야 한다.
            String concurrentBody = body.replace("}", ",\"page\":2}");
            var first = http.sendAsync(request(jwt, csrf, concurrentBody), HttpResponse.BodyHandlers.ofString());
            var second = http.sendAsync(request(jwt, csrf, concurrentBody.replace("한글 노트", "second")), HttpResponse.BodyHandlers.ofString());
            var results = java.util.concurrent.CompletableFuture.allOf(first, second);
            results.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, first.get().statusCode());
            assertEquals(200, second.get().statusCode());
            assertEquals(JsonMapper.builder().build().readTree(first.get().body()).get("note").get("id").stringValue(),
                JsonMapper.builder().build().readTree(second.get().body()).get("note").get("id").stringValue());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM note WHERE material_id = ? AND page = 2", Integer.class, materialId));
            assertEquals(1, studyFacts(ownerId, materialId));
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-internal-detail"))
                .when(notes).saveAndFlush(org.mockito.ArgumentMatchers.any());
            // * 관리 중인 노트 변경 후 저장 실패에도 기존 내용이 유지되어야 한다.
            var failed = post(jwt, csrf, body);
            assertEquals(500, failed.statusCode());
            assertEquals("데이터를 처리하지 못했습니다. 다시 시도해 주세요.",
                JsonMapper.builder().build().readTree(failed.body()).get("error").stringValue());
            assertFalse(failed.body().contains("synthetic-internal-detail"));
            org.mockito.Mockito.reset(notes);
            assertEquals("", notes.findById(noteId).orElseThrow().getContent());
            // ! Activity failure after the note flush must roll back the note update too.
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-activity-detail"))
                .when(studyDays).saveAndFlush(org.mockito.ArgumentMatchers.any());
            var activityFailed = post(jwt, csrf, body);
            assertEquals(500, activityFailed.statusCode());
            assertFalse(activityFailed.body().contains("synthetic-activity-detail"));
            org.mockito.Mockito.reset(studyDays);
            assertEquals("", notes.findById(noteId).orElseThrow().getContent());
            assertEquals(1, studyFacts(ownerId, materialId));
        } finally {
            org.mockito.Mockito.reset(notes);
            org.mockito.Mockito.reset(studyDays);
            // ! 이 시험에서 생성한 자료와 계정만 정리한다. 노트는 자료 FK로 함께 정리된다.
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id = ?", materialId);
            for (String email : new String[] {ownerEmail, otherEmail}) {
                assertTrue(email.matches("note-save-[0-9a-f-]{36}@example\\.invalid"));
                jdbc.update("DELETE FROM app_user WHERE email = ?", email);
                assertFalse(members.existsByEmail(email));
            }
        }
    }

    private int studyFacts(String userId, String materialId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM material_study_day WHERE user_id=? AND material_id=?",
            Integer.class, userId, materialId);
    }

    private HttpResponse<String> post(String jwt, String csrf, String body) throws Exception {
        return http.send(request(jwt, csrf, body), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest request(String jwt, String csrf, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/notes"))
            .header("Content-Type", "application/json");
        if (jwt != null) request.header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
