package com.onrelearn.material.repository;

import com.onrelearn.material.entity.ExtractionJob;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtractionJobRepository extends JpaRepository<ExtractionJob, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ExtractionJob job where job.material.id = :materialId order by job.id")
    List<ExtractionJob> lockByMaterialId(@Param("materialId") String materialId);

    List<ExtractionJob> findByMaterial_IdOrderByCreatedAtDesc(String materialId);

    // * 대기 중인 작업 하나만 잠그며 다른 트랜잭션이 선점 중인 행은 기다리지 않는다.
    @Query(value = """
        SELECT * FROM extraction_job
        WHERE type = 'EXTRACT_PDF' AND status = 'PENDING' AND attempts < max_attempts
        ORDER BY priority DESC, created_at ASC, id ASC
        LIMIT 1 FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<ExtractionJob> lockNextPending();

    @Query(value = """
        SELECT * FROM extraction_job
        WHERE type = 'EXTRACT_PDF' AND status = 'RUNNING' AND locked_at <= :cutoff
        ORDER BY locked_at ASC, id ASC
        LIMIT 100 FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ExtractionJob> lockExpired(@Param("cutoff") Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ExtractionJob job where job.id = :id")
    Optional<ExtractionJob> lockById(@Param("id") String id);

    // ! 여러 서버의 시계 차이가 lease 만료 판정을 바꾸지 않도록 DB의 UTC 시각을 사용한다.
    @Query(value = "SELECT UTC_TIMESTAMP(6)", nativeQuery = true)
    LocalDateTime databaseUtcNow();
}
