package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.service.ExtractionJobService;
import com.onrelearn.material.service.ExtractionJobService.Claim;
import com.onrelearn.material.service.ExtractionJobService.FailureCode;
import com.onrelearn.material.service.ExtractionLeaseLostException;
import com.onrelearn.material.service.ExtractionWorker;
import com.onrelearn.material.service.PdfTextExtractor;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.material.storage.StorageUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExtractionWorkerTests {
    private final ExtractionJobService jobs = mock(ExtractionJobService.class);
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final PdfObjectStorage storage = mock(PdfObjectStorage.class);
    private final PdfTextExtractor extractor = mock(PdfTextExtractor.class);
    private final ExtractionWorker worker = new ExtractionWorker(jobs, materials, storage, extractor, 100, "PT15M");

    @BeforeEach void resetMocks() {
        reset(jobs, materials, storage, extractor);
        when(jobs.renewalIntervalMillis()).thenReturn(60_000L);
    }

    @Test void idlePollDoesNotAccessStorage() {
        when(jobs.claimNext(anyString())).thenReturn(Optional.empty());
        assertFalse(worker.runOnce());
        verify(jobs).recoverExpired();
        verifyNoInteractions(materials, storage, extractor);
    }

    @Test void successfulPollDownloadsParsesAndCompletesClaim() {
        var claim = claimed();
        byte[] pdf = "%PDF-test".getBytes();
        when(storage.download("users/owner/materials/document.pdf", 100)).thenReturn(pdf);
        var pages = List.of(new PdfTextExtractor.PageText(1, "one"));
        when(extractor.extract(pdf)).thenReturn(pages);
        assertTrue(worker.runOnce());
        var order = inOrder(jobs, storage, extractor);
        order.verify(jobs).recoverExpired();
        order.verify(jobs).claimNext(anyString());
        order.verify(storage).download("users/owner/materials/document.pdf", 100);
        order.verify(jobs).renew(claim);
        order.verify(extractor).extract(pdf);
        order.verify(jobs).renew(claim);
        order.verify(jobs).completeWithPages(claim, pages);
        verify(jobs, never()).fail(any(), any());
    }

    @Test void storageFailureIsClassifiedWithoutAttemptingCompletion() {
        var claim = claimed();
        when(storage.download(anyString(), anyLong())).thenThrow(new StorageUnavailableException());
        assertTrue(worker.runOnce());
        verify(jobs).fail(claim, FailureCode.STORAGE_UNAVAILABLE);
        verify(jobs, never()).completeWithPages(any(), any());
        verifyNoInteractions(extractor);
    }

    @Test void staleCompletionCannotMarkANewerAttemptFailed() {
        var claim = claimed();
        byte[] pdf = "%PDF-test".getBytes();
        when(storage.download(anyString(), anyLong())).thenReturn(pdf);
        when(extractor.extract(pdf)).thenReturn(List.of(new PdfTextExtractor.PageText(1, "one")));
        doThrow(new ExtractionLeaseLostException()).when(jobs).completeWithPages(eq(claim), any());
        assertTrue(worker.runOnce());
        verify(jobs, never()).fail(any(), any());
    }

    @Test void lostLeaseAfterDownloadStopsParsingAndCompletion() {
        var claim = claimed();
        when(storage.download(anyString(), anyLong())).thenReturn("%PDF-test".getBytes());
        doThrow(new ExtractionLeaseLostException()).when(jobs).renew(claim);
        assertTrue(worker.runOnce());
        verifyNoInteractions(extractor);
        verify(jobs, never()).completeWithPages(any(), any());
        verify(jobs, never()).fail(any(), any());
    }

    @Test void longDownloadRenewsLeaseUntilItCanFinish() throws Exception {
        var claim = claimed();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        byte[] pdf = "%PDF-test".getBytes();
        when(jobs.renewalIntervalMillis()).thenReturn(20L);
        when(storage.download(anyString(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return pdf;
        });
        when(extractor.extract(pdf)).thenReturn(List.of(new PdfTextExtractor.PageText(1, "one")));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var outcome = executor.submit(worker::runOnce);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            verify(jobs, timeout(1000).atLeastOnce()).renew(claim);
            release.countDown();
            assertTrue(outcome.get(2, TimeUnit.SECONDS));
            verify(jobs).completeWithPages(eq(claim), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void processingDeadlineStopsLateResultAndSchedulesRetry() throws Exception {
        var claim = claimed();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(jobs.renewalIntervalMillis()).thenReturn(10L);
        when(storage.download(anyString(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return "%PDF-test".getBytes();
        });
        var shortWorker = new ExtractionWorker(jobs, materials, storage, extractor, 100, "PT0.05S");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var outcome = executor.submit(shortWorker::runOnce);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            release.countDown();
            assertTrue(outcome.get(2, TimeUnit.SECONDS));
            verifyNoInteractions(extractor);
            verify(jobs, never()).completeWithPages(any(), any());
            verify(jobs).fail(claim, FailureCode.PROCESSING_TIMEOUT);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private Claim claimed() {
        var claim = new Claim("job", "material", "worker", 1);
        var material = mock(Material.class);
        when(material.getStoragePath()).thenReturn("users/owner/materials/document.pdf");
        when(jobs.claimNext(anyString())).thenReturn(Optional.of(claim));
        when(materials.findById("material")).thenReturn(Optional.of(material));
        return claim;
    }
}
