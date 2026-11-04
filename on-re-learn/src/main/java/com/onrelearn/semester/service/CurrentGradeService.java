package com.onrelearn.semester.service;

import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.repository.GradeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentGradeService {
    private final MemberRepository members;
    private final GradeRepository grades;

    public CurrentGradeService(MemberRepository members, GradeRepository grades) {
        this.members = members;
        this.grades = grades;
    }

    @Transactional(readOnly = true)
    public List<GradeSummary> list(String memberId) {
        return grades.findByUser_IdOrderByYearAscTermAsc(memberId).stream()
            .map(grade -> new GradeSummary(grade.getId(), grade.getYear(), grade.getTerm(), grade.isCurrent()))
            .toList();
    }

    @Transactional
    public boolean select(String memberId, String gradeId) {
        // ! 회원 행 잠금으로 같은 회원의 동시 변경 요청을 직렬화해 현재 학기 하나를 유지한다.
        members.findByIdForUpdate(memberId).orElseThrow(GradeNotFoundException::new);
        var target = grades.findByIdAndUser_Id(gradeId, memberId).orElseThrow(GradeNotFoundException::new);
        if (target.isCurrent()) return false;
        grades.clearCurrentForUser(memberId);
        target.setCurrent(true);
        grades.saveAndFlush(target);
        return true;
    }

    public record GradeSummary(String id, int year, int term, boolean current) {}
}
