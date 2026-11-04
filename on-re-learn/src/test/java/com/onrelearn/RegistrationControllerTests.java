package com.onrelearn;

import com.onrelearn.auth.controller.RegistrationController;
import com.onrelearn.auth.service.DuplicateEmailException;
import com.onrelearn.auth.service.RegistrationService;
import com.onrelearn.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationControllerTests {
    @Test
    void mapsUniqueConstraintRaceToConflictButPropagatesUnrelatedConstraintFailures() throws Exception {
        RegistrationService registration = mock(RegistrationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registration))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        String email = "race@example.invalid";
        String request = "{\"email\":\"" + email + "\",\"password\":\"password\"}";
        doThrow(new DataIntegrityViolationException("synthetic constraint violation"))
            .when(registration).register(email, "password");
        when(registration.emailExists(email)).thenReturn(true, false);

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isInternalServerError())
            .andExpect(content().json("{\"error\":\"데이터를 처리하지 못했습니다. 다시 시도해 주세요.\"}"));
        verify(registration, times(2)).emailExists(email);
    }

    @Test
    void preservesSuccessAndDuplicateResponseContract() throws Exception {
        RegistrationService registration = mock(RegistrationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registration))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        String request = "{\"email\":\"learner@example.invalid\",\"password\":\"password\"}";

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"ok\":true}"));
        verify(registration).register("learner@example.invalid", "password");

        doThrow(new DuplicateEmailException()).when(registration)
            .register("learner@example.invalid", "password");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict())
            .andExpect(content().json("{\"error\":\"이미 존재하는 이메일\"}"));
    }

    @Test
    void rejectsMissingMalformedAndWronglyTypedInput() throws Exception {
        RegistrationService registration = mock(RegistrationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registration))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String body : new String[] {"null", "{}", "{\"email\":42,\"password\":\"x\"}",
                "{"}) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"입력 누락 또는 잘못된 형식\"}"));
        }
        verifyNoInteractions(registration);
    }

    @Test
    void identifiesInvalidEmailAndBlankPasswordWithoutCallingRegistration() throws Exception {
        RegistrationService registration = mock(RegistrationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registration))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String email : new String[]{"not-an-email", "a@host", " a@example.invalid", "a@example.invalid ", "a".repeat(243) + "@example.com"}) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"이메일 형식을 확인해 주세요. 앞뒤 공백 없이 name@example.com 형식으로 입력해 주세요.\"}"));
        }
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@example.invalid\",\"password\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().json("{\"error\":\"비밀번호는 공백만으로 입력할 수 없습니다.\"}"));
        verifyNoInteractions(registration);
    }
}
