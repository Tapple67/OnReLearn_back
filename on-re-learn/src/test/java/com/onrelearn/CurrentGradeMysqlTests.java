package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class CurrentGradeMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void currentGradeChangePreservesOwnershipCsrfAndSingleCurrentUnderConcurrency() throws Exception {
        String ownerEmail = "grade-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "grade-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String ownerId = members.findByEmail(ownerEmail).orElseThrow().getId();
            String otherId = members.findByEmail(otherEmail).orElseThrow().getId();
            List<Grade> owned = grades.findByUser_IdOrderByYearAscTermAsc(ownerId);
            String foreignGradeId = grades.findByUser_IdOrderByYearAscTermAsc(otherId).get(0).getId();
            String jwt = tokens.issue(ownerId);
            String csrf = csrfCookie();

            assertEquals(401, getGrades(null).statusCode());
            var listing = getGrades(jwt);
            assertEquals(200, listing.statusCode());
            assertEquals(12, JsonMapper.builder().build().readTree(listing.body()).size());
            assertFalse(listing.body().contains(foreignGradeId));
            assertEquals(403, patch(jwt, null, owned.get(1).getId()).statusCode());
            assertEquals(400, patch(jwt, csrf, "").statusCode());
            assertEquals(404, patch(jwt, csrf, foreignGradeId).statusCode());
            assertEquals(200, patch(jwt, csrf, owned.get(0).getId()).statusCode());
            assertEquals(200, patch(jwt, csrf, owned.get(1).getId()).statusCode());
            assertCurrent(ownerId, owned.get(1).getId());

            CountDownLatch start = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> patchWhenReleased(start, jwt, csrf, owned.get(2).getId()));
                var second = workers.submit(() -> patchWhenReleased(start, jwt, csrf, owned.get(3).getId()));
                start.countDown();
                assertEquals(200, first.get(30, TimeUnit.SECONDS));
                assertEquals(200, second.get(30, TimeUnit.SECONDS));
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
            var current = grades.findByUser_IdOrderByYearAscTermAsc(ownerId).stream()
                .filter(Grade::isCurrent).toList();
            assertEquals(1, current.size());
            assertTrue(List.of(owned.get(2).getId(), owned.get(3).getId()).contains(current.get(0).getId()));
        } finally {
            cleanup(ownerEmail);
            cleanup(otherEmail);
        }
    }

    private int patchWhenReleased(CountDownLatch start, String jwt, String csrf, String gradeId) throws Exception {
        start.await();
        return patch(jwt, csrf, gradeId).statusCode();
    }

    private String csrfCookie() throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        return response.headers().allValues("set-cookie").stream()
            .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
            .findFirst().orElseThrow().split(";", 2)[0];
    }

    private HttpResponse<String> getGrades(String jwt) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/grades"));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String jwt, String csrf, String gradeId) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/grades"))
            .header("Content-Type", "application/json")
            .header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.method("PATCH", HttpRequest.BodyPublishers.ofString(
            "{\"gradeId\":\"" + gradeId + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertCurrent(String memberId, String gradeId) {
        var current = grades.findByUser_IdOrderByYearAscTermAsc(memberId).stream()
            .filter(Grade::isCurrent).toList();
        assertEquals(1, current.size());
        assertEquals(gradeId, current.get(0).getId());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void cleanup(String email) {
        // ! 합성 이메일만 삭제하고 FK가 소유 학기까지 정리한다.
        assertTrue(email.matches("grade-[0-9a-f-]{36}@example\\.invalid"));
        jdbc.update("DELETE FROM app_user WHERE email = ?", email);
        assertFalse(members.existsByEmail(email));
    }
}
