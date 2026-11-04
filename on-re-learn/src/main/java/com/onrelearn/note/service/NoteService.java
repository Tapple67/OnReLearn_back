package com.onrelearn.note.service;

import com.onrelearn.activity.service.MaterialStudyService;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.note.repository.NoteRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NoteService {
    private final MaterialRepository materials;
    private final NoteRepository notes;
    private final com.onrelearn.subject.repository.SubjectRepository subjects;
    private final MaterialStudyService study;

    public NoteService(MaterialRepository materials, NoteRepository notes,
                       com.onrelearn.subject.repository.SubjectRepository subjects,
                       MaterialStudyService study) {
        this.materials = materials;
        this.notes = notes;
        this.subjects = subjects;
        this.study = study;
    }

    @Transactional(readOnly = true)
    public NoteView get(String memberId, String materialId, int page) {
        if (materialId == null || materialId.isBlank() || page < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자료 ID 또는 페이지가 올바르지 않습니다.");
        }
        // ! 노트가 없는 페이지도 자료 소유권을 확인한 뒤 응답한다.
        var material = materials.findByIdAndSubject_Grade_User_Id(materialId.trim(), memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없거나 자료가 없습니다."));
        return notes.findByMaterial_IdAndPage(material.getId(), page)
            .map(note -> new NoteView(note.getId(), note.getContent(), note.getPage(), note.getUpdatedAt()))
            .orElse(null);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public NoteView save(String memberId, String materialId, int page, String content) {
        if (materialId == null || materialId.isBlank() || page < 1 || content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "노트 입력이 올바르지 않습니다.");
        }
        String id = materialId.trim();
        String subjectId = materials.findOwnedSubjectId(id, memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없거나 자료가 없습니다."));
        // ! 자료 삭제와 같은 잠금으로 최초 저장을 직렬화한다. 대기 후에는 최신 커밋된 노트를 읽는다.
        subjects.findOwnedForUpdate(subjectId, memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없거나 자료가 없습니다."));
        var material = materials.findByIdAndSubject_Grade_User_Id(id, memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없거나 자료가 없습니다."));
        var note = notes.findByMaterial_IdAndPage(id, page)
            .orElseGet(() -> new com.onrelearn.note.entity.Note(material, page, content));
        note.replaceContent(content);
        notes.saveAndFlush(note);
        // * Count only a successful note save; both writes commit or roll back together.
        study.record(memberId, id, Instant.now());
        return new NoteView(note.getId(), note.getContent(), note.getPage(), note.getUpdatedAt());
    }

    public record NoteView(String id, String content, int page, Instant updatedAt) {}
}
