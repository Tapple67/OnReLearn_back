package com.onrelearn.quiz.entity;

import com.onrelearn.quiz.model.QuizAttemptStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="quiz_attempt")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded=true)
public class QuizAttempt {
    @Id @Column(length=64) @ToString.Include private String id;
    // * Preserve the legacy scalar owner ID; ownership is checked by the service.
    @Column(name="user_id",nullable=false,length=64) private String userId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="quiz_set_id",nullable=false) private QuizSet quizSet;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable=false,length=32) private QuizAttemptStatus status;
    private Integer score;
    @Column(name="max_score") private Integer maxScore;
    @Column(name="started_at",nullable=false,columnDefinition="datetime(6)") private Instant startedAt;
    @Column(name="submitted_at",columnDefinition="datetime(6)") private Instant submittedAt;
    @Column(name="graded_at",columnDefinition="datetime(6)") private Instant gradedAt;

    public QuizAttempt(String userId, QuizSet quizSet) {
        if(userId==null || userId.isBlank() || userId.length()>64) throw new IllegalArgumentException("Invalid owner ID");
        this.userId=userId;
        this.quizSet=Objects.requireNonNull(quizSet);
        this.id=UUID.randomUUID().toString();
        this.status=QuizAttemptStatus.IN_PROGRESS;
        this.startedAt=Instant.now();
    }
    // ! Called under the subject lock in the same transaction as all answer grades.
    public void completeGrading(int score,int maxScore,Instant now) {
        if(status!=QuizAttemptStatus.IN_PROGRESS) throw new IllegalStateException("Quiz already submitted");
        this.score=score; this.maxScore=maxScore;
        this.submittedAt=Objects.requireNonNull(now); this.gradedAt=now;
        this.status=QuizAttemptStatus.GRADED;
    }
}
