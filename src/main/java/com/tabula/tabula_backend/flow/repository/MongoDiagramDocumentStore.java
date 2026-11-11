package com.tabula.tabula_backend.flow.repository;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.tabula.tabula_backend.flow.service.FlowException;
import java.util.Optional;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import static com.mongodb.client.model.Filters.*;

/** Immutable blobs; SQL publishes a pointer only after Mongo acknowledges the write. */
@Repository
public class MongoDiagramDocumentStore implements DiagramDocumentStore {
    private final ObjectProvider<MongoClient> clients;
    private final String database;
    public MongoDiagramDocumentStore(ObjectProvider<MongoClient> clients, @Value("${spring.mongodb.database:tabula}") String database) {
        this.clients=clients; this.database=database;
    }
    private com.mongodb.client.MongoCollection<Document> collection() {
        var client=clients.getIfAvailable();
        if(client==null) throw unavailable();
        return client.getDatabase(database).getCollection("diagrams");
    }
    public void put(com.tabula.tabula_backend.flow.dto.EditorDtos.Document d) {
        try {
            collection().insertOne(new Document("_id",d.id()).append("versionId",d.versionId()).append("flowId",d.flowId())
                    .append("projectId",d.projectId()).append("portfolioId",d.portfolioId()).append("checksum",d.checksum())
                    .append("rawJson",d.rawJson()));
        } catch(MongoException ex) { throw unavailable(); }
    }
    public Optional<com.tabula.tabula_backend.flow.dto.EditorDtos.Document> find(String portfolioId,String id) {
        try {
            var d=collection().find(and(eq("_id",id),eq("portfolioId",portfolioId))).first();
            return Optional.ofNullable(d).map(v -> new com.tabula.tabula_backend.flow.dto.EditorDtos.Document(
                    v.getString("_id"),v.getString("versionId"),v.getString("flowId"),v.getString("projectId"),
                    v.getString("portfolioId"),v.getString("checksum"),v.getString("rawJson")));
        } catch(MongoException ex) { throw unavailable(); }
    }
    private static FlowException unavailable() {
        return new FlowException(HttpStatus.SERVICE_UNAVAILABLE,"DIAGRAM_STORAGE_UNAVAILABLE","Não foi possível acessar os diagramas no MongoDB.");
    }
}
