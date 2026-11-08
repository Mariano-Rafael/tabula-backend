package com.tabula.tabula_backend.flow.repository;

import com.tabula.tabula_backend.flow.model.Flow;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface FlowRepository extends JpaRepository<Flow, String> {
    @Query("select f.projectId from Flow f where f.id = :id")
    java.util.Optional<String> findProjectId(String id);
    @Query("select f from Flow f where f.projectId = :projectId and (:includeArchived = true or f.archived = false)")
    Page<Flow> findInProject(String projectId, boolean includeArchived, Pageable page);
}
