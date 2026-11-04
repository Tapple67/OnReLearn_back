package com.onrelearn.material.entity;

import com.onrelearn.subject.entity.Subject;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Entity
@Table(name = "material", uniqueConstraints = @UniqueConstraint(
    name = "uk_material_subject_week", columnNames = {"subject_id", "week"}))
public class Material {
    @Setter(AccessLevel.NONE)
    @Id @Column(length = 64) private String id;
    @ToString.Exclude
    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false) private Subject subject;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false) private int week;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false, length = 255) private String title;
    @ToString.Exclude
    @Column(name = "storage_path", nullable = false, length = 1024) private String storagePath;
    @Setter(AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    // * Builder도 동일한 검증 생성자를 통과해 식별자와 생성 시각을 빠뜨리지 않는다.
    @Builder
    public Material(Subject subject, int week, String title, String storagePath) {
        this.id = UUID.randomUUID().toString();
        this.subject = Objects.requireNonNull(subject);
        moveToWeek(week);
        rename(title);
        setStoragePath(storagePath);
        this.createdAt = Instant.now();
    }

    public static Material forPdfUpload(Subject subject, int week, String title, String memberId) {
        Material material = new Material(subject, week, title, "pending.pdf");
        material.setStoragePath("users/" + memberId + "/materials/" + material.id + ".pdf");
        return material;
    }

    public void moveToWeek(int week) {
        if (week < 1) throw new IllegalArgumentException("Study week must be positive");
        this.week = week;
    }

    public void rename(String title) {
        if (title == null || title.isBlank() || title.trim().length() > 255) {
            throw new IllegalArgumentException("Material title must contain 1 to 255 characters");
        }
        this.title = title.trim();
    }

    public void setStoragePath(String path) {
        if (path == null || path.isBlank() || path.length() > 1024) {
            throw new IllegalArgumentException("Material storage path must contain 1 to 1024 characters");
        }
        this.storagePath = path;
    }

    // ! 변경 가능한 주차·제목·관계 대신 불변 ID만 동등성에 사용한다.
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof Material entity && getId() != null && getId().equals(entity.getId());
    }

    @Override public int hashCode() { return Material.class.hashCode(); }
}
