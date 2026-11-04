package com.onrelearn.material.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Entity
@Table(name = "material_page_text", uniqueConstraints = @UniqueConstraint(
    name = "uk_material_page_text_material_page", columnNames = {"material_id", "page"}))
public class MaterialPageText {
    @Setter(AccessLevel.NONE)
    @Id @Column(length = 64) private String id;
    @ToString.Exclude
    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false) private Material material;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false) private int page;
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String text;
    @Setter(AccessLevel.NONE)
    @Column(name = "char_count", nullable = false) private int charCount;
    @Setter(AccessLevel.NONE)
    @Column(length = 32) private String method;
    @Setter(AccessLevel.NONE)
    @Column(nullable = false, length = 16) private String status;
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @Column(length = 2000) private String error;
    @Setter(AccessLevel.NONE)
    @Column(name = "extracted_at", nullable = false, columnDefinition = "datetime(6)") private Instant extractedAt;
    @Setter(AccessLevel.NONE)
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)") private Instant updatedAt;

    // * 추출 결과는 페이지 번호 단위로 저장한다. 빈 텍스트도 이미지 PDF의 실제 추출 결과로 보존한다.
    @Builder
    public MaterialPageText(Material material, int page, String text, String method) {
        this.id = UUID.randomUUID().toString();
        this.material = Objects.requireNonNull(material);
        if (page < 1) throw new IllegalArgumentException("PDF page must be positive");
        this.page = page;
        replaceExtractedText(text, method);
    }

    public void replaceExtractedText(String text, String method) {
        if (text == null || method == null || method.isBlank() || method.length() > 32) {
            throw new IllegalArgumentException("PDF extraction result is invalid");
        }
        this.text = text;
        this.charCount = text.length();
        this.method = method;
        this.status = "DONE";
        this.error = null;
        this.extractedAt = Instant.now();
        this.updatedAt = this.extractedAt;
    }

    // ! JPA 관계·텍스트는 가변적이므로 동등성에는 불변 ID만 사용한다.
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof MaterialPageText entity && getId() != null && getId().equals(entity.getId());
    }

    @Override public int hashCode() { return MaterialPageText.class.hashCode(); }
}
