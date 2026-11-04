package com.onrelearn.material.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.material.service.MaterialService;
import com.onrelearn.material.service.MaterialDeletionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    private final MaterialService materials;
    private final MaterialDeletionService deletions;

    public MaterialController(MaterialService materials, MaterialDeletionService deletions) {
        this.materials = materials;
        this.deletions = deletions;
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member, @PathVariable("id") String id) {
        deletions.delete(member.id(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/deletions")
    public ResponseEntity<List<MaterialDeletionService.PendingDeletion>> pendingDeletions(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member) {
        // * 자료가 목록에서 사라진 뒤에도 파일 삭제 실패를 확인하고 동일 DELETE로 재시도할 수 있다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deletions.pending(member.id()));
    }

    @GetMapping
    public List<MaterialService.MaterialSummary> list(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "subjectId", required = false) String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectId가 필요합니다.");
        }
        return materials.list(member.id(), subjectId.trim());
    }

    @GetMapping("/file_url")
    public ResponseEntity<Map<String, String>> fileUrl(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "materialId", required = false) String materialId) {
        if (materialId == null || materialId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "materialId가 필요합니다.");
        }
        // ! 서명 URL의 토큰이 브라우저·프록시 캐시에 남지 않도록 한다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("signedUrl", materials.signedUrl(member.id(), materialId.trim())));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        // * 부분 수정은 제공된 필드만 바꾸며 null·문자열 주차·소수는 입력 오류로 처리한다.
        if (body == null || (!body.containsKey("title") && !body.containsKey("week"))
            || (body.containsKey("title") && !(body.get("title") instanceof String))
            || (body.containsKey("week") && !(body.get("week") instanceof Integer))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목 또는 주차가 올바르지 않습니다.");
        }
        return Map.of("ok", true, "material", materials.update(member.id(), id,
            (String) body.get("title"), (Integer) body.get("week")));
    }

    @GetMapping("/status")
    public ResponseEntity<MaterialService.ProcessingStatus> status(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "materialId", required = false) String materialId) {
        if (materialId == null || materialId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "materialId가 필요합니다.");
        }
        // ! 작업 상태도 자료 소유권을 먼저 확인하며 브라우저·프록시에 캐시하지 않는다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(materials.processingStatus(member.id(), materialId.trim()));
    }

    @PostMapping(consumes = "multipart/form-data")
    public MaterialService.UploadedMaterial upload(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "subjectId", required = false) String subjectId,
        @RequestParam(name = "week", required = false) String week,
        @RequestParam(name = "title", required = false) String title,
        @RequestPart(name = "file", required = false) MultipartFile file) {
        // * multipart 입력을 HTTP 경계에서 검증한 뒤 Service가 소유권·DB·저장소 흐름을 처리한다.
        if (subjectId == null || subjectId.isBlank() || week == null
            || !week.matches("[1-9][0-9]*") || title == null || title.isBlank()
            || file == null || !"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자료 입력 또는 PDF 파일이 올바르지 않습니다.");
        }
        try {
            return materials.upload(member.id(), subjectId.trim(), Integer.parseInt(week), title,
                file.getBytes());
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "주차가 올바르지 않습니다.");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "업로드 파일을 읽을 수 없습니다.");
        }
    }
}
