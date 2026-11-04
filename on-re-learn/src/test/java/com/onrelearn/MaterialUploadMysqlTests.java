package com.onrelearn;
import com.onrelearn.material.entity.Material;

import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.entity.ExtractionJob;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.service.ExtractionWorker;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@Tag("mysql")
@ContextConfiguration(initializers = OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "onrelearn.auth.jwt.secret=synthetic-test-secret-at-least-32-bytes-long"
})
class MaterialUploadMysqlTests {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.onrelearn.material.repository.MaterialContentDeletionRepository contentDeletes;
    @TestConfiguration
    static class StorageConfig {
        @Bean @Primary FakeStorage fakeStorage() { return new FakeStorage(); }
    }

    static class FakeStorage implements PdfObjectStorage {
        final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<>();
        @Override public byte[] download(String path, long maxBytes) {
            byte[] pdf = objects.get(path);
            if (pdf == null || pdf.length > maxBytes) throw new StorageUnavailableException();
            return pdf;
        }
        final CopyOnWriteArrayList<String> uploaded = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> deleted = new CopyOnWriteArrayList<>();
        volatile boolean failUpload;
        volatile boolean loseUploadResponse;
        volatile boolean failDelete;
        @Override public void upload(String path, byte[] pdf) {
            if (failUpload) throw new StorageUnavailableException();
            objects.put(path, pdf);
            uploaded.add(path);
            if (loseUploadResponse) throw new StorageUnavailableException();
        }
        @Override public void delete(String path) {
            if (failDelete) throw new StorageUnavailableException();
            objects.remove(path); deleted.add(path);
        }
        @Override public String signedUrl(String path, long ttlSeconds) { return "https://storage.invalid/signed"; }
    }

    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @MockitoSpyBean ExtractionJobRepository jobs;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeStorage storage;
    @Autowired ExtractionWorker worker;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void uploadValidatesInputOwnershipCsrfAndDuplicateBeforeStorage() throws Exception {
        String ownerEmail = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        String materialId = null;
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String ownerId = members.findByEmail(ownerEmail).orElseThrow().getId();
            String otherId = members.findByEmail(otherEmail).orElseThrow().getId();
            Subject subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0), "PDF upload"));
            String ownerToken = tokens.issue(ownerId);
            String otherToken = tokens.issue(otherId);
            String csrf = csrfCookie();
            byte[] pdf = "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII);
            int initialUploads = storage.uploaded.size();

            assertEquals(403, upload(null, csrf, subject.getId(), "1", "Lesson", pdf, "application/pdf").statusCode());
            assertEquals(403, upload(ownerToken, null, subject.getId(), "1", "Lesson", pdf, "application/pdf").statusCode());
            assertEquals(403, upload(otherToken, csrf, subject.getId(), "1", "Lesson", pdf, "application/pdf").statusCode());
            assertEquals(400, upload(ownerToken, csrf, subject.getId(), "1.5", "Lesson", pdf, "application/pdf").statusCode());
            assertEquals(400, upload(ownerToken, csrf, subject.getId(), "0", "Lesson", pdf, "application/pdf").statusCode());
            assertEquals(400, upload(ownerToken, csrf, subject.getId(), "1", " ", pdf, "application/pdf").statusCode());
            assertEquals(400, upload(ownerToken, csrf, subject.getId(), "1", "Lesson", pdf, "text/plain").statusCode());
            assertEquals(400, upload(ownerToken, csrf, subject.getId(), "1", "Lesson", "not pdf".getBytes(StandardCharsets.US_ASCII), "application/pdf").statusCode());
            assertEquals(initialUploads, storage.uploaded.size());

            var created = upload(ownerToken, csrf, subject.getId(), "1", " Lesson ", pdf, "application/pdf");
            assertEquals(200, created.statusCode());
            var result = JsonMapper.builder().build().readTree(created.body());
            materialId = result.get("id").stringValue();
            assertEquals("Lesson", result.get("title").stringValue());
            assertEquals("users/" + ownerId + "/materials/" + materialId + ".pdf",
                result.get("storagePath").stringValue());
            assertTrue(materials.findByIdAndSubject_Grade_User_Id(materialId, ownerId).isPresent());
            var queued = jobs.findByMaterial_IdOrderByCreatedAtDesc(materialId);
            assertEquals(1, queued.size());
            assertEquals("PENDING", queued.get(0).getStatus());
            assertEquals(0, queued.get(0).getAttempts());
            assertEquals(3, queued.get(0).getMaxAttempts());
            assertEquals(initialUploads + 1, storage.uploaded.size());
            assertEquals(409, upload(ownerToken, csrf, subject.getId(), "1", "Duplicate", pdf, "application/pdf").statusCode());
            assertEquals(1, jobs.findByMaterial_IdOrderByCreatedAtDesc(materialId).size());
            assertEquals(initialUploads + 1, storage.uploaded.size());

            storage.failUpload = true;
            assertEquals(503, upload(ownerToken, csrf, subject.getId(), "2", "Next", pdf, "application/pdf").statusCode());
            assertFalse(materials.existsBySubject_IdAndWeek(subject.getId(), 2));
            storage.failUpload = false;
        } finally {
            storage.failUpload = false;
            cleanup(ownerEmail);
            cleanup(otherEmail);
        }
    }

    @Test
    void failureAfterJobInsertRollsBackBothRowsAndCompensatesUploadedObject() throws Exception {
        String email = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        var insertedJobId = new java.util.concurrent.atomic.AtomicReference<String>();
        var insertedMaterialId = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            registration.register(email, "synthetic-password");
            String ownerId = members.findByEmail(email).orElseThrow().getId();
            Subject subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0), "Job rollback"));
            // * 실제 MySQL에 자료와 Job을 flush한 뒤 실패시켜 같은 트랜잭션의 롤백을 검증한다.
            doAnswer(invocation -> {
                ExtractionJob job = invocation.getArgument(0);
                entityManager.persist(job);
                entityManager.flush();
                insertedJobId.set(job.getId());
                insertedMaterialId.set(job.getMaterial().getId());
                throw new DataIntegrityViolationException("synthetic job persistence failure");
            }).when(jobs).saveAndFlush(any(ExtractionJob.class));
            int beforeUploads = storage.uploaded.size();
            int beforeDeletes = storage.deleted.size();
            var response = upload(tokens.issue(ownerId), csrfCookie(), subject.getId(), "1", "Rollback",
                "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII), "application/pdf");
            assertEquals(500, response.statusCode());
            assertEquals("자료를 저장하지 못했습니다. 다시 시도해 주세요.",
                JsonMapper.builder().build().readTree(response.body()).get("error").stringValue());
            assertNotNull(insertedJobId.get());
            assertFalse(jobs.existsById(insertedJobId.get()));
            assertFalse(materials.existsById(insertedMaterialId.get()));
            assertEquals(beforeUploads + 1, storage.uploaded.size());
            assertEquals(beforeDeletes + 1, storage.deleted.size());
            assertEquals(storage.uploaded.get(beforeUploads), storage.deleted.get(beforeDeletes));
        } finally {
            reset(jobs);
            cleanup(email);
        }
    }

    @Test
    void lostUploadResponseCompensatesPossibleRemoteObject() throws Exception {
        String email = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(email, "synthetic-password");
            String ownerId = members.findByEmail(email).orElseThrow().getId();
            Subject subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0), "Lost upload response"));
            int beforeUploads = storage.uploaded.size();
            int beforeDeletes = storage.deleted.size();
            // * 저장소가 객체를 만들고 응답만 잃은 경우 DB 롤백과 해당 객체 보상을 함께 확인한다.
            storage.loseUploadResponse = true;
            var response = upload(tokens.issue(ownerId), csrfCookie(), subject.getId(), "1", "Lost response",
                "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII), "application/pdf");
            assertEquals(503, response.statusCode());
            assertFalse(materials.existsBySubject_IdAndWeek(subject.getId(), 1));
            assertEquals(beforeUploads + 1, storage.uploaded.size());
            assertEquals(beforeDeletes + 1, storage.deleted.size());
            String path = storage.uploaded.get(beforeUploads);
            assertEquals(path, storage.deleted.get(beforeDeletes));
            assertFalse(storage.objects.containsKey(path));
        } finally {
            storage.loseUploadResponse = false;
            cleanup(email);
        }
    }

    @Test
    void uploadWorkerStatusAndOwnerFileLinkFormOneFlow() throws Exception {
        // ! 공유 개발 DB의 기존 사용자 작업을 worker가 선점할 수 있으면 합성 시험을 시작하지 않는다.
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM extraction_job WHERE status IN ('PENDING', 'RUNNING')", Integer.class));
        String ownerEmail = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(ownerEmail, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String ownerId = members.findByEmail(ownerEmail).orElseThrow().getId();
            String otherId = members.findByEmail(otherEmail).orElseThrow().getId();
            Subject subject = subjects.saveAndFlush(new Subject(
                grades.findByUser_IdOrderByYearAscTermAsc(ownerId).get(0), "PDF flow"));
            var uploaded = upload(tokens.issue(ownerId), csrfCookie(), subject.getId(), "1", "PDF flow",
                samplePdf(), "application/pdf");
            assertEquals(200, uploaded.statusCode());
            String materialId = JsonMapper.builder().build().readTree(uploaded.body()).get("id").stringValue();
            assertEquals("PENDING", JsonMapper.builder().build().readTree(
                status(tokens.issue(ownerId), materialId).body()).get("status").stringValue());
            assertEquals(403, status(tokens.issue(otherId), materialId).statusCode());
            assertTrue(worker.runOnce());
            var done = JsonMapper.builder().build().readTree(status(tokens.issue(ownerId), materialId).body());
            assertEquals("DONE", done.get("status").stringValue());
            assertEquals(1, done.get("attempts").intValue());
            assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM material_page_text WHERE material_id = ? AND text LIKE ?",
                Integer.class, materialId, "%Synthetic PDF flow%"));
            assertEquals(403, fileUrl(tokens.issue(otherId), materialId).statusCode());
            var signed = fileUrl(tokens.issue(ownerId), materialId);
            assertEquals(200, signed.statusCode());
            assertTrue(JsonMapper.builder().build().readTree(signed.body())
                .get("signedUrl").stringValue().startsWith("https://"));
        } finally {
            cleanup(ownerEmail);
            cleanup(otherEmail);
        }
    }

    private byte[] samplePdf() throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
             var out = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Synthetic PDF flow");
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private HttpResponse<String> status(String jwt, String materialId) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/materials/status?materialId=" + materialId))
            .header("Cookie", "token=" + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> fileUrl(String jwt, String materialId) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/materials/file_url?materialId=" + materialId))
            .header("Cookie", "token=" + jwt).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrfCookie() throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        return response.headers().allValues("set-cookie").stream()
            .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
            .findFirst().orElseThrow().split(";", 2)[0];
    }

    @Test
    void metadataPatchValidatesOwnershipInputAndDuplicateWeek() throws Exception {
        String email = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(email, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            String other = members.findByEmail(otherEmail).orElseThrow().getId();
            var subject = subjects.saveAndFlush(new Subject(grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0), "Metadata"));
            var material = materials.saveAndFlush(new com.onrelearn.material.entity.Material(subject, 1, "Before", "synthetic/metadata.pdf"));
            materials.saveAndFlush(new com.onrelearn.material.entity.Material(subject, 2, "Occupied", "synthetic/occupied.pdf"));
            String token = tokens.issue(owner);
            String csrf = csrfCookie();
            assertEquals(403, patchMaterial(tokens.issue(other), csrf, material.getId(), "{\"title\":\"Other\"}").statusCode());
            assertEquals(403, patchMaterial(token, null, material.getId(), "{\"title\":\"No csrf\"}").statusCode());
            for (String invalid : List.of("{}", "{\"title\":null}", "{\"title\":\" \"}", "{\"week\":1.5}", "{\"week\":0}")) {
                assertEquals(400, patchMaterial(token, csrf, material.getId(), invalid).statusCode());
            }
            assertEquals(409, patchMaterial(token, csrf, material.getId(), "{\"title\":\"Rollback\",\"week\":2}").statusCode());
            assertEquals("Before", materials.findById(material.getId()).orElseThrow().getTitle());
            assertEquals(200, patchMaterial(token, csrf, material.getId(), "{\"title\":\" After \",\"week\":3}").statusCode());
            var updated = materials.findById(material.getId()).orElseThrow();
            assertEquals("After", updated.getTitle());
            assertEquals(3, updated.getWeek());
            assertEquals("synthetic/metadata.pdf", updated.getStoragePath());
        } finally { cleanup(email); cleanup(otherEmail); }
    }

    private HttpResponse<String> patchMaterial(String token, String csrf, String id, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/materials/" + id)).header("Content-Type", "application/json")
            .header("Cookie", "token=" + token + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deleteChecksOwnershipAndRunningJobAndRetriesStorageFailure() throws Exception {
        String email = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "material-upload-" + UUID.randomUUID() + "@example.invalid";
        try {
            registration.register(email, "synthetic-password");
            registration.register(otherEmail, "synthetic-password");
            String owner = members.findByEmail(email).orElseThrow().getId();
            String other = members.findByEmail(otherEmail).orElseThrow().getId();
            var subject = subjects.saveAndFlush(new Subject(grades.findByUser_IdOrderByYearAscTermAsc(owner).get(0), "Deletion"));
            String token = tokens.issue(owner);
            String csrf = csrfCookie();
            var created = upload(token, csrf, subject.getId(), "1", "Delete me",
                "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII), "application/pdf");
            assertEquals(200, created.statusCode());
            var body = JsonMapper.builder().build().readTree(created.body());
            String id = body.get("id").stringValue();
            String path = body.get("storagePath").stringValue();
            assertEquals(403, deleteMaterial(tokens.issue(other), csrf, id).statusCode());
            assertEquals(403, deleteMaterial(token, null, id).statusCode());
            var job = jobs.findByMaterial_IdOrderByCreatedAtDesc(id).get(0);
            job.claim("synthetic-delete", java.time.Instant.now(), java.time.Instant.EPOCH);
            jobs.saveAndFlush(job);
            assertEquals(409, deleteMaterial(token, csrf, id).statusCode());
            assertTrue(materials.existsById(id));
            assertTrue(storage.objects.containsKey(path));
            job.complete("synthetic-delete", java.time.Instant.now());
            jobs.saveAndFlush(job);
            jdbc.update("INSERT INTO material_page_text (id, material_id, page, text, char_count, status, extracted_at, updated_at) "
                + "VALUES (?, ?, 1, '', 0, 'DONE', NOW(6), NOW(6))", UUID.randomUUID().toString(), id);
            var graph = seedDeletionGraph(id, owner);
            var retained = materials.saveAndFlush(new Material(subject, 2, "Retained", "users/synthetic/retained.pdf"));
            var retainedGraph = seedDeletionGraph(retained.getId(), owner);
            // ! DB 삭제가 실패하면 삭제 기록도 롤백하고 외부 객체에는 접근하지 않는다.
            doThrow(new DataIntegrityViolationException("synthetic deletion failure"))
                .when(jobs).deleteAll(org.mockito.ArgumentMatchers.<ExtractionJob>anyList());
            assertEquals(500, deleteMaterial(token, csrf, id).statusCode());
            reset(jobs);
            assertTrue(materials.existsById(id));
            assertTrue(storage.objects.containsKey(path));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_deletion WHERE material_id = ?", Integer.class, id));
            // ! 자식 삭제가 일부 실행된 뒤 실패해도 문항·풀이·답안·노트·요약까지 모두 복원돼야 한다.
            doThrow(new DataIntegrityViolationException("synthetic content deletion failure")).when(contentDeletes).deleteNotes(id);
            try { assertEquals(500, deleteMaterial(token, csrf, id).statusCode()); }
            finally { reset(contentDeletes); }
            assertGraph(graph, true);
            assertGraph(retainedGraph, true);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_deletion WHERE material_id=?", Integer.class, id));
            assertTrue(storage.objects.containsKey(path));
            storage.failDelete = true;
            assertEquals(503, deleteMaterial(token, csrf, id).statusCode());
            assertFalse(materials.existsById(id));
            assertTrue(jobs.findByMaterial_IdOrderByCreatedAtDesc(id).isEmpty());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_page_text WHERE material_id = ?", Integer.class, id));
            assertGraph(graph, false);
            assertGraph(retainedGraph, true);
            assertEquals(path, jdbc.queryForObject("SELECT storage_path FROM material_deletion WHERE material_id = ?", String.class, id));
            assertTrue(storage.objects.containsKey(path));
            assertEquals(403, deleteMaterial(tokens.issue(other), csrf, id).statusCode());
            var pendingList = pendingDeletions(token);
            assertEquals(200, pendingList.statusCode());
            assertTrue(pendingList.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            var pendingBody = JsonMapper.builder().build().readTree(pendingList.body());
            assertEquals(1, pendingBody.size());
            assertEquals(id, pendingBody.get(0).get("materialId").stringValue());
            assertNotNull(pendingBody.get(0).get("requestedAt"));
            assertEquals(2, pendingBody.get(0).size());
            assertFalse(pendingList.body().contains(path));
            assertEquals("[]", pendingDeletions(tokens.issue(other)).body());
            assertEquals(401, pendingDeletions(null).statusCode());
            storage.failDelete = false;
            assertEquals(200, deleteMaterial(token, csrf, id).statusCode());
            assertFalse(storage.objects.containsKey(path));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_deletion WHERE material_id = ?", Integer.class, id));
            assertEquals("[]", pendingDeletions(token).body());

            var pending = upload(token, csrf, subject.getId(), "1", "Pending",
                "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII), "application/pdf");
            assertEquals(200, pending.statusCode());
            String pendingId = JsonMapper.builder().build().readTree(pending.body()).get("id").stringValue();
            assertEquals(200, deleteMaterial(token, csrf, pendingId).statusCode());
            assertFalse(materials.existsById(pendingId));
            assertTrue(jobs.findByMaterial_IdOrderByCreatedAtDesc(pendingId).isEmpty());
            assertTrue(materials.existsById(retained.getId()));
            assertGraph(retainedGraph, true);
            // * 과목 삭제도 같은 자식 삭제 경계를 사용하며 과거 달성 기록은 보존한다.
            var deleteSubject = HttpRequest.newBuilder(uri("/api/subjects"))
                .header("Content-Type", "application/json").header("Cookie", "token=" + token + "; " + csrf)
                .header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()))
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"subjectId\":\"" + subject.getId() + "\"}")).build();
            assertEquals(200, http.send(deleteSubject, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertFalse(subjects.existsById(subject.getId()));
            assertFalse(materials.existsById(retained.getId()));
            assertGraph(retainedGraph, false);
            assertGraph(graph, false);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempt WHERE user_id=?", Integer.class, owner));
        } finally {
            storage.failDelete = false;
            reset(jobs);
            reset(contentDeletes);
            cleanup(email); cleanup(otherEmail);
        }
    }

    private HttpResponse<String> deleteMaterial(String token, String csrf, String id) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/materials/" + id))
            .header("Cookie", "token=" + token + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> pendingDeletions(String token) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/materials/deletions")).GET();
        if (token != null) request.header("Cookie", "token=" + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String jwt, String csrf, String subjectId, String week,
        String title, byte[] file, String mime) throws Exception {
        String boundary = "onrelearn-test-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        part(body, boundary, "subjectId", subjectId);
        part(body, boundary, "week", week);
        part(body, boundary, "title", title);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.pdf\"\r\n"
            + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri("/api/materials"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (jwt != null) request.header("Cookie", "token=" + jwt + (csrf == null ? "" : "; " + csrf));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()));
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private void part(java.io.ByteArrayOutputStream body, String boundary, String name, String value) throws Exception {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
            + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private java.util.Map<String, String> seedDeletionGraph(String materialId, String owner) {
        var ids = new java.util.LinkedHashMap<String, String>();
        for (String table : new String[]{"note", "material_summary", "quiz_set", "quiz_item", "quiz_attempt", "quiz_answer", "material_study_day"}) ids.put(table, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO note(id,material_id,page,content,created_at,updated_at) VALUES (?,?,1,'synthetic',NOW(6),NOW(6))", ids.get("note"), materialId);
        jdbc.update("INSERT INTO material_summary(id,material_id,source_hash,content,created_at,updated_at) VALUES (?,?,'synthetic','synthetic',NOW(6),NOW(6))", ids.get("material_summary"), materialId);
        jdbc.update("INSERT INTO quiz_set(id,material_id,source_hash,purpose,created_at) VALUES (?,?,'synthetic','GENERAL',NOW(6))", ids.get("quiz_set"), materialId);
        jdbc.update("INSERT INTO quiz_item(id,quiz_set_id,item_order,type,question,answer_key,points,created_at) VALUES (?,?,1,'tf','synthetic','true',1,NOW(6))", ids.get("quiz_item"), ids.get("quiz_set"));
        jdbc.update("INSERT INTO quiz_attempt(id,user_id,quiz_set_id,status,started_at,submitted_at,graded_at,score,max_score) VALUES (?,?,?,'GRADED',NOW(6),NOW(6),NOW(6),1,1)", ids.get("quiz_attempt"), owner, ids.get("quiz_set"));
        jdbc.update("INSERT INTO quiz_answer(id,attempt_id,quiz_item_id,response,is_correct,earned,created_at,updated_at) VALUES (?,?,?,'true',TRUE,1,NOW(6),NOW(6))", ids.get("quiz_answer"), ids.get("quiz_attempt"), ids.get("quiz_item"));
        String progress = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO quiz_attempt(id,user_id,quiz_set_id,status,started_at) VALUES (?,?,?,'IN_PROGRESS',NOW(6))", progress, owner, ids.get("quiz_set"));
        ids.put("progress", progress);
        jdbc.update("INSERT INTO material_study_day(id,user_id,material_id,study_date,first_at,last_at) VALUES (?,?,?,CURRENT_DATE(),NOW(6),NOW(6))", ids.get("material_study_day"), owner, materialId);
        return ids;
    }

    private void assertGraph(java.util.Map<String, String> ids, boolean exists) {
        for (var entry : ids.entrySet()) {
            String table = entry.getKey().equals("progress") ? "quiz_attempt" : entry.getKey();
            assertEquals(table.equals("material_study_day") || exists ? 1 : 0,
                jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, entry.getValue()), table);
        }
    }

    private void cleanup(String email) {
        // ! 생성한 합성 회원만 정확한 이메일 패턴으로 정리한다.
        assertTrue(email.matches("material-upload-[0-9a-f-]{36}@example\\.invalid"));
        jdbc.query("SELECT d.storage_path FROM material_deletion d JOIN app_user u ON u.id = d.owner_id WHERE u.email = ?",
            (row, ignored) -> storage.objects.remove(row.getString(1)), email);
        jdbc.update("DELETE d FROM material_deletion d JOIN app_user u ON u.id = d.owner_id WHERE u.email = ?", email);
        jdbc.update("DELETE p FROM material_page_text p JOIN material m ON m.id = p.material_id "
            + "JOIN subject s ON s.id = m.subject_id JOIN grade g ON g.id = s.grade_id "
            + "JOIN app_user u ON u.id = g.user_id WHERE u.email = ?", email);
        jdbc.query("SELECT m.storage_path FROM material m JOIN subject s ON s.id = m.subject_id "
            + "JOIN grade g ON g.id = s.grade_id JOIN app_user u ON u.id = g.user_id "
            + "WHERE u.email = ?", (row, ignored) -> storage.objects.remove(row.getString(1)), email);
        for (String id : jdbc.queryForList("SELECT m.id FROM material m JOIN subject s ON s.id=m.subject_id JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", String.class, email)) SyntheticMaterialCleanup.delete(jdbc, id);
        jdbc.update("DELETE j FROM extraction_job j JOIN material m ON m.id = j.material_id "
            + "JOIN subject s ON s.id = m.subject_id JOIN grade g ON g.id = s.grade_id "
            + "JOIN app_user u ON u.id = g.user_id WHERE u.email = ?", email);
        jdbc.update("DELETE m FROM material m JOIN subject s ON s.id = m.subject_id "
            + "JOIN grade g ON g.id = s.grade_id JOIN app_user u ON u.id = g.user_id WHERE u.email = ?", email);
        // ! 합성 계정의 자식부터 정리해 FK RESTRICT에서도 정상/오류 시험 결과를 보존한다.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material m JOIN subject s ON s.id=m.subject_id JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", Integer.class, email));
        jdbc.update("DELETE s FROM subject s JOIN grade g ON g.id=s.grade_id JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
        jdbc.update("DELETE g FROM grade g JOIN app_user u ON u.id=g.user_id WHERE u.email=?", email);
        jdbc.update("DELETE d FROM material_study_day d JOIN app_user u ON u.id=d.user_id WHERE u.email=?", email);
        jdbc.update("DELETE FROM app_user WHERE email = ?", email);
        assertFalse(members.existsByEmail(email));
    }
}
