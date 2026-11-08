package com.tabula.tabula_backend.portfolio.repository;

import com.tabula.tabula_backend.portfolio.model.Portfolio;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface PortfolioRepository extends JpaRepository<Portfolio, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Portfolio p where p.id = :id")
    Optional<Portfolio> findLocked(String id);

    @Query("select p from Portfolio p where (:includeArchived = true or p.archived = false) "
            + "and (:admin = true or exists (select m.id from PortfolioMember m where m.portfolioId = p.id and m.userId = :userId))")
    Page<Portfolio> findAccessible(String userId, boolean admin, boolean includeArchived, Pageable pageable);
}
