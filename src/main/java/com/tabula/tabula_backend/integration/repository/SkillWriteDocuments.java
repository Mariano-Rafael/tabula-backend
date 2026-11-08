package com.tabula.tabula_backend.integration.repository;

import com.mongodb.client.MongoClient;
import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Documents;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import static com.mongodb.client.model.Filters.*;

/** Raw JSON strings preserve arbitrary IBM keys and numeric precision. Insert once before dispatch. */
@Repository
public class SkillWriteDocuments {
    private final ObjectProvider<MongoClient> clients;
    private final String database;
    public SkillWriteDocuments(ObjectProvider<MongoClient> clients, @Value("${spring.mongodb.database:tabula}") String database) {
        this.clients = clients; this.database = database;
    }
    public void put(Documents d) {
        try { collection().insertOne(new Document("_id", d.id()).append("projectId", d.projectId())
                .append("baselineJson", d.baselineJson()).append("candidateJson", d.candidateJson())); }
        catch (RuntimeException ex) { throw unavailable(); }
    }
    public Documents get(String projectId, String id) {
        try {
            var d = collection().find(and(eq("_id", id), eq("projectId", projectId))).first();
            if (d == null) throw unavailable();
            return new Documents(id, projectId, d.getString("baselineJson"), d.getString("candidateJson"));
        } catch (RuntimeException ex) { throw unavailable(); }
    }
    private com.mongodb.client.MongoCollection<Document> collection() {
        var client = clients.getIfAvailable(); if (client == null) throw unavailable();
        return client.getDatabase(database).getCollection("ibm_skill_write_documents");
    }
    private static IntegrationException unavailable() {
        return new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "IBM_WRITE_DOCUMENT_UNAVAILABLE", "Documento da operação IBM indisponível.");
    }
}
