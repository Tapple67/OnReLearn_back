package com.onrelearn.home.service;

import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeService {
    private final GradeRepository grades;
    private final SubjectRepository subjects;

    public HomeService(GradeRepository grades, SubjectRepository subjects) {
        this.grades = grades;
        this.subjects = subjects;
    }

    @Transactional(readOnly = true)
    public HomeOverview overview(String memberId) {
        // * 현재 사용자에게 속한 학기만 읽고, 현재 학기의 과목만 홈에 표시한다.
        List<Grade> ownedGrades = grades.findByUser_IdOrderByYearAscTermAsc(memberId);
        Grade current = ownedGrades.stream().filter(Grade::isCurrent).findFirst().orElse(null);
        CurrentGrade currentGrade = current == null ? null : new CurrentGrade(
            current.getId(), current.getYear(), current.getTerm(),
            subjects.findByGrade_IdOrderByCreatedAtDesc(current.getId()).stream()
                .map(subject -> new SubjectSummary(subject.getId(), subject.getName())).toList());
        return new HomeOverview(currentGrade, ownedGrades.stream()
            .map(grade -> new GradeSummary(grade.getId(), grade.getYear(), grade.getTerm(), grade.isCurrent()))
            .toList());
    }

    public record HomeOverview(CurrentGrade currentGrade, List<GradeSummary> grades) {}
    public record CurrentGrade(String id, int year, int term, List<SubjectSummary> subjects) {}
    public record SubjectSummary(String id, String name) {}
    public record GradeSummary(String id, int year, int term, boolean current) {}
}
