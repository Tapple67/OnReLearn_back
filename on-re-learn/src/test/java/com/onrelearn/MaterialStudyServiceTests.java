package com.onrelearn;

import com.onrelearn.activity.entity.MaterialStudyDay;
import com.onrelearn.activity.repository.MaterialStudyDayRepository;
import com.onrelearn.activity.service.MaterialStudyService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaterialStudyServiceTests {
    @Test void ownerLockAndSeoulDayUpsert() {
        var materials = mock(MaterialRepository.class);
        var subjects = mock(SubjectRepository.class);
        var days = mock(MaterialStudyDayRepository.class);
        var service = new MaterialStudyService(materials, subjects, days);
        var first = Instant.parse("2026-10-21T15:00:00Z");
        assertEquals(401, status(() -> service.record(null, "m", first)));
        assertEquals(400, status(() -> service.record("u", " ", first)));
        assertEquals(403, status(() -> service.record("u", "m", first)));
        verify(days, never()).saveAndFlush(any());

        when(materials.findOwnedSubjectId("m", "u")).thenReturn(Optional.of("s"));
        when(subjects.findOwnedForUpdate("s", "u")).thenReturn(Optional.of(mock(Subject.class)));
        assertEquals(403, status(() -> service.record("u", "m", first)));
        when(materials.findByIdAndSubject_Grade_User_Id("m", "u"))
            .thenReturn(Optional.of(mock(Material.class)));

        service.record("u", " m ", first);
        var saved = org.mockito.ArgumentCaptor.forClass(MaterialStudyDay.class);
        verify(days).saveAndFlush(saved.capture());
        assertEquals(LocalDate.of(2026, 10, 22), saved.getValue().getStudyDate());
        assertEquals(first, saved.getValue().getFirstAt());

        when(days.findByUserIdAndMaterialIdAndStudyDate("u", "m", LocalDate.of(2026, 10, 22)))
            .thenReturn(Optional.of(saved.getValue()));
        var later = first.plusSeconds(3600);
        service.record("u", "m", later);
        assertEquals(first, saved.getValue().getFirstAt());
        assertEquals(later, saved.getValue().getLastAt());
        verify(days, times(2)).saveAndFlush(saved.getValue());
    }

    private int status(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode().value();
    }
}
