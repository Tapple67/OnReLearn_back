package com.onrelearn.summary.entity;

import com.onrelearn.material.entity.Material;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Setter(AccessLevel.NONE)
@Entity
@Table(name = "material_summary", uniqueConstraints = @UniqueConstraint(name = "uk_material_summary_material", columnNames = "material_id"))
public class MaterialSummary {
    @Id @Column(length = 64)
    private String id;
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;
    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;
    @ToString.Exclude @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;
    @ToString.Exclude @Column(columnDefinition = "LONGTEXT")
    private String canonical;
    @ToString.Exclude @Column(columnDefinition = "LONGTEXT")
    private String adaptive;
    @Column(name = "summary_type", columnDefinition = "LONGTEXT")
    private String summaryType;
    // * 원본 digest는 해시가 아니라 페이지별 signals를 직렬화한 JSON 문자열이다.
    @ToString.Exclude @Column(name = "signals_digest", columnDefinition = "LONGTEXT")
    private String signalsDigest;
    @Column(columnDefinition = "LONGTEXT")
    private String provider;
    @Column(columnDefinition = "LONGTEXT")
    private String model;
    @Column(name = "prompt_version", columnDefinition = "LONGTEXT")
    private String promptVersion;
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    @Builder
    public MaterialSummary(Material material, String sourceHash, String content, String canonical,
            String adaptive, String summaryType, String signalsDigest, String provider, String model, String promptVersion) {
        this.material = Objects.requireNonNull(material);
        this.id = UUID.randomUUID().toString();
        replace(sourceHash, content, canonical, adaptive, summaryType, signalsDigest, provider, model, promptVersion);
        this.createdAt = updatedAt;
    }

    // * 재생성은 자료당 기존 한 행을 갱신하고 최초 생성 시각과 ID를 유지한다.
    public void replace(String sourceHash, String content, String canonical, String adaptive,
            String summaryType, String signalsDigest, String provider, String model, String promptVersion) {
        if (sourceHash == null || sourceHash.isBlank() || sourceHash.length() > 64) {
            throw new IllegalArgumentException("Summary source hash is required and must fit 64 characters");
        }
        Objects.requireNonNull(content);
        this.sourceHash = sourceHash;
        this.content = content;
        this.canonical = canonical;
        this.adaptive = adaptive;
        this.summaryType = summaryType;
        this.signalsDigest = signalsDigest;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.updatedAt = Instant.now();
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof MaterialSummary summary
                && getId() != null && getId().equals(summary.getId());
    }
    @Override public int hashCode() { return MaterialSummary.class.hashCode(); }
}
