package com.onrelearn.auth.service;

import com.onrelearn.member.repository.MemberRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentMemberService {
    private final JwtTokenService tokens;
    private final MemberRepository members;

    public CurrentMemberService(JwtTokenService tokens, MemberRepository members) {
        this.tokens = tokens;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public Optional<CurrentMember> findByToken(String token) {
        // * 서명 검증 뒤 DB에서 현재 계정을 확인하므로 삭제된 회원의 JWT는 통과하지 못한다.
        return tokens.verify(token)
            .flatMap(members::findById)
            .map(member -> new CurrentMember(member.getId(), member.getEmail()));
    }

    public record CurrentMember(String id, String email) {}
}
