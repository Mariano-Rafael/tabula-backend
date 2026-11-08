package com.tabula.tabula_backend.portfolio.repository;

import com.tabula.tabula_backend.portfolio.model.Project;

import java.util.Optional;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ProjectRepository extends JpaRepository<Project, String> {
    @Query("select p.portfolioId from Project p where p.id = :id")
    Optional<String> findPortfolioId(String id);

    @Query("select p from Project p where p.portfolioId = :portfolioId and (:includeArchived = true or p.archived = false)")
    Page<Project> findInPortfolio(String portfolioId, boolean includeArchived, Pageable pageable);
}
