package com.onrelearn.summary.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.summary.service.MaterialSummaryService;
import com.onrelearn.summary.service.SummaryJobs;
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
    private final SummaryJobs jobs;

    public MaterialSummaryController(MaterialSummaryService summaries, SummaryJobs jobs) {
        this.summaries = summaries;
        this.jobs = jobs;
    }

    @GetMapping("/job")
    public ResponseEntity<SummaryJobs.Status> job(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestParam(name = "materialId", required = false) String materialId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.status(member.id(), materialId));
    }

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
        // ! Authorize before committing stream headers. Starting joins a running job instead of generating twice.
        var job=jobs.start(member.id(),id);
        // * This request only watches: a closed window ends the watching, and the job still finishes and saves.
        com.onrelearn.shared.error.GenerationStream.run(response, events -> {
            var result=job.follow(text -> events.accept(Map.of("type","delta","value",text)));
            return new GenerateResponse(true,result.summaryId(),result.sourceHash(),result.content(),result.adaptive(),result.reused());
        });
    }

    public record ReadResponse(MaterialSummaryService.View summary) {}
    public record GenerateResponse(boolean ok, String summaryId, String sourceHash,
                                   String content, String adaptive, boolean reused) {
        @Override public String toString() { return "GenerateResponse[content omitted]"; }
    }
}
