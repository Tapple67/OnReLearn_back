package com.onrelearn;

import com.onrelearn.summary.service.MaterialSummaryService;
import com.onrelearn.summary.service.MaterialSummaryService.View;
import com.onrelearn.summary.service.SummaryJobs;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SummaryJobsTests {
    private static final View DONE = new View("id", "hash", "본문", "본문", false);
    private final MaterialSummaryService service = mock(MaterialSummaryService.class);
    private final SummaryJobs jobs = new SummaryJobs(service);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach void stop() { release.countDown(); jobs.shutdown(); }

    @SuppressWarnings("unchecked")
    private void generation(String... chunks) {
        when(service.ensure(anyString(), anyString(), any())).thenAnswer(call -> {
            Consumer<String> delta = call.getArgument(2);
            for (String chunk : chunks) delta.accept(chunk);
            release.await(5, TimeUnit.SECONDS);
            return DONE;
        });
    }

    @Test void closingTheWatcherDoesNotStopGeneration() throws Exception {
        generation("가", "나");
        var job = jobs.start("owner", "material");
        assertThrows(IllegalStateException.class, () -> job.follow(text -> { throw new IllegalStateException("연결 끊김"); }));
        assertEquals("running", jobs.status("owner", "material").status());
        release.countDown();
        assertEquals(DONE, job.follow(text -> {}));
        verify(service, times(1)).ensure(eq("owner"), eq("material"), any());
    }
    @Test void aSecondRequestJoinsTheRunningJobAndReceivesWhatWasMissed() throws Exception {
        generation("가", "나");
        var first = jobs.start("owner", "material");
        var second = jobs.start("owner", "material");
        assertSame(first, second);
        release.countDown();
        var received = new StringBuilder();
        assertEquals(DONE, second.follow(received::append));
        assertEquals("가나", received.toString());
        verify(service, times(1)).ensure(anyString(), anyString(), any());
    }
    @Test void statusShowsTheDraftWhileRunningAndIsIdleAfterSaving() throws Exception {
        generation("가", "나");
        var job = jobs.start("owner", "material");
        waitUntil(() -> jobs.status("owner", "material").draft().equals("가나"));
        assertEquals("running", jobs.status("owner", "material").status());
        release.countDown();
        job.follow(text -> {});
        assertEquals("idle", jobs.status("owner", "material").status());
    }
    @Test void aFailureReachesWatchersAndALaterRequestStartsAgain() {
        when(service.ensure(anyString(), anyString(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "실패"))
            .thenReturn(DONE);
        var failed = jobs.start("owner", "material");
        var failure = assertThrows(ResponseStatusException.class, () -> failed.follow(text -> {}));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertEquals("failed", jobs.status("owner", "material").status());
        assertDoesNotThrow(() -> assertEquals(DONE, jobs.start("owner", "material").follow(text -> {})));
        assertEquals("idle", jobs.status("owner", "material").status());
    }
    @Test void anotherMembersRequestDoesNotJoinAndIsCheckedForOwnership() {
        generation("가");
        var owner = jobs.start("owner", "material");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "거부")).when(service).get("other", "material");
        assertThrows(ResponseStatusException.class, () -> jobs.start("other", "material"));
        assertThrows(ResponseStatusException.class, () -> jobs.status("other", "material"));
        assertNotNull(owner);
        verify(service, never()).ensure(eq("other"), anyString(), any());
    }
    @Test void withNoJobTheStatusIsIdle() {
        var status = jobs.status("owner", "material");
        assertEquals("idle", status.status());
        assertEquals("", status.draft());
    }
    @Test void aFailedJobWhoseAuthorRetriesKeepsNoStaleDraft() throws Exception {
        var calls = new ArrayList<String>();
        when(service.ensure(anyString(), anyString(), any())).thenAnswer(call -> {
            Consumer<String> delta = call.getArgument(2);
            calls.add("run");
            delta.accept("회차" + calls.size());
            if (calls.size() == 1) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "실패");
            return DONE;
        });
        var failed = jobs.start("owner", "material");
        assertThrows(ResponseStatusException.class, () -> failed.follow(text -> {}));
        var received = new StringBuilder();
        jobs.start("owner", "material").follow(received::append);
        assertEquals("회차2", received.toString());
    }

    @Test void statusEndpointBindsItsNamedParameterAndForbidsCaching() throws Exception {
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            new com.onrelearn.auth.service.CurrentMemberService.CurrentMember("owner", "synthetic@example.invalid"), null, java.util.List.of()));
        try {
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.onrelearn.summary.controller.MaterialSummaryController(service, jobs))
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/summary/summaries/job").param("materialId", "material"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("idle"));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
