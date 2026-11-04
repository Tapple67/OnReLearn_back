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
class NoteReadMysqlTests {
    @Autowired RegistrationService registration;
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired com.onrelearn.note.repository.NoteRepository notes;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void readsOnlyOwnedNotesAndDistinguishesMissingPage() throws Exception {
        String ownerEmail = "note-read-" + UUID.randomUUID() + "@example.invalid";
        String otherEmail = "note-read-" + UUID.randomUUID() + "@example.invalid";
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
            var note = notes.saveAndFlush(new com.onrelearn.note.entity.Note(material, 1, "한글 노트\n "));
            String jwt = tokens.issue(ownerId);
            String query = "materialId=" + materialId;
            assertEquals(401, get(null, query).statusCode());
            assertEquals(403, get(tokens.issue(otherId), query).statusCode());
            assertEquals(403, get(jwt, "materialId=" + UUID.randomUUID()).statusCode());
            for (String invalid : new String[] {"", "materialId=", query + "&page=0", query + "&page=-1",
                    query + "&page=1.5", query + "&page=abc", query + "&page=2147483648"}) {
                assertEquals(400, get(jwt, invalid).statusCode());
            }
            var response = get(jwt, query);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            var data = JsonMapper.builder().build().readTree(response.body()).get("note");
            assertEquals(note.getId(), data.get("id").stringValue());
            assertEquals("한글 노트\n ", data.get("content").stringValue());
            assertEquals(1, data.get("page").intValue());
            assertNotNull(data.get("updatedAt"));
            assertNull(data.get("signals"));
            assertNull(data.get("material"));
            var empty = get(jwt, query + "&page=2");
            assertEquals(200, empty.statusCode());
            assertTrue(JsonMapper.builder().build().readTree(empty.body()).get("note").isNull());
            assertEquals(403, get(tokens.issue(otherId), query + "&page=2").statusCode());
            notes.saveAndFlush(new com.onrelearn.note.entity.Note(material, 2, " \n "));
            var pages = send(jwt, "/api/notes/pages?" + query);
            assertEquals(200, pages.statusCode());
            var listed = JsonMapper.builder().build().readTree(pages.body()).get("pages");
            assertEquals(1, listed.size());
            assertEquals(1, listed.get(0).intValue());
            assertEquals(401, send(null, "/api/notes/pages?" + query).statusCode());
            assertEquals(403, send(tokens.issue(otherId), "/api/notes/pages?" + query).statusCode());
            assertEquals(400, send(jwt, "/api/notes/pages").statusCode());
        } finally {
            // ! 이 시험에서 생성한 자료와 계정만 정리한다. 노트는 자료 FK로 함께 정리된다.
            if (materialId != null) jdbc.update("DELETE FROM material WHERE id = ?", materialId);
            for (String email : new String[] {ownerEmail, otherEmail}) {
                assertTrue(email.matches("note-read-[0-9a-f-]{36}@example\\.invalid"));
                jdbc.update("DELETE FROM app_user WHERE email = ?", email);
                assertFalse(members.existsByEmail(email));
            }
        }
    }

    private HttpResponse<String> get(String jwt, String query) throws Exception {
        return send(jwt, "/api/notes?" + query);
    }

    private HttpResponse<String> send(String jwt, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (jwt != null) request.header("Cookie", "token=" + jwt);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
