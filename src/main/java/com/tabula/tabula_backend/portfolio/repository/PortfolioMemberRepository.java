package com.tabula.tabula_backend.portfolio.repository;

import com.tabula.tabula_backend.portfolio.model.*;

import java.util.Optional;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioMemberRepository extends JpaRepository<PortfolioMember, String> {
    Optional<PortfolioMember> findByPortfolioIdAndUserId(String portfolioId, String userId);

    long countByPortfolioIdAndRole(String portfolioId, PortfolioRole role);

    Page<PortfolioMember> findByPortfolioId(String portfolioId, Pageable pageable);
}
