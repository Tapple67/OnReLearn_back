package com.onrelearn;

import com.onrelearn.auth.config.JwtSettings;
import com.onrelearn.auth.controller.LoginController;
import com.onrelearn.auth.service.AuthCookieService;
import com.onrelearn.auth.service.AuthenticationService;
import com.onrelearn.auth.service.JwtTokenService;
import com.onrelearn.shared.error.ApiExceptionHandler;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginControllerTests {
    private static final String SECRET = "synthetic-test-secret-at-least-32-bytes-long";
    private static final String CREDENTIALS = "{\"email\":\"learner@example.invalid\",\"password\":\"password\"}";

    @Test
    void successfulLoginSetsHttpOnlySameSiteCookie() throws Exception {
        AuthenticationService authentication = mock(AuthenticationService.class);
        when(authentication.authenticate("learner@example.invalid", "password"))
            .thenReturn(Optional.of("synthetic-member-id"));
        JwtTokenService tokens = new JwtTokenService(new JwtSettings(SECRET, Duration.ofHours(3)));
        var mvc = MockMvcBuilders.standaloneSetup(new LoginController(authentication, tokens, new AuthCookieService(false)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        var response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
            .andExpect(status().isOk()).andExpect(content().json("{\"ok\":true}"))
            .andReturn().getResponse();
        String cookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("token="));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/"));
        assertFalse(cookie.contains("Secure"));
        assertEquals("synthetic-member-id", tokens.verify(cookie.substring(6, cookie.indexOf(';'))).orElseThrow());
    }

    @Test
    void invalidCredentialsAndInputNeverSetCookie() throws Exception {
        AuthenticationService authentication = mock(AuthenticationService.class);
        when(authentication.authenticate("learner@example.invalid", "password"))
            .thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new LoginController(authentication,
            new JwtTokenService(new JwtSettings(SECRET, Duration.ofHours(3))), new AuthCookieService(false)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        var invalid = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
            .andExpect(status().isUnauthorized())
            .andExpect(content().json("{\"error\":\"아이디 또는 비밀번호가 틀렸습니다.\"}"))
            .andReturn().getResponse();
        assertNull(invalid.getHeader(HttpHeaders.SET_COOKIE));
        for (String body : new String[] {"null", "{}", "{\"email\":42,\"password\":\"p\"}", "{"}) {
            var bad = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
            assertNull(bad.getHeader(HttpHeaders.SET_COOKIE));
        }
    }

    @Test
    void missingSigningKeyReturnsServiceUnavailableWithoutCookie() throws Exception {
        AuthenticationService authentication = mock(AuthenticationService.class);
        when(authentication.authenticate("learner@example.invalid", "password"))
            .thenReturn(Optional.of("synthetic-member-id"));
        var mvc = MockMvcBuilders.standaloneSetup(new LoginController(authentication,
            new JwtTokenService(new JwtSettings("", Duration.ofHours(3))), new AuthCookieService(false)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        var response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"error\":\"인증 설정을 확인해 주세요.\"}"))
            .andReturn().getResponse();
        assertNull(response.getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void secureCookieSettingAndJsonOnlyMappingAreEnforced() throws Exception {
        AuthenticationService authentication = mock(AuthenticationService.class);
        when(authentication.authenticate("learner@example.invalid", "password"))
            .thenReturn(Optional.of("synthetic-member-id"));
        var mvc = MockMvcBuilders.standaloneSetup(new LoginController(authentication,
            new JwtTokenService(new JwtSettings(SECRET, Duration.ofHours(3))), new AuthCookieService(true)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        String cookie = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
            .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("Secure"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content(CREDENTIALS))
            .andExpect(status().isUnsupportedMediaType());
    }
}
