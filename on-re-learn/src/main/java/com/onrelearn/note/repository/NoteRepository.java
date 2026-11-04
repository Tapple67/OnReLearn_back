package com.onrelearn.note.repository;

import com.onrelearn.note.entity.Note;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, String> {
    List<Note> findByMaterial_IdOrderByPageAsc(String materialId);
    Optional<Note> findByMaterial_IdAndPage(String materialId, int page);
}
