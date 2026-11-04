package com.onrelearn.stats;

import com.onrelearn.auth.service.CurrentMemberService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats/material")
public class MaterialStatsController {
    private final MaterialStatsService statistics;

    public MaterialStatsController(MaterialStatsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping
    public ResponseEntity<MaterialStats.Response> get(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member,
            @RequestParam(name = "materialId", required = false) String materialId) {
        // ! Only the authenticated owner may receive per-material learning statistics.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(statistics.get(member.id(), materialId));
    }
}
