package com.onrelearn.material.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Entity
@Table(name = "extraction_job", indexes = {
    @Index(name = "ix_extraction_job_claim", columnList = "status,type,priority,created_at"),
    @Index(name = "ix_extraction_job_material", columnList = "material_id,type,status")
})
public class ExtractionJob {
    @Setter(AccessLevel.NONE)
    @Id @Column(length = 64) private String id;
    @ToString.Exclude
    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false) private Material material;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false, length = 40) private String type;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false, length = 16) private String status;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false) private int priority;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false) private int attempts;
    @Setter(AccessLevel.NONE)
    @Column(name = "max_attempts", nullable = false) private int maxAttempts;
    @Setter(AccessLevel.NONE)
    @Column(name = "locked_at", columnDefinition = "datetime(6)") private Instant lockedAt;
    @Setter(AccessLevel.NONE)
    @Column(name = "locked_by", length = 128) private String lockedBy;
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @Column(name = "last_error", length = 100) private String lastError;
    @Setter(AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @Setter(AccessLevel.NONE)
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)") private Instant updatedAt;

    @Builder
    public ExtractionJob(Material material, int priority, int maxAttempts) {
        this.id = UUID.randomUUID().toString();
        this.material = Objects.requireNonNull(material);
        if (maxAttempts < 1) throw new IllegalArgumentException("Maximum attempts must be positive");
        this.type = "EXTRACT_PDF";
        this.status = "PENDING";
        this.priority = priority;
        this.maxAttempts = maxAttempts;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public boolean canClaim(Instant staleBefore) {
        if (attempts >= maxAttempts) return false;
        return "PENDING".equals(status) || ("RUNNING".equals(status)
            && lockedAt != null && !lockedAt.isAfter(staleBefore));
    }

    // * claim 한 번에 attempts를 정확히 한 번 증가시킨다. 실패 판정에서 다시 더하지 않는다.
    public void claim(String workerId, Instant now, Instant staleBefore) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 128
            || now == null || staleBefore == null || !canClaim(staleBefore)) {
            throw new IllegalStateException("Extraction job is not claimable");
        }
        status = "RUNNING";
        attempts++;
        lockedAt = now;
        lockedBy = workerId;
        updatedAt = now;
    }

    public void complete(String workerId, Instant now) {
        requireLease(workerId);
        status = "DONE";
        lastError = null;
        clearLease(now);
    }

    // * 같은 시도의 살아 있는 lease만 연장하며 시도 횟수와 상태는 변경하지 않는다.
    public void renewLease(String workerId, Instant now) {
        requireLease(workerId);
        if (now == null || lockedAt == null || now.isBefore(lockedAt)) {
            throw new IllegalArgumentException("Lease renewal time is invalid");
        }
        lockedAt = now;
        updatedAt = now;
    }

    public void fail(String workerId, Instant now, String safeErrorCode) {
        requireLease(workerId);
        if (safeErrorCode == null || safeErrorCode.isBlank() || safeErrorCode.length() > 100) {
            throw new IllegalArgumentException("A safe failure code is required");
        }
        // ! 실제 시도 횟수로 종료 여부를 판정해 기존 worker의 조기 FAILED 결함을 피한다.
        status = attempts >= maxAttempts ? "FAILED" : "PENDING";
        lastError = safeErrorCode;
        clearLease(now);
    }

    public void expireLease(Instant now, Instant staleBefore) {
        if (!"RUNNING".equals(status) || lockedAt == null || staleBefore == null
            || lockedAt.isAfter(staleBefore)) {
            throw new IllegalStateException("Extraction job lease is not stale");
        }
        // * 만료된 마지막 시도는 FAILED로 확정하고 그 전에는 PENDING으로 돌려 재시도한다.
        status = attempts >= maxAttempts ? "FAILED" : "PENDING";
        lastError = "LEASE_EXPIRED";
        clearLease(now);
    }

    private void requireLease(String workerId) {
        if (!"RUNNING".equals(status) || workerId == null || !workerId.equals(lockedBy)) {
            throw new IllegalStateException("Extraction job lease is not owned by this worker");
        }
    }

    private void clearLease(Instant now) {
        lockedAt = null;
        lockedBy = null;
        updatedAt = Objects.requireNonNull(now);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof ExtractionJob entity && getId() != null && getId().equals(entity.getId());
    }

    @Override public int hashCode() { return ExtractionJob.class.hashCode(); }
}
