package com.onrelearn.subject.entity;

import com.onrelearn.semester.entity.Grade;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Entity
@Table(name = "subject")
public class Subject {
    @Setter(AccessLevel.NONE)
    @Id @Column(length = 64) private String id;
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grade_id", nullable = false) private Grade grade;
    @Column(nullable = false) private String name;
    @Column(name = "is_current", nullable = false) private boolean current;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    // Builder uses the validating domain constructor.
    @Builder
    public Subject(Grade grade, String name) {
        this.id = UUID.randomUUID().toString();
        this.grade = Objects.requireNonNull(grade);
        rename(name);
        this.createdAt = Instant.now();
    }
    public void rename(String name) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("Subject name must contain 1 to 255 characters");
        }
        this.name = name.trim();
    }
    // Stable hash and ID equality avoid mutable fields and lazy relations.
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof Subject entity && getId() != null && getId().equals(entity.getId());
    }

    @Override
    public int hashCode() { return Subject.class.hashCode(); }
}
