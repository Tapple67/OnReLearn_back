package com.onrelearn.material.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Data
@Entity
@Table(name = "material_deletion")
public class MaterialDeletion {
    @Id @Setter(AccessLevel.NONE)
    @Column(name = "material_id", length = 64) private String materialId;
    @Setter(AccessLevel.NONE)
    @Column(name = "owner_id", nullable = false, length = 64) private String ownerId;
    @Setter(AccessLevel.NONE) @ToString.Exclude
    @Column(name = "storage_path", nullable = false, length = 1024) private String storagePath;
    @Setter(AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)") private Instant createdAt;

    // ! 자료 행 삭제 후에도 원격 삭제 실패를 재시도할 수 있도록 경로와 요청 소유자를 보존한다.
    @Builder
    public MaterialDeletion(String materialId, String ownerId, String storagePath) {
        if (materialId == null || materialId.isBlank() || materialId.length() > 64
            || ownerId == null || ownerId.isBlank() || ownerId.length() > 64
            || storagePath == null || storagePath.isBlank() || storagePath.length() > 1024) {
            throw new IllegalArgumentException("Invalid material deletion request");
        }
        this.materialId = materialId;
        this.ownerId = ownerId;
        this.storagePath = storagePath;
        this.createdAt = Instant.now();
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof MaterialDeletion entity && getMaterialId() != null
            && getMaterialId().equals(entity.getMaterialId());
    }
    @Override public int hashCode() { return MaterialDeletion.class.hashCode(); }
}
