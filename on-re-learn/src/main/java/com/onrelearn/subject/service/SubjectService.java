package com.onrelearn.subject.service;

import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.repository.MaterialDeletionRepository;
import com.onrelearn.material.entity.MaterialDeletion;
import com.onrelearn.material.service.MaterialPersistenceException;
import com.onrelearn.material.service.MaterialContentDeletionService;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubjectService {
    private final GradeRepository grades;
    private final SubjectRepository subjects;
    private final MaterialRepository materials;
    private final ExtractionJobRepository jobs;
    private final MaterialDeletionRepository deletions;
    private final PdfObjectStorage storage;
    private final TransactionTemplate transactions;
    private final MaterialContentDeletionService contents;

    public SubjectService(GradeRepository grades, SubjectRepository subjects, MaterialRepository materials,
        ExtractionJobRepository jobs, MaterialDeletionRepository deletions, PdfObjectStorage storage,
        PlatformTransactionManager manager, MaterialContentDeletionService contents) {
        this.grades = grades;
        this.subjects = subjects;
        this.materials = materials;
        this.jobs = jobs;
        this.deletions = deletions;
        this.storage = storage;
        this.transactions = new TransactionTemplate(manager);
        this.contents = contents;
    }

    @Transactional(readOnly = true)
    public List<SubjectSummary> list(String memberId, String gradeId) {
        // * 기존 목록 API처럼 소유하지 않은 학기는 빈 목록을 반환한다.
        if (grades.findByIdAndUser_Id(gradeId, memberId).isEmpty()) return List.of();
        return subjects.findByGrade_IdOrderByCreatedAtDesc(gradeId).stream()
            .map(subject -> new SubjectSummary(subject.getId(), subject.getName(), gradeId, List.of()))
            .toList();
    }

    @Transactional
    public CreatedSubject create(String memberId, String gradeId, String name) {
        // ! 클라이언트의 학기 ID를 그대로 신뢰하지 않고 회원 소유권을 먼저 확인한다.
        var grade = grades.findByIdAndUser_Id(gradeId, memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "유효하지 않은 학기입니다."));
        Subject subject = subjects.save(Subject.builder().grade(grade).name(name).build());
        return new CreatedSubject(subject.getId(), subject.getName(), gradeId);
    }

    @Transactional
    public UpdatedSubject rename(String memberId, String subjectId, String name) {
        // ! 과목 ID 단독 조회를 피하고 회원 소유권을 조건으로 확인한다.
        var subject = subjects.findByIdAndGrade_User_Id(subjectId, memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없거나 과목이 없습니다."));
        subject.rename(name);
        return new UpdatedSubject(subject.getId(), subject.getName());
    }

    public void delete(String memberId, String subjectId) {
        List<MaterialDeletion> pending;
        try {
            pending = transactions.execute(status -> {
                var subject = subjects.findOwnedForUpdate(subjectId, memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없거나 과목이 없습니다."));
                var ownedMaterials = materials.findBySubject_IdOrderByWeekAscCreatedAtAsc(subjectId);
                // ! 한 자료라도 추출 중이면 모든 DB 삭제를 취소한다.
                for (var material : ownedMaterials) {
                    var history = jobs.lockByMaterialId(material.getId());
                    if (history.stream().anyMatch(job -> "RUNNING".equals(job.getStatus()))) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "PDF 처리 중입니다. 완료 후 삭제해 주세요.");
                    }
                }
                var receipts = ownedMaterials.stream()
                    .map(material -> new MaterialDeletion(material.getId(), memberId, material.getStoragePath()))
                    .toList();
                deletions.saveAllAndFlush(receipts);
                for (var material : ownedMaterials) {
                    jobs.deleteAll(jobs.findByMaterial_IdOrderByCreatedAtDesc(material.getId()));
                }
                jobs.flush();
                for (var material : ownedMaterials) contents.deleteForMaterial(material.getId());
                materials.deleteAll(ownedMaterials);
                materials.flush();
                subjects.delete(subject);
                subjects.flush();
                return receipts;
            });
        } catch (DataAccessException exception) {
            throw new MaterialPersistenceException(exception);
        }
        // ! DB 커밋 후 파일을 지운다. 실패한 파일의 기록은 계정 전체 자료 삭제 패널에서 재시도한다.
        boolean storageFailed = false;
        for (var receipt : pending) {
            try {
                storage.delete(receipt.getStoragePath());
                deletions.deleteById(receipt.getMaterialId());
            } catch (RuntimeException exception) {
                storageFailed = true;
            }
        }
        if (storageFailed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "과목은 삭제됐지만 일부 자료 삭제가 남았습니다. 미완료 자료 삭제에서 다시 시도해 주세요.");
        }
    }

    public record SubjectSummary(String id, String name, String gradeId, List<Object> materials) {}
    public record CreatedSubject(String id, String name, String gradeId) {}
    public record UpdatedSubject(String id, String name) {}
}
