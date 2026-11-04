package com.onrelearn.activity.controller;

import com.onrelearn.activity.service.RecentMaterialStudyService;
import com.onrelearn.auth.service.CurrentMemberService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activity/material-study/recent")
public class RecentMaterialStudyController {
    private final RecentMaterialStudyService recent;

    public RecentMaterialStudyController(RecentMaterialStudyService recent) { this.recent = recent; }

    @GetMapping
    public ResponseEntity<List<RecentMaterialStudyService.RecentStudy>> get(
            @AuthenticationPrincipal CurrentMemberService.CurrentMember member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recent.get(member.id()));
    }
}
