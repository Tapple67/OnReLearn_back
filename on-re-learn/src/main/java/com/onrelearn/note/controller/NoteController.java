package com.onrelearn.note.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.note.service.NoteService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notes")
public class NoteController {
    private final NoteService notes;

    public NoteController(NoteService notes) { this.notes = notes; }

    @GetMapping
    public ResponseEntity<NoteResponse> get(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "materialId", required = false) String materialId,
        @RequestParam(name = "page", defaultValue = "1") int page) {
        // * 원본의 {note:null}을 유지해 미작성 페이지와 조회 실패를 구별한다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new NoteResponse(notes.get(member.id(), materialId, page)));
    }

    @GetMapping("/pages")
    public ResponseEntity<PagesResponse> pages(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestParam(name = "materialId", required = false) String materialId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new PagesResponse(notes.pagesWithContent(member.id(), materialId)));
    }

    @PostMapping
    public ResponseEntity<SaveResponse> save(
        @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
        @RequestBody java.util.Map<String, Object> body) {
        // ! 누락·잘못된 타입을 빈 내용으로 변환하면 기존 노트를 지울 수 있으므로 거부한다.
        if (body == null || !(body.get("materialId") instanceof String materialId)
            || !(body.get("content") instanceof String content)
            || (body.containsKey("page") && !(body.get("page") instanceof Integer))) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "메모 입력이 올바르지 않습니다.");
        }
        int page = body.containsKey("page") ? (Integer) body.get("page") : 1;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new SaveResponse(true, notes.save(member.id(), materialId, page, content)));
    }

    public record SaveResponse(boolean ok, NoteService.NoteView note) {}
    public record NoteResponse(NoteService.NoteView note) {}
    public record PagesResponse(java.util.List<Integer> pages) {}
}
