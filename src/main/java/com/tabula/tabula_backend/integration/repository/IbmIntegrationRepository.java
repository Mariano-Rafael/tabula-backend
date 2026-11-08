package com.tabula.tabula_backend.integration.repository;

import com.tabula.tabula_backend.integration.model.IbmIntegration;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IbmIntegrationRepository extends JpaRepository<IbmIntegration, String> {
    Optional<IbmIntegration> findByPortfolioId(String portfolioId);
}
