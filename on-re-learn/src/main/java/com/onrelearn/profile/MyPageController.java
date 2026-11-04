package com.onrelearn.profile;

import com.onrelearn.auth.service.CurrentMemberService.CurrentMember;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mypage")
public class MyPageController {
    private final MyPageService myPage;

    public MyPageController(MyPageService myPage) {
        this.myPage = myPage;
    }

    @GetMapping
    public ResponseEntity<MyPageService.View> get(@AuthenticationPrincipal CurrentMember member) {
        // ! The current user comes from verified authentication, never a caller-supplied owner ID.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(myPage.get(member, Instant.now()));
    }
}
