package com.tabula.tabula_backend.portfolio.dto;

import com.tabula.tabula_backend.portfolio.model.*;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

public final class PortfolioDtos {
    private PortfolioDtos() {
    }

    public record Create(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description) {
    }

    public record Update(@Size(min = 1, max = 120) String name, @Size(max = 1000) String description) {
    }

    public record AddMember(@NotNull UUID userId, @NotNull PortfolioRole role) {
    }

    public record ChangeRole(@NotNull PortfolioRole role) {
    }

    public record PortfolioView(String id, String name, String description, boolean archived) {
        public static PortfolioView from(Portfolio p) {
            return new PortfolioView(p.getId(), p.getName(), p.getDescription(), p.isArchived());
        }
    }

    public record ProjectView(String id, String portfolioId, String name, String description, boolean archived) {
        public static ProjectView from(Project p) {
            return new ProjectView(p.getId(), p.getPortfolioId(), p.getName(), p.getDescription(), p.isArchived());
        }
    }

    public record MemberView(String userId, PortfolioRole role) {
        public static MemberView from(PortfolioMember m) {
            return new MemberView(m.getUserId(), m.getRole());
        }
    }

    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageView<T> from(Page<T> source) {
            return new PageView<>(source.getContent(), source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages());
        }
    }
}
