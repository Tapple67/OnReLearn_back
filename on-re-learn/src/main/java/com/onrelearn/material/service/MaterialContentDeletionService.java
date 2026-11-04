package com.onrelearn.material.service;

import com.onrelearn.material.repository.MaterialContentDeletionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialContentDeletionService {
    private final MaterialContentDeletionRepository contents;
    public MaterialContentDeletionService(MaterialContentDeletionRepository contents) { this.contents = contents; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForMaterial(String materialId) {
        // ! 호출자가 소유 과목과 Job을 잠근 트랜잭션 안에서만 실행한다. FK CASCADE 설정에 의존하지 않는다.
        contents.deleteAttemptAnswers(materialId);
        contents.deleteItemAnswers(materialId);
        contents.deleteAttempts(materialId);
        contents.deleteItems(materialId);
        contents.deleteSets(materialId);
        contents.deleteSummary(materialId);
        contents.deleteNotes(materialId);
        contents.deletePageTexts(materialId);
        // ! material_study_day는 과거 달성 근거이므로 삭제하지 않는다.
    }
}
