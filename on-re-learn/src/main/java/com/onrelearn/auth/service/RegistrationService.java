package com.onrelearn.auth.service;

import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final MemberRepository members;
    private final GradeRepository grades;
    private final PasswordEncoder passwords;

    public RegistrationService(MemberRepository members, GradeRepository grades, PasswordEncoder passwords) {
        this.members = members;
        this.grades = grades;
        this.passwords = passwords;
    }

    public boolean emailExists(String email) {
        return members.existsByEmail(email);
    }

    @Transactional
    public void register(String email, String password) {
        // * 회원과 기본 12개 학기는 하나의 트랜잭션으로 저장해 부분 가입을 막는다.
        if (members.existsByEmail(email)) throw new DuplicateEmailException();

        Member member = members.saveAndFlush(new Member(email, passwords.encode(password)));
        List<Grade> defaults = new ArrayList<>(12);
        for (int year = 1; year <= 6; year++) {
            for (int term = 1; term <= 2; term++) {
                defaults.add(new Grade(member, year, term, year == 1 && term == 1));
            }
        }
        grades.saveAllAndFlush(defaults);
    }
}
