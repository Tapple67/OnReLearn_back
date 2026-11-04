package com.onrelearn.quiz.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="quiz_answer")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded=true)
public class QuizAnswer {
    @Id @Column(length=64) @ToString.Include private String id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="attempt_id",nullable=false) private QuizAttempt attempt;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="quiz_item_id",nullable=false) private QuizItem item;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="JSON") private String response;
    @Column(name="is_correct") private Boolean isCorrect;
    private Integer earned;
    @Column(columnDefinition="TEXT") private String feedback;
    @Column(name="created_at",nullable=false,columnDefinition="datetime(6)") private Instant createdAt;
    @Column(name="updated_at",nullable=false,columnDefinition="datetime(6)") private Instant updatedAt;

    public QuizAnswer(QuizAttempt attempt, QuizItem item, String response) {
        this.attempt=Objects.requireNonNull(attempt);
        this.item=Objects.requireNonNull(item);
        // ! Never attach an item from a different quiz set to this attempt.
        if(!attempt.getQuizSet().getId().equals(item.getQuizSet().getId()))
            throw new IllegalArgumentException("Quiz item does not belong to attempt");
        this.response=Objects.requireNonNull(response);
        this.id=UUID.randomUUID().toString();
        this.createdAt=Instant.now();
        this.updatedAt=createdAt;
    }

    // * The save service must check ownership and IN_PROGRESS before calling this.
    public void replaceResponse(String response) {
        this.response=Objects.requireNonNull(response);
        this.updatedAt=Instant.now();
    }
    public void recordGrade(boolean correct,int earned) {
        this.isCorrect=correct; this.earned=earned; this.updatedAt=Instant.now();
    }
}
