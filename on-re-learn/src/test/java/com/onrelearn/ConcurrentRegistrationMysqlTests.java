package com.onrelearn;

import com.onrelearn.auth.service.DuplicateEmailException;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.repository.GradeRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.jpa.hibernate.ddl-auto=validate")
class ConcurrentRegistrationMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    @Test
    void concurrentHttpRegistrationsReturnOneSuccessAndOneConflict() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@example.invalid";
        var firstClient = csrfClient();
        var secondClient = csrfClient();
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> registerHttpWhenReleased(start, firstClient, email));
            var second = workers.submit(() -> registerHttpWhenReleased(start, secondClient, email));
            start.countDown();
            List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertTrue(statuses.containsAll(List.of(200, 409)), "One request must win and one must conflict");

            var member = members.findByEmail(email).orElseThrow();
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email));
            assertEquals(12, grades.findByUser_IdOrderByYearAscTermAsc(member.getId()).size());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            cleanup(email);
        }
    }

    @Test
    void competingRegistrationsCreateOneMemberAndOneSetOfGrades() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@example.invalid";
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> registerWhenReleased(start, email));
            var second = workers.submit(() -> registerWhenReleased(start, email));
            start.countDown();
            List<String> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("created"::equals).count());
            assertEquals(1, results.stream().filter("duplicate"::equals).count());

            var member = members.findByEmail(email).orElseThrow();
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email));
            assertEquals(12, grades.findByUser_IdOrderByYearAscTermAsc(member.getId()).size());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            cleanup(email);
        }
    }

    private CsrfClient csrfClient() throws Exception {
        var cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
            .GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        String token = cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
            .findFirst().orElseThrow().getValue();
        return new CsrfClient(client, token);
    }

    private int registerHttpWhenReleased(CountDownLatch start, CsrfClient session, String email) throws Exception {
        start.await();
        String body = "{\"email\":\"" + email + "\",\"password\":\"synthetic-password\"}";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", session.token())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return session.client().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void cleanup(String email) {
        // This exact synthetic address is the only row these tests may remove.
        assertTrue(email.matches("concurrent-[0-9a-f-]{36}@example\\.invalid"));
        jdbc.update("DELETE FROM app_user WHERE email = ?", email);
        assertFalse(members.existsByEmail(email));
    }

    private record CsrfClient(HttpClient client, String token) {}

    private String registerWhenReleased(CountDownLatch start, String email) throws InterruptedException {
        start.await();
        try {
            registration.register(email, "synthetic-password");
            return "created";
        } catch (DuplicateEmailException | DataIntegrityViolationException exception) {
            return "duplicate";
        }
    }
}
