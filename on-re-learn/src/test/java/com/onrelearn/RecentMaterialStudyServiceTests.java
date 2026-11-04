package com.onrelearn;

import com.onrelearn.activity.entity.MaterialStudyDay;
import com.onrelearn.activity.repository.MaterialStudyDayRepository;
import com.onrelearn.activity.service.RecentMaterialStudyService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.subject.entity.Subject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecentMaterialStudyServiceTests {
    @Test void keepsDeletedFactButProvidesLinkOnlyForOwnedLiveMaterial() {
        var days = mock(MaterialStudyDayRepository.class);
        var materials = mock(MaterialRepository.class);
        var service = new RecentMaterialStudyService(days, materials);
        var when = Instant.parse("2026-10-22T00:00:00Z");
        var live = new MaterialStudyDay("u", "live", LocalDate.of(2026, 10, 22), when);
        var deleted = new MaterialStudyDay("u", "deleted", LocalDate.of(2026, 10, 21), when);
        when(days.findTop20ByUserIdOrderByLastAtDescIdDesc("u")).thenReturn(List.of(live, deleted));
        var material = mock(Material.class);
        var subject = mock(Subject.class);
        when(material.getSubject()).thenReturn(subject);
        when(subject.getId()).thenReturn("s");
        when(material.getTitle()).thenReturn("자료");
        when(materials.findByIdAndSubject_Grade_User_Id("live", "u")).thenReturn(Optional.of(material));
        var recent = service.get("u");
        assertEquals(2, recent.size());
        assertEquals("s", recent.get(0).subjectId());
        assertEquals("자료", recent.get(0).title());
        assertNull(recent.get(1).subjectId());
        assertEquals("삭제된 자료", recent.get(1).title());
        verify(days, never()).delete(any());
    }
}
