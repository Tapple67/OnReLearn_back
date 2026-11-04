package com.onrelearn.summary.service;

import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.repository.MaterialPageTextRepository;
import com.onrelearn.note.repository.NoteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SummaryInputService {
    private final MaterialRepository materials;
    private final MaterialPageTextRepository pages;
    private final NoteRepository notes;
    private final JsonMapper json = JsonMapper.builder().build();

    public SummaryInputService(MaterialRepository materials, MaterialPageTextRepository pages, NoteRepository notes) {
        this.materials = materials;
        this.pages = pages;
        this.notes = notes;
    }

    // * One read snapshot joins saved notes and extracted text; provider calls happen after this transaction.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SummaryInput build(String memberId, String materialId, String promptVersion) {
        if (memberId == null || memberId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (materialId == null || materialId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material ID is required");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("Summary prompt version is required");
        }
        // ! Check ownership before reading any page text or private note content.
        var material = materials.findByIdAndSubject_Grade_User_Id(materialId.trim(), memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Material unavailable"));
        var texts = pages.findByMaterial_IdOrderByPageAsc(material.getId()).stream()
                .map(page -> new SummaryInput.Text(page.getPage(), page.getText())).toList();
        var savedNotes = notes.findByMaterial_IdOrderByPageAsc(material.getId()).stream()
                .map(note -> new SummaryInput.Note(note.getPage(), note.getContent(),
                        note.getSignals() == null ? null : json.readTree(note.getSignals()), note.getUpdatedAt())).toList();
        return SummaryInput.assemble(material.getId(), material.getSubject().getId(), material.getWeek(),
                material.getTitle(), promptVersion, texts, savedNotes);
    }
}
