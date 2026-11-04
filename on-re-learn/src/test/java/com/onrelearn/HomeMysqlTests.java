package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
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
class HomeMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    @Test
    void homeShowsOnlyTheAuthenticatedMembersCurrentGradeAndSubjects() throws Exception {
        String ownerEmail = "home-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "home-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            var owner = members.findByEmail(ownerEmail).orElseThrow();
            var other = members.findByEmail(otherEmail).orElseThrow();
            Grade ownerGrade = currentGrade(owner.getId());
            Grade otherGrade = currentGrade(other.getId());
            subjects.saveAndFlush(new Subject(ownerGrade, "Owned subject"));
            subjects.saveAndFlush(new Subject(otherGrade, "Other member subject"));

            assertEquals(401, getHome(null).statusCode());
            assertEquals(401, getHome("invalid-token").statusCode());
            var response = getHome(tokens.issue(owner.getId()));
            assertEquals(200, response.statusCode());
            var body = JsonMapper.builder().build().readTree(response.body());
            assertEquals(12, body.get("grades").size());
            assertEquals(ownerGrade.getId(), body.get("currentGrade").get("id").stringValue());
            assertEquals(1, body.get("currentGrade").get("subjects").size());
            assertEquals("Owned subject", body.get("currentGrade").get("subjects").get(0).get("name").stringValue());
            assertFalse(response.body().contains("Other member subject"));
        } finally {
            cleanup(ownerEmail);
            cleanup(otherEmail);
        }
    }

    private Grade currentGrade(String memberId) {
        return grades.findByUser_IdOrderByYearAscTermAsc(memberId).stream()
            .filter(Grade::isCurrent).findFirst().orElseThrow();
    }

    private HttpResponse<String> getHome(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/home"));
        if (token != null) request.header("Cookie", "token=" + token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void cleanup(String email) {
        // ! 허용된 합성 이메일만 삭제하며 FK가 학기·과목을 함께 정리한다.
        assertTrue(email.matches("home-[0-9a-f-]{36}@example\\.invalid"));
        jdbc.update("DELETE FROM app_user WHERE email = ?", email);
        assertFalse(members.existsByEmail(email));
    }
}
