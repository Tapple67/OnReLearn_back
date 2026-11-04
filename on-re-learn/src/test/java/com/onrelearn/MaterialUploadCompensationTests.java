package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.service.MaterialService;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaterialUploadCompensationTests {
    @Test
    void committedObjectIsDeletedWhenDbSaveFails() {
        SubjectRepository subjects = mock(SubjectRepository.class);
        MaterialRepository materials = mock(MaterialRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(subjects.findOwnedForUpdate("subject", "owner")).thenReturn(Optional.of(mock(Subject.class)));
        when(materials.saveAndFlush(any(Material.class)))
            .thenThrow(new DataIntegrityViolationException("synthetic unique failure",
                new org.hibernate.exception.ConstraintViolationException("duplicate", new java.sql.SQLException(), "uk_material_subject_week")));
        var storage = new RecordingStorage();
        var jobs = mock(ExtractionJobRepository.class);
        var service = new MaterialService(subjects, materials, jobs, storage, transactions, 1024, 600);

        var error = assertThrows(ResponseStatusException.class,
            () -> service.upload("owner", "subject", 1, "Lesson",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(1, storage.uploaded.size());
        assertEquals(storage.uploaded, storage.deleted);

        var limited = new MaterialService(subjects, materials, jobs, storage, transactions, 5, 600);
        var tooLarge = assertThrows(ResponseStatusException.class,
            () -> limited.upload("owner", "subject", 1, "Lesson",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(400, tooLarge.getStatusCode().value());
        assertEquals(1, storage.uploaded.size());
    }

    static class RecordingStorage implements PdfObjectStorage {
        @Override public byte[] download(String path, long maxBytes) { throw new UnsupportedOperationException("No download in upload test"); }
        final List<String> uploaded = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        @Override public void upload(String path, byte[] pdf) { uploaded.add(path); }
        @Override public void delete(String path) { deleted.add(path); }
        @Override public String signedUrl(String path, long ttlSeconds) { return "unused"; }
    }
}
