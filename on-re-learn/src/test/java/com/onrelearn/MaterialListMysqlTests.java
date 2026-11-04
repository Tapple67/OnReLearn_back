package com.onrelearn;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.entity.ExtractionJob;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.material.storage.StorageUnavailableException;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
class MaterialListMysqlTests {
    @TestConfiguration
    static class StorageConfig {
        @Bean @Primary FakeStorage fakeStorage() { return new FakeStorage(); }
    }

    static class FakeStorage implements PdfObjectStorage {
        @Override public byte[] download(String path, long maxBytes) { throw new UnsupportedOperationException("No download in list test"); }
        volatile boolean fail;
        volatile String signedPath;
        @Override public void upload(String path, byte[] pdf) {}
        @Override public void delete(String path) {}
        @Override public String signedUrl(String path, long ttlSeconds) {
            signedPath = path;
            assertEquals(600, ttlSeconds);
            if (fail) throw new StorageUnavailableException();
            return "https://storage.invalid/signed?token=synthetic";
        }
    }

    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired ExtractionJobRepository jobs;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeStorage storage;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void listUsesSubjectOwnershipAndWeekOrder() throws Exception {
        String ownerEmail = "material-list-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "material-list-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        String jobId = null;
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String ownerId = members.findByEmail(ownerEmail).orElseThrow().getId();
            String otherId = members.findByEmail(otherEmail).orElseThrow().getId();
            Subject subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0), "Material list"));
            Material material = materials.saveAndFlush(Material.builder().subject(subject)
                .week(3).title("Third week").storagePath("users/synthetic/materials/test.pdf").build());
            materialId = material.getId();

            assertEquals(401, get(null, subject.getId()).statusCode());
            assertEquals(400, get(tokens.issue(ownerId), "").statusCode());
            assertEquals(403, get(tokens.issue(otherId), subject.getId()).statusCode());
            assertEquals(403, get(tokens.issue(ownerId), UUID.randomUUID().toString()).statusCode());
            var response = get(tokens.issue(ownerId), subject.getId());
            assertEquals(200, response.statusCode());
            var list = JsonMapper.builder().build().readTree(response.body());
            assertEquals(1, list.size());
            assertEquals(materialId, list.get(0).get("id").stringValue());
            assertEquals(3, list.get(0).get("week").intValue());
            assertEquals("Third week", list.get(0).get("title").stringValue());

            assertEquals(401, fileUrl(null, materialId).statusCode());
            assertEquals(400, fileUrl(tokens.issue(ownerId), "").statusCode());
            assertEquals(403, fileUrl(tokens.issue(otherId), materialId).statusCode());
            assertEquals(403, fileUrl(tokens.issue(ownerId), UUID.randomUUID().toString()).statusCode());
            var signed = fileUrl(tokens.issue(ownerId), materialId);
            assertEquals(200, signed.statusCode());
            assertTrue(signed.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals("https://storage.invalid/signed?token=synthetic",
                JsonMapper.builder().build().readTree(signed.body()).get("signedUrl").stringValue());
            assertEquals(material.getStoragePath(), storage.signedPath);

            assertEquals(401, processing(null, materialId).statusCode());
            assertEquals(400, processing(tokens.issue(ownerId), "").statusCode());
            assertEquals(403, processing(tokens.issue(otherId), materialId).statusCode());
            assertEquals(403, processing(tokens.issue(ownerId), UUID.randomUUID().toString()).statusCode());
            var notScheduled = processing(tokens.issue(ownerId), materialId);
            assertEquals(200, notScheduled.statusCode());
            assertTrue(notScheduled.headers().firstValue("cache-control").orElse("").contains("no-store"));
            assertEquals("NOT_SCHEDULED", JsonMapper.builder().build().readTree(notScheduled.body())
                .get("status").stringValue());
            ExtractionJob job = jobs.saveAndFlush(new ExtractionJob(material, 0, 3));
            jobId = job.getId();
            var pending = JsonMapper.builder().build().readTree(
                processing(tokens.issue(ownerId), materialId).body());
            assertEquals(materialId, pending.get("materialId").stringValue());
            assertEquals("PENDING", pending.get("status").stringValue());
            assertEquals(0, pending.get("attempts").intValue());
            assertEquals(3, pending.get("maxAttempts").intValue());
            assertNull(pending.get("lastError"));
            storage.fail = true;
            assertEquals(503, fileUrl(tokens.issue(ownerId), materialId).statusCode());
            storage.fail = false;
        } finally {
            storage.fail = false;
            // ! FK RESTRICT를 지키며 이 시험이 만든 행만 ID와 이메일로 정확히 정리한다.
            if (jobId != null) jdbc.update("DELETE FROM extraction_job WHERE id = ?", jobId);
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id = ?", materialId);
            cleanup(ownerEmail);
            cleanup(otherEmail);
        }
    }

    private HttpResponse<String> get(String jwt, String subjectId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/api/materials?subjectId=" + subjectId));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> fileUrl(String jwt, String materialId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/api/materials/file_url?materialId=" + materialId));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> processing(String jwt, String materialId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/api/materials/status?materialId=" + materialId));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void cleanup(String email) {
        assertTrue(email.matches("material-list-[0-9a-f-]{36}@example\\.invalid"));
        jdbc.update("DELETE FROM app_user WHERE email = ?", email);
        assertFalse(members.existsByEmail(email));
    }
}
