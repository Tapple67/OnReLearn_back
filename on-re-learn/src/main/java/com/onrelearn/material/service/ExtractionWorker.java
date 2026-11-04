package com.onrelearn.material.service;

import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.material.service.ExtractionJobService.Claim;
import com.onrelearn.material.service.ExtractionJobService.FailureCode;
import com.onrelearn.material.storage.PdfObjectStorage;
import com.onrelearn.material.storage.StorageUnavailableException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExtractionWorker {
    private final ExtractionJobService jobs;
    private final MaterialRepository materials;
    private final PdfObjectStorage storage;
    private final PdfTextExtractor extractor;
    private final long maxBytes;
    private final Duration maxProcessing;
    private final String workerId = UUID.randomUUID().toString();

    public ExtractionWorker(ExtractionJobService jobs, MaterialRepository materials,
        PdfObjectStorage storage, PdfTextExtractor extractor,
        @Value("${onrelearn.material.max-bytes:20971520}") long maxBytes,
        @Value("${onrelearn.material.extraction.max-processing:PT15M}") String maxProcessing) {
        if (maxBytes < 1) throw new IllegalArgumentException("Invalid PDF size limit");
        this.jobs = jobs;
        this.materials = materials;
        this.storage = storage;
        this.extractor = extractor;
        this.maxBytes = maxBytes;
        this.maxProcessing = Duration.parse(maxProcessing);
        if (this.maxProcessing.isZero() || this.maxProcessing.isNegative()
            || this.maxProcessing.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid PDF processing time limit");
        }
    }

    // * 짧은 DB 선점 뒤 다운로드·파싱은 잠금 밖에서 수행하고 결과 저장 때 lease를 다시 검사한다.
    public boolean runOnce() {
        jobs.recoverExpired();
        var available = jobs.claimNext(workerId);
        if (available.isEmpty()) return false;
        Claim claim = available.get();
        try (var heartbeat = new LeaseHeartbeat(claim)) {
            String path = materials.findById(claim.materialId())
                .orElseThrow(() -> new PdfExtractionException()).getStoragePath();
            var pdf = storage.download(path, maxBytes);
            heartbeat.check();
            // * 각 단계 경계도 확인해 갱신 스레드의 소유권 상실을 다음 단계에 전파한다.
            jobs.renew(claim);
            var pages = extractor.extract(pdf);
            heartbeat.check();
            jobs.renew(claim);
            // ! 최종 저장 전에 갱신 스레드를 완전히 멈춰 소유권 상실과 완료 전이가 엇갈리지 않게 한다.
            heartbeat.close();
            heartbeat.check();
            jobs.completeWithPages(claim, pages);
        } catch (ExtractionLeaseLostException stale) {
            // ! 이전 시도는 새 작업자의 상태나 페이지 결과를 변경하지 않는다.
        } catch (ExtractionProcessingTimeoutException timeout) {
            // * 상한 후에도 소유권이 유효하면 즉시 PENDING/FAILED로 전이해 만료 회수를 기다리지 않는다.
            failIfOwned(claim, FailureCode.PROCESSING_TIMEOUT);
        } catch (StorageUnavailableException unavailable) {
            failIfOwned(claim, FailureCode.STORAGE_UNAVAILABLE);
        } catch (PdfExtractionException invalid) {
            failIfOwned(claim, FailureCode.PDF_INVALID);
        } catch (RuntimeException unexpected) {
            // ! 원격 응답과 내부 오류 원문을 DB나 로그에 기록하지 않는다.
            failIfOwned(claim, FailureCode.EXTRACTION_ERROR);
        }
        return true;
    }

    private void failIfOwned(Claim claim, FailureCode code) {
        try { jobs.fail(claim, code); }
        catch (ExtractionLeaseLostException stale) {
            // * lease 만료 후 다른 시도가 선점했다면 이 시도의 실패 보고를 버린다.
        }
    }

    private final class LeaseHeartbeat implements AutoCloseable {
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pdf-extraction-lease");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final long started = System.nanoTime();

        LeaseHeartbeat(Claim claim) {
            long interval = jobs.renewalIntervalMillis();
            if (interval < 1) throw new IllegalStateException("Invalid extraction renewal interval");
            // ! 다운로드·파싱이 멈춰도 무한히 lease를 연장하지 않도록 처리 한도를 적용한다.
            scheduler.scheduleWithFixedDelay(() -> {
                if (lost.get() || timedOut.get()) return;
                if (expired()) { timedOut.set(true); return; }
                try { jobs.renew(claim); }
                catch (RuntimeException failure) { lost.set(true); }
            }, interval, interval, TimeUnit.MILLISECONDS);
        }

        void check() {
            if (lost.get()) throw new ExtractionLeaseLostException();
            if (timedOut.get() || expired()) throw new ExtractionProcessingTimeoutException();
        }

        private boolean expired() { return System.nanoTime() - started >= maxProcessing.toNanos(); }

        @Override public void close() {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) lost.set(true);
            } catch (InterruptedException interrupted) {
                lost.set(true);
                Thread.currentThread().interrupt();
            }
        }
    }
}
