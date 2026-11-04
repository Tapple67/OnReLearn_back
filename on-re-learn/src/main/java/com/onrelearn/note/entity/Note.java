package com.onrelearn.note.entity;

import com.onrelearn.material.entity.Material;
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
@Entity
@Table(name = "note", uniqueConstraints = @UniqueConstraint(
    name = "uk_note_material_page", columnNames = {"material_id", "page"}))
public class Note {
    @Setter(AccessLevel.NONE) @Id @Column(length = 64)
    private String id;
    @Setter(AccessLevel.NONE) @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;
    @Setter(AccessLevel.NONE) @Column(nullable = false)
    private int page;
    @Setter(AccessLevel.NONE) @ToString.Exclude @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;
    // * 원본의 선택적 개인화 정보는 내용 수정 시 그대로 보존한다.
    @Setter(AccessLevel.NONE) @ToString.Exclude @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "JSON")
    private String signals;
    @Setter(AccessLevel.NONE) @Column(name = "signals_ver", columnDefinition = "LONGTEXT")
    private String signalsVer;
    @Setter(AccessLevel.NONE) @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @Setter(AccessLevel.NONE) @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    @Builder
    public Note(Material material, int page, String content) {
        this.id = UUID.randomUUID().toString();
        this.material = Objects.requireNonNull(material);
        if (page < 1) throw new IllegalArgumentException("페이지는 양수여야 합니다.");
        this.page = page;
        replaceContent(content);
        this.createdAt = this.updatedAt;
    }

    public void replaceContent(String content) {
        // * 빈 문자열은 사용자가 노트 내용을 지운 정상 저장이다. 공백도 보존한다.
        this.content = Objects.requireNonNull(content);
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Note note
            && getId() != null && getId().equals(note.getId());
    }

    @Override
    public int hashCode() { return Note.class.hashCode(); }
}
