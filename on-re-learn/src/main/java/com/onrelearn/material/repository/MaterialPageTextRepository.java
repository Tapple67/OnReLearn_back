package com.onrelearn.material.repository;

import com.onrelearn.material.entity.MaterialPageText;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialPageTextRepository extends JpaRepository<MaterialPageText, String> {
    List<MaterialPageText> findByMaterial_IdOrderByPageAsc(String materialId);
    Optional<MaterialPageText> findByMaterial_IdAndPage(String materialId, int page);
}
