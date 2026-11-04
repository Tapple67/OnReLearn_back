package com.onrelearn.semester.entity;

import com.onrelearn.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Entity
@Table(name = "grade", uniqueConstraints = @UniqueConstraint(name = "uk_grade_user_year_term", columnNames = {"user_id", "academic_year", "term"}))
public class Grade {
    @Setter(AccessLevel.NONE)
    @Id @Column(length = 64) private String id;
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private Member user;
    @Column(name = "academic_year", nullable = false) private int year;
    @Column(nullable = false) private int term;
    @Column(name = "is_current", nullable = false) private boolean current;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    // Builder uses the validating domain constructor.
    @Builder
    public Grade(Member user, int year, int term, boolean current) {
        if (year < 1 || year > 6 || term < 1 || term > 2) {
            throw new IllegalArgumentException("Invalid academic year or term");
        }
        this.id = UUID.randomUUID().toString();
        this.user = Objects.requireNonNull(user);
        this.year = year;
        this.term = term;
        this.current = current;
        this.createdAt = Instant.now();
    }
    // Stable hash and ID equality avoid mutable fields and lazy relations.
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof Grade entity && getId() != null && getId().equals(entity.getId());
    }

    @Override
    public int hashCode() { return Grade.class.hashCode(); }
}
