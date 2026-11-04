package com.onrelearn;

import com.onrelearn.material.entity.ExtractionJob;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.repository.MaterialDeletionRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.material.storage.StorageUnavailableException;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.subject.service.SubjectService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubjectDeletionServiceTests {
    @Test void commitsAllRowsBeforeDeletingFile() {
        var setup = setup();
        setup.service.delete("owner", "subject");
        var order = inOrder(setup.subjects, setup.materials, setup.manager, setup.storage,
            setup.deletions, setup.contents);
        order.verify(setup.contents).deleteForMaterial("material");
        order.verify(setup.materials).deleteAll(List.of(setup.material));
        order.verify(setup.subjects).delete(setup.subject);
        order.verify(setup.manager).commit(any());
        order.verify(setup.storage).delete("owner/file.pdf");
        order.verify(setup.deletions).deleteById("material");
    }

    @Test void runningJobBlocksAllDeletesAndStorage() {
        var setup = setup();
        var running = mock(ExtractionJob.class);
        when(running.getStatus()).thenReturn("RUNNING");
        when(setup.jobs.lockByMaterialId("material")).thenReturn(List.of(running));
        var error = assertThrows(ResponseStatusException.class,
            () -> setup.service.delete("owner", "subject"));
        assertEquals(409, error.getStatusCode().value());
        verify(setup.manager).rollback(any());
        verify(setup.materials, never()).deleteAll(any());
        verifyNoInteractions(setup.storage);
        verifyNoInteractions(setup.contents);
    }

    @Test void storageFailureKeepsRetryReceiptAfterCommittedDelete() {
        var setup = setup();
        doThrow(new StorageUnavailableException()).when(setup.storage).delete("owner/file.pdf");
        var error = assertThrows(ResponseStatusException.class,
            () -> setup.service.delete("owner", "subject"));
        assertEquals(503, error.getStatusCode().value());
        verify(setup.manager).commit(any());
        verify(setup.deletions, never()).deleteById(any());
    }

    @Test void contentFailureRollsBackBeforeFilesAreTouched() {
        var setup = setup();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic"))
            .when(setup.contents).deleteForMaterial("material");
        assertThrows(com.onrelearn.material.service.MaterialPersistenceException.class,
            () -> setup.service.delete("owner", "subject"));
        verify(setup.manager).rollback(any());
        verifyNoInteractions(setup.storage);
        verify(setup.materials, never()).deleteAll(any());
    }

    private Setup setup() {
        var grades = mock(GradeRepository.class);
        var subjects = mock(SubjectRepository.class);
        var materials = mock(MaterialRepository.class);
        var jobs = mock(ExtractionJobRepository.class);
        var deletions = mock(MaterialDeletionRepository.class);
        var storage = mock(PdfObjectStorage.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var subject = mock(Subject.class);
        var material = mock(Material.class);
        when(material.getId()).thenReturn("material");
        when(material.getStoragePath()).thenReturn("owner/file.pdf");
        when(subjects.findOwnedForUpdate("subject", "owner")).thenReturn(Optional.of(subject));
        when(materials.findBySubject_IdOrderByWeekAscCreatedAtAsc("subject"))
            .thenReturn(List.of(material));
        when(jobs.lockByMaterialId("material")).thenReturn(List.of());
        when(jobs.findByMaterial_IdOrderByCreatedAtDesc("material")).thenReturn(List.of());
        var contents = mock(com.onrelearn.material.service.MaterialContentDeletionService.class);
        return new Setup(new SubjectService(grades, subjects, materials, jobs, deletions, storage,
            manager, contents), subjects, materials, jobs, deletions, storage, manager, subject, material, contents);
    }

    private record Setup(SubjectService service, SubjectRepository subjects,
        MaterialRepository materials, ExtractionJobRepository jobs,
        MaterialDeletionRepository deletions, PdfObjectStorage storage,
        PlatformTransactionManager manager, Subject subject, Material material,
        com.onrelearn.material.service.MaterialContentDeletionService contents) {}
}
