package com.onrelearn.home.controller;

import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.home.service.HomeService;
import com.onrelearn.home.service.HomeService.HomeOverview;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {
    private final HomeService home;

    public HomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/api/home")
    public HomeOverview overview(@AuthenticationPrincipal CurrentMemberService.CurrentMember member) {
        // ! 회원 ID는 요청 파라미터가 아닌 검증된 JWT 주체에서만 받는다.
        return home.overview(member.id());
    }
}
