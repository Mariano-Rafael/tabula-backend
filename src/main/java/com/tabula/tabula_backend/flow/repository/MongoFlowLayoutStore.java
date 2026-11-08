package com.tabula.tabula_backend.flow.repository;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.Stored;
import com.tabula.tabula_backend.flow.service.FlowException;

import java.time.Instant;
import java.util.Optional;

import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

@Repository
public class MongoFlowLayoutStore implements FlowLayoutStore {
    private final ObjectProvider<MongoClient> clients;
    private final String database;

    public MongoFlowLayoutStore(ObjectProvider<MongoClient> clients, @Value("${spring.mongodb.database:tabula}") String database) {
        this.clients = clients;
        this.database = database;
    }

    @Override
    public Optional<Stored> find(String portfolioId, String flowId) {
        try {
            return Optional.ofNullable(collection().find(and(eq("_id", flowId), eq("portfolioId", portfolioId))).first()).map(MongoFlowLayoutStore::stored);
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    @Override
    public Stored save(String portfolioId, String flowId, Long revision, String raw, String checksum, String actorId) {
        String now = Instant.now().toString();
        try {
            if (revision == null) {
                var d = new Document("_id", flowId).append("portfolioId", portfolioId).append("revision", 1L)
                        .append("rawJson", raw).append("checksum", checksum).append("updatedBy", actorId).append("updatedAt", now);
                try {
                    collection().insertOne(d);
                    return stored(d);
                } catch (MongoWriteException ex) {
                    if (ex.getError().getCode() == 11000) {
                        throw mismatch();
                    }
                    throw ex;
                }
            }
            var d = collection().findOneAndUpdate(and(eq("_id", flowId), eq("portfolioId", portfolioId), eq("revision", revision)),
                    combine(set("rawJson", raw), set("checksum", checksum), set("updatedBy", actorId), set("updatedAt", now), inc("revision", 1L)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (d == null) {
                throw mismatch();
            }
            return stored(d);
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    private MongoCollection<Document> collection() {
        var client = clients.getIfAvailable();
        if (client == null) {
            throw unavailable();
        }
        // Mongo's unique _id index guarantees a single current layout per flow.
        return client.getDatabase(database).getCollection("flow_layouts");
    }

    private static Stored stored(Document d) {
        return new Stored(d.getString("_id"), d.getString("portfolioId"), d.getLong("revision"), d.getString("rawJson"),
                d.getString("checksum"), d.getString("updatedBy"), d.getString("updatedAt"));
    }

    private static FlowException unavailable() {
        return new FlowException(HttpStatus.SERVICE_UNAVAILABLE, "LAYOUT_STORAGE_UNAVAILABLE", "Layout indisponível no MongoDB.");
    }

    private static FlowException mismatch() {
        return new FlowException(HttpStatus.PRECONDITION_FAILED, "LAYOUT_REVISION_MISMATCH", "Layout alterado ou inexistente. Consulte a revisão atual.");
    }
}
