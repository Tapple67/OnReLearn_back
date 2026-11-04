package com.onrelearn.subject.service;

import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubjectWorkspaceService {
    private final GradeRepository grades;
    private final SubjectRepository subjects;

    public SubjectWorkspaceService(GradeRepository grades, SubjectRepository subjects) {
        this.grades = grades;
        this.subjects = subjects;
    }

    @Transactional(readOnly = true)
    public Workspace view(String memberId, String requestedGradeId, String requestedSubjectId) {
        // * URL 선택값을 소유 목록과 대조한 뒤 다음 단계의 자료 조회로 전달할 ID를 확정한다.
        List<Grade> ownedGrades = grades.findByUser_IdOrderByYearAscTermAsc(memberId);
        Grade selectedGrade = requestedGradeId == null
            ? ownedGrades.stream().filter(Grade::isCurrent).findFirst()
                .orElse(ownedGrades.isEmpty() ? null : ownedGrades.get(0))
            : ownedGrades.stream().filter(grade -> grade.getId().equals(requestedGradeId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 학기를 찾을 수 없습니다."));
        List<Subject> ownedSubjects = selectedGrade == null ? List.of()
            : subjects.findByGrade_IdOrderByCreatedAtDesc(selectedGrade.getId());
        // ! 다른 학기의 과목이나 타인 과목은 존재 여부를 노출하지 않고 조회를 중단한다.
        Subject selectedSubject = requestedSubjectId == null
            ? (ownedSubjects.isEmpty() ? null : ownedSubjects.get(0))
            : ownedSubjects.stream().filter(subject -> subject.getId().equals(requestedSubjectId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 과목을 찾을 수 없습니다."));
        return new Workspace(
            ownedGrades.stream().map(grade -> new GradeItem(grade.getId(), grade.getYear(), grade.getTerm(), grade.isCurrent())).toList(),
            ownedSubjects.stream().map(subject -> new SubjectItem(subject.getId(), subject.getName(), selectedGrade.getId())).toList(),
            selectedGrade == null ? null : selectedGrade.getId(),
            selectedSubject == null ? null : selectedSubject.getId()
        );
    }

    public record Workspace(List<GradeItem> grades, List<SubjectItem> subjects,
                            String selectedGradeId, String selectedSubjectId) {}
    public record GradeItem(String id, int year, int term, boolean current) {}
    public record SubjectItem(String id, String name, String gradeId) {}
}
