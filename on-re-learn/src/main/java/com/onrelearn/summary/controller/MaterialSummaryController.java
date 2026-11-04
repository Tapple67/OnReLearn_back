package com.onrelearn.summary.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.summary.service.MaterialSummaryService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/summary/summaries")
public class MaterialSummaryController {
    private final MaterialSummaryService summaries;

    public MaterialSummaryController(MaterialSummaryService summaries) { this.summaries = summaries; }

    @GetMapping
    public ResponseEntity<ReadResponse> get(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestParam(name = "materialId", required = false) String materialId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ReadResponse(summaries.get(member.id(), materialId)));
    }

    @PostMapping
    public ResponseEntity<GenerateResponse> generate(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody Map<String, Object> body) {
        // ! Reject invalid JSON types rather than coercing them into another material ID.
        if (body == null || !(body.get("materialId") instanceof String materialId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material ID is required");
        }
        var result = summaries.ensure(member.id(), materialId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new GenerateResponse(
                true, result.summaryId(), result.sourceHash(), result.content(), result.adaptive(), result.reused()));
    }

    @PostMapping("/stream")
    public void stream(@AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestBody Map<String,Object> body, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        if (!(body.get("materialId") instanceof String id) || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Material ID is required");
        summaries.get(member.id(),id); // ! Authorize before committing stream headers.
        com.onrelearn.shared.error.GenerationStream.run(response, events -> {
            var result=summaries.ensure(member.id(),id,text -> events.accept(Map.of("type","delta","value",text)));
            return new GenerateResponse(true,result.summaryId(),result.sourceHash(),result.content(),result.adaptive(),result.reused());
        });
    }

    public record ReadResponse(MaterialSummaryService.View summary) {}
    public record GenerateResponse(boolean ok, String summaryId, String sourceHash,
                                   String content, String adaptive, boolean reused) {
        @Override public String toString() { return "GenerateResponse[content omitted]"; }
    }
}
