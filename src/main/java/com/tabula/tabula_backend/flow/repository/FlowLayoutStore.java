package com.tabula.tabula_backend.flow.repository;

import com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.Stored;

import java.util.Optional;

public interface FlowLayoutStore {
    Optional<Stored> find(String portfolioId, String flowId);

    Stored save(String portfolioId, String flowId, Long expectedRevision, String rawJson, String checksum, String actorId);
}
