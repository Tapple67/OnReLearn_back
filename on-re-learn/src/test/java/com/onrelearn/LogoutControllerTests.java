package com.onrelearn;

import com.onrelearn.auth.controller.LogoutController;
import com.onrelearn.auth.service.AuthCookieService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LogoutControllerTests {
    @Test
    void clearsAuthAndCsrfCookiesWithoutExposingTheirValues() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new LogoutController(new AuthCookieService(false))).build();
        var response = mvc.perform(post("/api/auth/logout"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"ok\":true}"))
            .andReturn().getResponse();

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, cookies.size());
        assertTrue(cookies.get(0).startsWith("token=;"));
        assertTrue(cookies.get(0).contains("HttpOnly"));
        assertTrue(cookies.get(0).contains("Max-Age=0"));
        assertTrue(cookies.get(0).contains("Path=/"));
        assertTrue(cookies.get(1).startsWith("XSRF-TOKEN=;"));
        assertTrue(cookies.get(1).contains("Max-Age=0"));
    }

    @Test
    void secureSettingAlsoAppliesWhenClearingCookies() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new LogoutController(new AuthCookieService(true))).build();
        var response = mvc.perform(post("/api/auth/logout")).andExpect(status().isOk()).andReturn().getResponse();
        for (String cookie : response.getHeaders(HttpHeaders.SET_COOKIE)) assertTrue(cookie.contains("Secure"));
    }
}
