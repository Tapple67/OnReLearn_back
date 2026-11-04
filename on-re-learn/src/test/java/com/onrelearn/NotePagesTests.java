package com.onrelearn;

import com.onrelearn.activity.service.MaterialStudyService;
import com.onrelearn.auth.service.CurrentMemberService;
import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.note.controller.NoteController;
import com.onrelearn.note.entity.Note;
import com.onrelearn.note.repository.NoteRepository;
import com.onrelearn.note.service.NoteService;
import com.onrelearn.shared.error.ApiExceptionHandler;
import com.onrelearn.subject.repository.SubjectRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotePagesTests {
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final NoteService service = new NoteService(materials, notes,
        mock(SubjectRepository.class), mock(MaterialStudyService.class));

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    private Material owned() {
        Material material = mock(Material.class);
        when(material.getId()).thenReturn("material");
        when(materials.findByIdAndSubject_Grade_User_Id("material", "owner")).thenReturn(Optional.of(material));
        return material;
    }

    @Test void listsOnlyPagesWhoseMemoHasContent() {
        Material material = owned();
        when(notes.findByMaterial_IdOrderByPageAsc("material")).thenReturn(List.of(
            new Note(material, 2, "메모"), new Note(material, 3, ""), new Note(material, 4, " \n "),
            new Note(material, 9, "다른 메모")));
        assertEquals(List.of(2, 9), service.pagesWithContent("owner", " material "));
    }

    @Test void otherMembersCannotLearnWhichPagesHaveMemos() {
        when(materials.findByIdAndSubject_Grade_User_Id("material", "other")).thenReturn(Optional.empty());
        var failure = assertThrows(ResponseStatusException.class, () -> service.pagesWithContent("other", "material"));
        assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
        verifyNoInteractions(notes);
    }

    @Test void blankMaterialIsRejected() {
        for (String blank : new String[] {null, "", "  "}) {
            var failure = assertThrows(ResponseStatusException.class, () -> service.pagesWithContent("owner", blank));
            assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        }
    }

    @Test void endpointBindsNamedParameterAndForbidsCaching() throws Exception {
        NoteService mocked = mock(NoteService.class);
        when(mocked.pagesWithContent("owner", "material")).thenReturn(List.of(3, 4));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentMemberService.CurrentMember("owner", "synthetic@example.invalid"), null, List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new NoteController(mocked))
            .setControllerAdvice(new ApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/notes/pages").param("materialId", "material"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.pages[0]").value(3))
            .andExpect(jsonPath("$.pages[1]").value(4));
    }
}
