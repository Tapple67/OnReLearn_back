package com.onrelearn.member.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Entity
@Table(name = "app_user")
public class Member {
    @Setter(AccessLevel.NONE)
    @Id @Column(length = 64) private String id;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @ToString.Exclude
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    // Builder uses the validating domain constructor.
    @Builder
    public Member(String email, String passwordHash) {
        this.id = UUID.randomUUID().toString();
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.createdAt = Instant.now();
    }
    // Stable hash and ID equality avoid mutable fields and lazy relations.
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof Member entity && getId() != null && getId().equals(entity.getId());
    }

    @Override
    public int hashCode() { return Member.class.hashCode(); }
}
