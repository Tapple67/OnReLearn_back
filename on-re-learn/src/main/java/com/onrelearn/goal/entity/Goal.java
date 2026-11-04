package com.onrelearn.goal.entity;

import com.onrelearn.member.entity.Member;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Entity
@Table(name = "goal", uniqueConstraints = @UniqueConstraint(name = "uk_goal_user", columnNames = "user_id"))
public class Goal {
    @Id @Column(length = 64)
    private String id;
    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Member user;
    @Column(name = "daily_quiz_goal", nullable = false)
    private int dailyQuizGoal;
    @Column(name = "weekly_quiz_goal", nullable = false)
    private int weeklyQuizGoal;
    @Column(name = "daily_material_goal", nullable = false)
    private int dailyMaterialGoal;
    @Column(name = "weekly_material_goal", nullable = false)
    private int weeklyMaterialGoal;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    public Goal(Member user) {
        this.id = UUID.randomUUID().toString();
        this.user = Objects.requireNonNull(user);
        this.createdAt = this.updatedAt = Instant.now();
        this.dailyQuizGoal = 10;
        this.weeklyQuizGoal = 70;
        this.dailyMaterialGoal = 3;
        this.weeklyMaterialGoal = 15;
    }

    public void update(int dailyQuizGoal, int weeklyQuizGoal, int dailyMaterialGoal, int weeklyMaterialGoal) {
        if (dailyQuizGoal < 0 || weeklyQuizGoal < 0 || dailyMaterialGoal < 0 || weeklyMaterialGoal < 0) {
            throw new IllegalArgumentException("목표는 0 이상의 정수여야 합니다.");
        }
        this.dailyQuizGoal = dailyQuizGoal;
        this.weeklyQuizGoal = weeklyQuizGoal;
        this.dailyMaterialGoal = dailyMaterialGoal;
        this.weeklyMaterialGoal = weeklyMaterialGoal;
        this.updatedAt = Instant.now();
    }
}
