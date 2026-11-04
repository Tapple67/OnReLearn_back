package com.onrelearn.profile;

import com.onrelearn.auth.service.CurrentMemberService.CurrentMember;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quiz/recent")
public class RecentQuizController {
    private final RecentQuizService recent;

    public RecentQuizController(RecentQuizService recent) {
        this.recent = recent;
    }

    @GetMapping
    public ResponseEntity<List<RecentQuizService.RecentQuiz>> get(
            @AuthenticationPrincipal CurrentMember member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(recent.get(member.id()));
    }
}
