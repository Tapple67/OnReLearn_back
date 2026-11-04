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
import java.util.concurrent.CompletableFuture;
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
class MaterialStudyApiMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void recordsOneDailyFactAndRetainsItAfterMaterialDeletion() throws Exception {
        String email = "material-study-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "material-study-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            registration.register(email, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            String other = members.findByEmail(otherEmail).orElseThrow().getId();
            var subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0), "Study"));
            var material = materials.saveAndFlush(new Material(subject, 1, "Lecture", "users/synthetic/study.pdf"));
            materialId = material.getId();
            String jwt = tokens.issue(owner);
            String csrf = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
            String body = "{\"materialId\":\"" + materialId + "\"}";

            assertEquals(401, record(null, csrf, body).statusCode());
            assertEquals(403, record(jwt, null, body).statusCode());
            assertEquals(403, record(tokens.issue(other), csrf, body).statusCode());
            for (String invalid : new String[]{"null", "[]", "{}", "{\"materialId\":2}",
                    "{\"materialId\":\" \"}", "{\"materialId\":\"x\",\"userId\":\"u\"}"})
                assertEquals(400, record(jwt, csrf, invalid).statusCode());
            assertEquals(0, facts(owner, materialId));

            // ! The subject lock must serialize simultaneous first writes into one unique daily row.
            var first = http.sendAsync(request(jwt, csrf, body), HttpResponse.BodyHandlers.ofString());
            var second = http.sendAsync(request(jwt, csrf, body), HttpResponse.BodyHandlers.ofString());
            CompletableFuture.allOf(first, second).join();
            assertEquals(200, first.join().statusCode());
            assertEquals(200, second.join().statusCode());
            assertTrue(first.join().headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals(1, facts(owner, materialId));
            var progressBeforeDelete = http.send(HttpRequest.newBuilder(uri("/api/goals/material-progress"))
                .header("Cookie", "token=" + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, progressBeforeDelete.statusCode());
            assertEquals(1, json.readTree(progressBeforeDelete.body()).get("todayCount").intValue());
            var recent = http.send(HttpRequest.newBuilder(uri("/api/activity/material-study/recent"))
                .header("Cookie", "token=" + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, recent.statusCode());
            assertEquals(subject.getId(), json.readTree(recent.body()).get(0).get("subjectId").stringValue());

            // ! Deleting this exact synthetic material must leave historical study facts intact.
            assertEquals(1, jdbc.update("DELETE FROM material WHERE id=?", materialId));
            assertEquals(1, facts(owner, materialId));
            var progressAfterDelete = http.send(HttpRequest.newBuilder(uri("/api/goals/material-progress"))
                .header("Cookie", "token=" + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, progressAfterDelete.statusCode());
            assertEquals(1, json.readTree(progressAfterDelete.body()).get("todayCount").intValue());
            var deletedRecent = http.send(HttpRequest.newBuilder(uri("/api/activity/material-study/recent"))
                .header("Cookie", "token=" + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
            var card = json.readTree(deletedRecent.body()).get(0);
            assertTrue(card.get("subjectId").isNull());
            assertEquals("삭제된 자료", card.get("title").stringValue());
            assertEquals(403, record(jwt, csrf, body).statusCode());
        } finally {
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id=?", materialId);
            jdbc.update("DELETE FROM app_user WHERE email=?", email);
            jdbc.update("DELETE FROM app_user WHERE email=?", otherEmail);
            assertFalse(members.existsByEmail(email));
            assertFalse(members.existsByEmail(otherEmail));
        }
    }

    private int facts(String owner, String materialId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM material_study_day WHERE user_id=? AND material_id=?",
            Integer.class, owner, materialId);
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpRequest request(String jwt, String csrf, String body) {
        var request = HttpRequest.newBuilder(uri("/api/activity/material-study"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> record(String jwt, String csrf, String body) throws Exception {
        return http.send(request(jwt, csrf, body), HttpResponse.BodyHandlers.ofString());
    }
}
