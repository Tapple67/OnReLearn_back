package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.repository.GradeRepository;
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
class SubjectCreateMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void createAndListRespectOwnershipInputAndCsrf() throws Exception {
        String ownerEmail = "subject-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "subject-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String ownerId = members.findByEmail(ownerEmail).orElseThrow().getId();
            String otherId = members.findByEmail(otherEmail).orElseThrow().getId();
            String gradeId = grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0).getId();
            String foreignId = grades.findByUser_IdOrderByYearAscTermAsc(otherId).get(0).getId();
            String jwt = tokens.issue(ownerId);
            String csrf = csrfCookie();

            assertEquals(200, workspace(jwt, "", null).statusCode());

            assertEquals(401, get(null, gradeId).statusCode());
            assertEquals(400, get(jwt, "").statusCode());
            assertEquals(200, get(jwt, foreignId).statusCode());
            assertEquals("[]", get(jwt, foreignId).body());
            assertEquals(403, post(jwt, null, gradeId, "운영체제").statusCode());
            assertEquals(400, post(jwt, csrf, gradeId, " ").statusCode());
            assertEquals(400, post(jwt, csrf, "", "운영체제").statusCode());
            assertEquals(403, post(jwt, csrf, foreignId, "운영체제").statusCode());

            var created = post(jwt, csrf, gradeId, " 운영체제 ");
            assertEquals(201, created.statusCode());
            var result = JsonMapper.builder().build().readTree(created.body());
            assertEquals("운영체제", result.get("subject").get("name").stringValue());
            String subjectId = result.get("subject").get("id").stringValue();
            assertEquals(gradeId, subjects.findByIdAndGrade_User_Id(subjectId, ownerId).orElseThrow().getGrade().getId());
            var list = get(jwt, gradeId);
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains(subjectId));
            assertTrue(list.body().contains("\"materials\":[]"));
            assertFalse(get(tokens.issue(otherId), gradeId).body().contains(subjectId));

            assertEquals(401, workspace(null, gradeId, subjectId).statusCode());
            assertEquals(404, workspace(jwt, foreignId, null).statusCode());
            assertEquals(404, workspace(jwt, gradeId, foreignId).statusCode());
            String anotherOwnedGradeId = grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(1).getId();
            assertEquals(404, workspace(jwt, anotherOwnedGradeId, subjectId).statusCode());
            var selected = workspace(jwt, gradeId, subjectId);
            assertEquals(200, selected.statusCode());
            var selection = JsonMapper.builder().build().readTree(selected.body());
            assertEquals(gradeId, selection.get("selectedGradeId").stringValue());
            assertEquals(subjectId, selection.get("selectedSubjectId").stringValue());
            assertFalse(workspace(tokens.issue(otherId), null, null).body().contains(subjectId));

            assertEquals(403, change("PATCH", jwt, null, subjectId, "자료구조").statusCode());
            assertEquals(400, change("PATCH", jwt, csrf, "", "자료구조").statusCode());
            assertEquals(400, change("PATCH", jwt, csrf, subjectId, " ").statusCode());
            assertEquals(403, change("PATCH", tokens.issue(otherId), csrf, subjectId, "자료구조").statusCode());
            var renamed = change("PATCH", jwt, csrf, subjectId, " 자료구조 ");
            assertEquals(200, renamed.statusCode());
            assertEquals("자료구조", subjects.findById(subjectId).orElseThrow().getName());
            assertTrue(renamed.body().contains("자료구조"));
            assertEquals(403, change("DELETE", tokens.issue(otherId), csrf, subjectId, null).statusCode());
            assertEquals(403, change("DELETE", jwt, null, subjectId, null).statusCode());
            assertEquals(400, change("DELETE", jwt, csrf, "", null).statusCode());
            assertEquals(200, change("DELETE", jwt, csrf, subjectId, null).statusCode());
            assertFalse(subjects.existsById(subjectId));
            assertFalse(get(jwt, gradeId).body().contains(subjectId));
        } finally {
            cleanup(ownerEmail);
            cleanup(otherEmail);
        }
    }

    private String csrfCookie() throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        return response.headers().allValues("set-cookie").stream()
            .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
            .findFirst().orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> get(String jwt, String gradeId) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/subjects?gradeId=" + gradeId));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> workspace(String jwt, String gradeId, String subjectId) throws Exception {
        String query = gradeId == null ? "" : "?gradeId=" + gradeId;
        if (subjectId != null) query += (query.isEmpty() ? "?" : "&") + "subjectId=" + subjectId;
        var request = HttpRequest.newBuilder(uri("/api/subjects/workspace" + query));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String jwt, String csrf, String gradeId, String name) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/subjects"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        String body = "{\"gradeId\":\"" + gradeId + "\",\"name\":\"" + name + "\"}";
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> change(String method, String jwt, String csrf, String subjectId, String name)
        throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/subjects"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        String body = "{\"subjectId\":\"" + subjectId + "\"" +
            (name == null ? "" : ",\"name\":\"" + name + "\"") + "}";
        return http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void cleanup(String email) {
        // ! 승인된 합성 계정 형식만 삭제하고 기존 회원 데이터에는 접근하지 않는다.
        assertTrue(email.matches("subject-[0-9a-f-]{36}@example\\.invalid"));
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM material m JOIN subject s ON s.id=m.subject_id
            JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?
            """, Integer.class, email));
        // ! 합성 계정의 자식부터 정리해 실제 FK의 RESTRICT에서도 원래 시험 실패를 숨기지 않는다.
        jdbc.update("DELETE s FROM subject s JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
        jdbc.update("DELETE g FROM grade g JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
        jdbc.update("DELETE FROM app_user WHERE email = ?", email);
        assertFalse(members.existsByEmail(email));
    }
}
