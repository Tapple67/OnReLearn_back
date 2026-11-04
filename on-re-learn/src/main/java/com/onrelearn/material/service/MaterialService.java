package com.onrelearn.material.service;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.entity.ExtractionJob;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaterialService {
    private final SubjectRepository subjects;
    private final MaterialRepository materials;
    private final ExtractionJobRepository jobs;
    private final PdfObjectStorage storage;
    private final TransactionTemplate transactions;
    private final long maxBytes;
    private final long signedUrlTtlSeconds;

    public MaterialService(SubjectRepository subjects, MaterialRepository materials,
                           ExtractionJobRepository jobs,
                           PdfObjectStorage storage, PlatformTransactionManager transactionManager,
                           @Value("${onrelearn.material.max-bytes:20971520}") long maxBytes,
                           @Value("${onrelearn.storage.supabase.signed-url-ttl-seconds:600}") long signedUrlTtlSeconds) {
        this.subjects = subjects;
        this.materials = materials;
        this.jobs = jobs;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxBytes = maxBytes;
        this.signedUrlTtlSeconds = signedUrlTtlSeconds;
    }

    @Transactional(readOnly = true)
    public List<MaterialSummary> list(String memberId, String subjectId) {
        // ! 자료 목록은 과목 소유권을 먼저 확인한다. 자료 ID만으로 접근을 허용하지 않는다.
        if (subjects.findByIdAndGrade_User_Id(subjectId, memberId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "과목이 없거나 권한이 없습니다.");
        }
        // * 기존 화면의 주차순 자료 목록 계약에 필요한 필드만 반환한다.
        return materials.findBySubject_IdOrderByWeekAscCreatedAtAsc(subjectId).stream()
            .map(item -> new MaterialSummary(item.getId(), item.getWeek(), item.getTitle(), item.getStoragePath()))
            .toList();
    }

    public record MaterialSummary(String id, int week, String title, String storagePath) {}

    public MaterialSummary update(String memberId, String materialId, String title, Integer week) {
        if ((title == null && week == null) || (title != null && (title.isBlank() || title.trim().length() > 255))
            || (week != null && week < 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목 또는 주차가 올바르지 않습니다.");
        }
        try {
            return transactions.execute(status -> {
                String subjectId = materials.findOwnedSubjectId(materialId, memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "자료가 없거나 권한이 없습니다."));
                // ! 업로드와 같은 과목 잠금을 사용해 주차 중복 검증과 변경을 직렬화한다.
                subjects.findOwnedForUpdate(subjectId, memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "과목이 없거나 권한이 없습니다."));
                var material = materials.findByIdAndSubject_Grade_User_Id(materialId, memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "자료가 없거나 권한이 없습니다."));
                if (week != null && week != material.getWeek()
                    && materials.existsBySubject_IdAndWeek(subjectId, week)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 주차의 자료가 있습니다.");
                }
                if (title != null) material.rename(title);
                if (week != null) material.moveToWeek(week);
                materials.flush();
                return new MaterialSummary(material.getId(), material.getWeek(), material.getTitle(), material.getStoragePath());
            });
        } catch (DataAccessException exception) {
            if (isDuplicateWeek(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 주차의 자료가 있습니다.");
            }
            throw new MaterialPersistenceException(exception);
        }
    }

    @Transactional(readOnly = true)
    public ProcessingStatus processingStatus(String memberId, String materialId) {
        // ! 작업 ID만으로 조회하면 다른 사용자의 처리 현황을 알아낼 수 있으므로 자료 소유권부터 검사한다.
        var material = materials.findByIdAndSubject_Grade_User_Id(materialId, memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "자료가 없거나 권한이 없습니다."));
        var history = jobs.findByMaterial_IdOrderByCreatedAtDesc(material.getId());
        if (history.isEmpty()) return new ProcessingStatus(material.getId(), "NOT_SCHEDULED", 0, 0);
        var latest = history.get(0);
        return new ProcessingStatus(material.getId(), latest.getStatus(), latest.getAttempts(),
            latest.getMaxAttempts());
    }

    public record ProcessingStatus(String materialId, String status, int attempts, int maxAttempts) {}

    @Transactional(readOnly = true)
    public String signedUrl(String memberId, String materialId) {
        // ! 타인 자료의 Storage 경로가 서명 API로 전달되지 않도록 소유권으로 조회한다.
        var material = materials.findByIdAndSubject_Grade_User_Id(materialId, memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "자료가 없거나 권한이 없습니다."));
        return storage.signedUrl(material.getStoragePath(), signedUrlTtlSeconds);
    }

    public UploadedMaterial upload(String memberId, String subjectId, int week, String title, byte[] pdf) {
        validateUpload(week, title, pdf);
        String[] uploadedPath = {null};
        try {
            // ! 과목 행 잠금으로 같은 주차의 동시 요청을 직렬화하고 외부 객체 저장 후 DB 커밋 실패도 보상한다.
            return transactions.execute(status -> {
                var subject = subjects.findOwnedForUpdate(subjectId, memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "과목이 없거나 권한이 없습니다."));
                if (materials.existsBySubject_IdAndWeek(subjectId, week)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 주차의 자료가 있습니다.");
                }
                Material material = Material.forPdfUpload(subject, week, title.trim(), memberId);
                // ! 원격 업로드가 성공한 뒤 응답만 유실될 수 있어 호출 전부터 보상 대상 경로를 추적한다.
                uploadedPath[0] = material.getStoragePath();
                storage.upload(uploadedPath[0], pdf);
                materials.saveAndFlush(material);
                // * 자료와 추출 작업을 함께 커밋한다. Job 저장 실패도 자료 롤백과 객체 보상을 거친다.
                jobs.saveAndFlush(new ExtractionJob(material, 0, 3));
                return new UploadedMaterial(material.getId(), subjectId, week, material.getTitle(),
                    material.getStoragePath());
            });
        } catch (RuntimeException exception) {
            if (uploadedPath[0] != null) {
                // ! DB rollback만으로 외부 객체는 지워지지 않는다. 삭제 실패도 숨기지 않는다.
                try { storage.delete(uploadedPath[0]); }
                catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                    throw new com.onrelearn.material.storage.StorageUnavailableException(exception);
                }
            }
            if (isDuplicateWeek(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 주차의 자료가 있습니다.");
            }
            // ! Job/FK 등의 DB 실패를 주차 중복으로 숨기지 않고 공통 처리기에서 안전한 500으로 변환한다.
            if (exception instanceof DataAccessException) throw new MaterialPersistenceException(exception);
            throw exception;
        }
    }

    private boolean isDuplicateWeek(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if ("uk_material_subject_week".equals(name)
                    || (name != null && name.endsWith(".uk_material_subject_week"))) return true;
            }
        }
        return false;
    }

    private void validateUpload(int week, String title, byte[] pdf) {
        if (week < 1 || title == null || title.isBlank() || title.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "주차 또는 제목이 올바르지 않습니다.");
        }
        if (pdf == null || pdf.length < 5 || pdf.length > maxBytes
            || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-') {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 PDF 파일이 필요합니다.");
        }
    }

    public record UploadedMaterial(String id, String subjectId, int week, String title, String storagePath) {}
}
