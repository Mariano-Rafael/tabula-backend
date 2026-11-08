package com.tabula.tabula_backend.integration.repository;

import com.tabula.tabula_backend.integration.model.IbmTarget;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IbmTargetRepository extends JpaRepository<IbmTarget, String> {
    Page<IbmTarget> findByProjectId(String projectId, Pageable pageable);
}
