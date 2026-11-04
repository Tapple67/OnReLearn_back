package com.onrelearn.quiz.entity;
import com.onrelearn.material.entity.Material;
import com.onrelearn.quiz.model.QuizPurpose;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Setter(AccessLevel.NONE)
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "quiz_set")
public class QuizSet {
    @Id @Column(length = 64) @ToString.Include
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "material_id", nullable = false)
    private Material material;
    @Column(name = "summary_id", length = 64)
    private String summaryId;
    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;
    @Column(columnDefinition = "LONGTEXT")
    private String title;
    @Column(columnDefinition = "LONGTEXT")
    private String provider;
    @Column(columnDefinition = "LONGTEXT")
    private String model;
    @Column(name = "prompt_version", columnDefinition = "LONGTEXT")
    private String promptVersion;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 32)
    private QuizPurpose purpose;
    @Column(name = "source_attempt_id", length = 64)
    private String sourceAttemptId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "source_topics", columnDefinition = "JSON")
    private String sourceTopics;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "source_item_ids", columnDefinition = "JSON")
    private String sourceItemIds;
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @Builder
    public QuizSet(Material material, String summaryId, String sourceHash, String title, String provider, String model, String promptVersion, QuizPurpose purpose, String sourceAttemptId, String sourceTopics, String sourceItemIds) {
        Objects.requireNonNull(material);
        Objects.requireNonNull(purpose);
        if (sourceHash == null || sourceHash.isBlank() || sourceHash.length() > 64)
            throw new IllegalArgumentException("Invalid source hash");
        // * Snapshot references stay nullable scalar IDs, matching legacy history semantics.
        this.material = material;
        this.summaryId = summaryId;
        this.sourceHash = sourceHash;
        this.title = title;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.purpose = purpose;
        this.sourceAttemptId = sourceAttemptId;
        this.sourceTopics = sourceTopics;
        this.sourceItemIds = sourceItemIds;
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }
    public void rename(String title) { this.title = Objects.requireNonNull(title); }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof QuizSet entity
            && getId() != null && getId().equals(entity.getId());
    }
    @Override public int hashCode() { return QuizSet.class.hashCode(); }
}
