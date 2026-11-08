package com.tabula.tabula_backend.flow.repository;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.flow.service.FlowException;

import java.util.Optional;

import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

@Repository
public class MongoFlowDocumentStore implements FlowDocumentStore {
    private final ObjectProvider<MongoClient> clients;
    private final String database;
    private volatile boolean indexed;

    public MongoFlowDocumentStore(ObjectProvider<MongoClient> clients, @Value("${spring.mongodb.database:tabula}") String database) {
        this.clients = clients;
        this.database = database;
    }

    private MongoCollection<Document> collection(String name) {
        MongoClient client = clients.getIfAvailable();
        if (client == null) {
            throw unavailable();
        }
        var db = client.getDatabase(database);
        if (!indexed) {
            synchronized (this) {
                if (!indexed) {
                    db.getCollection("flow_documents").createIndex(Indexes.ascending("versionId"), new IndexOptions().unique(true));
                    db.getCollection("flow_documents").createIndex(Indexes.compoundIndex(Indexes.ascending("portfolioId"), Indexes.ascending("flowId")));
                    db.getCollection("flow_drafts").createIndex(Indexes.ascending("portfolioId"));
                    indexed = true;
                }
            }
        }
        return db.getCollection(name);
    }

    @Override
    public void putSnapshot(Snapshot s) {
        try {
            var collection = collection("flow_documents");
            try {
                collection.insertOne(new Document("_id", s.documentId()).append("versionId", s.versionId()).append("flowId", s.flowId())
                        .append("projectId", s.projectId()).append("portfolioId", s.portfolioId()).append("schemaVersion", 1)
                        .append("checksum", s.checksum()).append("rawJson", s.rawJson()));
            } catch (MongoWriteException ex) {
                if (ex.getError().getCode() != 11000) {
                    throw ex;
                }
                var existing = snapshot(s.portfolioId(), s.documentId()).orElseThrow(MongoFlowDocumentStore::conflict);
                if (!existing.versionId().equals(s.versionId()) || !existing.flowId().equals(s.flowId())
                        || !existing.projectId().equals(s.projectId()) || !existing.checksum().equals(s.checksum())) {
                    throw conflict();
                }
            }
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    @Override
    public Optional<Snapshot> snapshot(String portfolioId, String documentId) {
        try {
            var d = collection("flow_documents").find(and(eq("_id", documentId), eq("portfolioId", portfolioId))).first();
            return Optional.ofNullable(d).map(doc -> new Snapshot(doc.getString("_id"), doc.getString("versionId"), doc.getString("flowId"),
                    doc.getString("projectId"), doc.getString("portfolioId"), doc.getString("checksum"), doc.getString("rawJson")));
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    @Override
    public Draft initializeDraft(String flowId, String portfolioId, String json, String checksum) {
        try {
            collection("flow_drafts").insertOne(new Document("_id", flowId).append("portfolioId", portfolioId)
                    .append("revision", 1L).append("checksum", checksum).append("rawJson", json));
            return new Draft(flowId, portfolioId, 1, checksum, json);
        } catch (MongoWriteException ex) {
            if (ex.getError().getCode() == 11000) {
                throw conflict();
            }
            throw unavailable();
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    @Override
    public Optional<Draft> draft(String portfolioId, String flowId) {
        try {
            return Optional.ofNullable(collection("flow_drafts").find(and(eq("_id", flowId), eq("portfolioId", portfolioId))).first()).map(MongoFlowDocumentStore::draft);
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    @Override
    public Draft updateDraft(String portfolioId, String flowId, long revision, String json, String checksum) {
        try {
            var result = collection("flow_drafts").findOneAndUpdate(and(eq("_id", flowId), eq("portfolioId", portfolioId), eq("revision", revision)),
                    combine(set("rawJson", json), set("checksum", checksum), inc("revision", 1L)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (result == null) {
                throw new FlowException(HttpStatus.PRECONDITION_FAILED, "DRAFT_REVISION_MISMATCH", "Rascunho alterado ou inexistente. Atualize a revisão.");
            }
            return draft(result);
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    private static Draft draft(Document d) {
        return new Draft(d.getString("_id"), d.getString("portfolioId"), d.getLong("revision"), d.getString("checksum"), d.getString("rawJson"));
    }

    private static FlowException unavailable() {
        return new FlowException(HttpStatus.SERVICE_UNAVAILABLE, "FLOW_STORAGE_UNAVAILABLE", "Não foi possível acessar os documentos no MongoDB.");
    }

    private static FlowException conflict() {
        return new FlowException(HttpStatus.CONFLICT, "FLOW_DOCUMENT_CONFLICT", "Documento ou rascunho já existe com conteúdo incompatível.");
    }
}
