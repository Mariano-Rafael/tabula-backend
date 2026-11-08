package com.tabula.tabula_backend.flow.repository;

import com.tabula.tabula_backend.flow.model.FlowVersion;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface FlowVersionRepository extends JpaRepository<FlowVersion, String> {
    Optional<FlowVersion> findByProjectIdAndRequestKey(String projectId, String requestKey);
    Page<FlowVersion> findByFlowId(String flowId, Pageable pageable);
    @Query("select coalesce(max(v.number), 0) from FlowVersion v where v.flowId = :flowId")
    int lastNumber(String flowId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from FlowVersion v where v.id = :id")
    Optional<FlowVersion> findLocked(String id);
}
