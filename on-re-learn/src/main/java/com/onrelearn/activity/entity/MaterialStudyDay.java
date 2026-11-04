package com.onrelearn.activity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "material_study_day", uniqueConstraints = @UniqueConstraint(
    name = "uk_material_study_day", columnNames = {"user_id", "material_id", "study_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialStudyDay {
    @Id @Column(length = 64) private String id;
    @Column(name = "user_id", nullable = false, length = 64) private String userId;
    // ! No material FK: completed daily progress survives a later material deletion.
    @Column(name = "material_id", nullable = false, length = 64) private String materialId;
    @Column(name = "study_date", nullable = false) private LocalDate studyDate;
    @Column(name = "first_at", nullable = false, updatable = false, columnDefinition = "datetime(6)") private Instant firstAt;
    @Column(name = "last_at", nullable = false, columnDefinition = "datetime(6)") private Instant lastAt;

    public MaterialStudyDay(String userId, String materialId, LocalDate studyDate, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.userId = Objects.requireNonNull(userId);
        this.materialId = Objects.requireNonNull(materialId);
        this.studyDate = Objects.requireNonNull(studyDate);
        this.firstAt = this.lastAt = Objects.requireNonNull(now);
    }

    public void seenAt(Instant now) {
        if (now.isAfter(lastAt)) lastAt = now;
    }
}
