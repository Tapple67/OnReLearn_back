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
@Table(name = "quiz_item")
public class QuizItem {
    @Id @Column(length = 64) @ToString.Include
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "quiz_set_id", nullable = false)
    private QuizSet quizSet;
    @Column(name = "item_order", nullable = false)
    private int position;
    @Column(nullable = false, length = 16)
    private String type;
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String question;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "JSON")
    private String choices;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "answer_key", nullable = false, columnDefinition = "JSON")
    private String answerKey;
    @Column(columnDefinition = "LONGTEXT")
    private String explanation;
    @Column(columnDefinition = "LONGTEXT")
    private String topic;
    @Column(nullable = false)
    private int points;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "JSON")
    private String evidence;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "signal_hits", columnDefinition = "JSON")
    private String signalHits;
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @Builder
    public QuizItem(QuizSet quizSet, int position, String type, String question, String choices, String answerKey, String explanation, String topic, int points, String evidence, String signalHits) {
        Objects.requireNonNull(quizSet);
        Objects.requireNonNull(question);
        Objects.requireNonNull(answerKey);
        if (position < 1 || points < 1 || !java.util.Set.of("mcq", "tf", "short").contains(type))
            throw new IllegalArgumentException("Invalid quiz item");
        // ! Answer JSON is stored privately; entities must never be returned as API responses.
        this.quizSet = quizSet;
        this.position = position;
        this.type = type;
        this.question = question;
        this.choices = choices;
        this.answerKey = answerKey;
        this.explanation = explanation;
        this.topic = topic;
        this.points = points;
        this.evidence = evidence;
        this.signalHits = signalHits;
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof QuizItem entity
            && getId() != null && getId().equals(entity.getId());
    }
    @Override public int hashCode() { return QuizItem.class.hashCode(); }
}
