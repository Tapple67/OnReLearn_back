package com.onrelearn.material.service;

import com.onrelearn.material.entity.ExtractionJob;
import com.onrelearn.material.entity.MaterialPageText;
import com.onrelearn.material.repository.ExtractionJobRepository;
import com.onrelearn.material.repository.MaterialPageTextRepository;
import com.onrelearn.material.repository.MaterialRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionJobService {
    private final ExtractionJobRepository jobs;
    private final MaterialRepository materials;
    private final MaterialPageTextRepository pages;
    private final Duration leaseDuration;

    public ExtractionJobService(ExtractionJobRepository jobs, MaterialRepository materials,
        MaterialPageTextRepository pages,
        @Value("${onrelearn.material.extraction.lease:PT5M}") String lease) {
        this.jobs = jobs;
        this.materials = materials;
        this.pages = pages;
        this.leaseDuration = Duration.parse(lease);
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Extraction lease must be positive");
        }
    }

    // * 짧은 트랜잭션 안에서 행 선택과 RUNNING 변경을 커밋한 뒤 worker에 값만 반환한다.
    // ! 다운로드·PDF 파싱은 이 메서드가 끝난 뒤 수행해야 DB 행 잠금이 오래 유지되지 않는다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Claim> claimNext(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
            throw new IllegalArgumentException("Worker id must contain 1 to 128 characters");
        }
        return jobs.lockNextPending().map(job -> {
            Instant now = databaseNow();
            job.claim(workerId, now, now.minus(leaseDuration));
            return new Claim(job.getId(), job.getMaterial().getId(), workerId, job.getAttempts());
        });
    }

    // * 한 번에 최대 100개만 회수한다. 마지막 시도에서 죽은 작업은 FAILED가 되어 무한 RUNNING을 막는다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recoverExpired() {
        Instant now = databaseNow();
        Instant cutoff = now.minus(leaseDuration);
        var expired = jobs.lockExpired(cutoff);
        expired.forEach(job -> job.expireLease(now, cutoff));
        return expired.size();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(Claim claim) {
        ExtractionJob job = ownedJob(claim);
        job.complete(claim.workerId(), databaseNow());
    }

    // ! 이전 시도나 만료된 소유자는 갱신할 수 없다. DB 행 잠금과 UTC 시각을 사용한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void renew(Claim claim) {
        ExtractionJob job = ownedJob(claim);
        job.renewLease(claim.workerId(), databaseNow());
    }

    public long renewalIntervalMillis() {
        // * lease의 1/3 간격으로 갱신해 짧은 DB 지연이 있어도 만료 전에 재시도할 여유를 둔다.
        return Math.max(1L, leaseDuration.toMillis() / 3L);
    }

    // * 페이지 결과와 DONE 전이를 같은 트랜잭션에 묶어 재시도 시 반쪽 결과가 보이지 않게 한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void completeWithPages(Claim claim, java.util.List<PdfTextExtractor.PageText> extracted) {
        Objects.requireNonNull(extracted);
        ExtractionJob job = ownedJob(claim);
        if (!job.getMaterial().getId().equals(claim.materialId()) || extracted.isEmpty()) {
            throw new IllegalArgumentException("Extraction result does not match the claimed material");
        }
        // ! 이전 시도의 결과나 임의 페이지를 덮어쓰지 않는다. 기존 결과가 있으면 작업을 중단한다.
        if (!pages.findByMaterial_IdOrderByPageAsc(claim.materialId()).isEmpty()) {
            throw new IllegalStateException("Extraction pages already exist");
        }
        var material = materials.findById(claim.materialId()).orElseThrow(ExtractionLeaseLostException::new);
        int expectedPage = 1;
        for (var result : extracted) {
            if (result == null || result.page() != expectedPage++ || result.text() == null) {
                throw new IllegalArgumentException("Extraction pages must be consecutive");
            }
            pages.save(new MaterialPageText(material, result.page(), result.text(), "PDFBOX"));
        }
        pages.flush();
        job.complete(claim.workerId(), databaseNow());
        jobs.flush();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void fail(Claim claim, FailureCode failure) {
        Objects.requireNonNull(failure);
        ExtractionJob job = ownedJob(claim);
        job.fail(claim.workerId(), databaseNow(), failure.name());
    }

    private ExtractionJob ownedJob(Claim claim) {
        Objects.requireNonNull(claim);
        ExtractionJob job = jobs.lockById(claim.jobId()).orElseThrow(ExtractionLeaseLostException::new);
        Instant cutoff = databaseNow().minus(leaseDuration);
        // ! 같은 작업자 이름이라도 이전 시도의 늦은 결과는 현재 작업을 완료/실패로 바꿀 수 없다.
        if (!"RUNNING".equals(job.getStatus()) || !Objects.equals(job.getLockedBy(), claim.workerId())
            || job.getAttempts() != claim.attempt() || job.getLockedAt() == null
            || !job.getLockedAt().isAfter(cutoff)) {
            throw new ExtractionLeaseLostException();
        }
        return job;
    }

    private Instant databaseNow() {
        return jobs.databaseUtcNow().toInstant(ZoneOffset.UTC);
    }

    public record Claim(String jobId, String materialId, String workerId, int attempt) {}

    // ! 원격 응답이나 예외 원문 대신 안전한 분류 코드만 DB에 기록한다.
    public enum FailureCode { PDF_INVALID, STORAGE_UNAVAILABLE, EXTRACTION_ERROR, PROCESSING_TIMEOUT }
}
