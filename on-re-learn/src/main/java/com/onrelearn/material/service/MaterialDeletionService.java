package com.onrelearn.material.service;

import com.onrelearn.material.entity.MaterialDeletion;
import com.onrelearn.material.repository.MaterialDeletionRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.subject.repository.SubjectRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaterialDeletionService {
    private final MaterialRepository materials;
    private final SubjectRepository subjects;
    private final ExtractionJobRepository jobs;
    private final MaterialDeletionRepository deletions;
    private final PdfObjectStorage storage;
    private final TransactionTemplate transactions;
    private final MaterialContentDeletionService contents;

    public MaterialDeletionService(MaterialRepository materials, SubjectRepository subjects,
        ExtractionJobRepository jobs, MaterialDeletionRepository deletions, PdfObjectStorage storage,
        PlatformTransactionManager manager, MaterialContentDeletionService contents) {
        this.materials = materials;
        this.subjects = subjects;
        this.jobs = jobs;
        this.deletions = deletions;
        this.storage = storage;
        this.transactions = new TransactionTemplate(manager);
        this.contents = contents;
    }

    public void delete(String ownerId, String materialId) {
        try {
            MaterialDeletion receipt = transactions.execute(status -> {
                var pending = deletions.findByMaterialIdAndOwnerId(materialId, ownerId);
                if (pending.isPresent()) return pending.get();
                String subjectId = materials.findOwnedSubjectId(materialId, ownerId)
                    .orElseThrow(MaterialDeletionService::forbidden);
                subjects.findOwnedForUpdate(subjectId, ownerId).orElseThrow(MaterialDeletionService::forbidden);
                var material = materials.findByIdAndSubject_Grade_User_Id(materialId, ownerId)
                    .orElseThrow(MaterialDeletionService::forbidden);
                // ! 선점과 같은 Job 행 잠금으로 삭제 직전 RUNNING 전환을 막는다.
                var history = jobs.lockByMaterialId(materialId);
                if (history.stream().anyMatch(job -> "RUNNING".equals(job.getStatus()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "PDF 처리 중입니다. 완료 후 삭제해 주세요.");
                }
                var saved = deletions.saveAndFlush(new MaterialDeletion(materialId, ownerId, material.getStoragePath()));
                jobs.deleteAll(history);
                jobs.flush();
                contents.deleteForMaterial(materialId);
                materials.delete(material);
                materials.flush();
                return saved;
            });
            // ! DB 커밋 후 객체를 삭제한다. 실패하면 기록을 남겨 같은 DELETE 요청으로 재시도한다.
            storage.delete(receipt.getStoragePath());
            deletions.deleteById(materialId);
        } catch (DataAccessException exception) {
            throw new MaterialPersistenceException(exception);
        }
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<PendingDeletion> pending(String ownerId) {
        // ! 요청자가 전달한 사용자 ID 대신 인증된 소유자의 기록만 조회하고 저장소 경로는 반환하지 않는다.
        return deletions.findByOwnerIdOrderByCreatedAtAscMaterialIdAsc(ownerId).stream()
            .map(item -> new PendingDeletion(item.getMaterialId(), item.getCreatedAt())).toList();
    }

    public record PendingDeletion(String materialId, java.time.Instant requestedAt) {}

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "자료가 없거나 권한이 없습니다.");
    }
}
