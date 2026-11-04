package com.onrelearn;

import com.onrelearn.quiz.entity.QuizSet;
import com.onrelearn.quiz.repository.QuizSetRepository;
import com.onrelearn.quiz.service.QuizSetDeletionService;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuizSetDeletionServiceTests {
    @Test void checksOwnerAndSubjectLockBeforeDeletingSelectedSet() {
        var sets = mock(QuizSetRepository.class);
        var subjects = mock(SubjectRepository.class);
        var service = new QuizSetDeletionService(sets, subjects);
        assertEquals(401, status(() -> service.delete(null, "set")));
        assertEquals(400, status(() -> service.delete("owner", " ")));
        assertEquals(403, status(() -> service.delete("owner", "missing")));
        verify(sets, never()).delete(any());

        when(sets.findOwnedSubjectId("set", "owner")).thenReturn(Optional.of("subject"));
        assertEquals(403, status(() -> service.delete("owner", "set")));
        verify(sets, never()).delete(any());

        when(subjects.findOwnedForUpdate("subject", "owner")).thenReturn(Optional.of(mock(Subject.class)));
        assertEquals(403, status(() -> service.delete("owner", "set")));
        verify(sets, never()).delete(any());

        var selected = mock(QuizSet.class);
        when(sets.findByIdAndMaterial_Subject_Grade_User_Id("set", "owner"))
            .thenReturn(Optional.of(selected));
        service.delete("owner", " set ");
        verify(sets).delete(selected);
        verify(sets).flush();
    }

    private int status(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode().value();
    }
}
