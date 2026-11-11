package com.tabula.tabula_backend.flow.repository;

import com.tabula.tabula_backend.flow.dto.EditorDtos.Document;
import java.util.Optional;

public interface DiagramDocumentStore {
    void put(Document document);
    Optional<Document> find(String portfolioId, String id);
}
