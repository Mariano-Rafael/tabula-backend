package com.tabula.tabula_backend.flow.repository;

import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import java.util.Optional;

public interface FlowDocumentStore {
    void putSnapshot(Snapshot snapshot);
    Optional<Snapshot> snapshot(String portfolioId, String documentId);
    Draft initializeDraft(String flowId, String portfolioId, String json, String checksum);
    Optional<Draft> draft(String portfolioId, String flowId);
    Draft updateDraft(String portfolioId, String flowId, long expectedRevision, String json, String checksum);
}
