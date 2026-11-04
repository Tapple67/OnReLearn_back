package com.onrelearn.material.repository;

import com.onrelearn.material.entity.MaterialDeletion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialDeletionRepository extends JpaRepository<MaterialDeletion, String> {
    java.util.List<MaterialDeletion> findByOwnerIdOrderByCreatedAtAscMaterialIdAsc(String ownerId);
    Optional<MaterialDeletion> findByMaterialIdAndOwnerId(String materialId, String ownerId);
}
