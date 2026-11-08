package com.tabula.tabula_backend.conversation.repository;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.service.ConversationException;

import java.util.Optional;

import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

@Repository
public class MongoConversationDocumentStore implements ConversationDocumentStore {
    private final ObjectProvider<MongoClient> clients;
    private final String database;
    private volatile boolean indexed;

    public MongoConversationDocumentStore(ObjectProvider<MongoClient> clients, @Value("${spring.mongodb.database:tabula}") String database) {
        this.clients = clients;
        this.database = database;
    }

    private MongoCollection<Document> collection() {
        var client = clients.getIfAvailable();
        if (client == null) {
            throw unavailable();
        }
        var collection = client.getDatabase(database).getCollection("conversation_documents");
        if (!indexed) {
            synchronized (this) {
                if (!indexed) {
                    collection.createIndex(Indexes.compoundIndex(Indexes.ascending("conversationId"), Indexes.ascending("sequence")), new IndexOptions().unique(true));
                    collection.createIndex(Indexes.compoundIndex(Indexes.ascending("portfolioId"), Indexes.ascending("conversationId")));
                    indexed = true;
                }
            }
        }
        return collection;
    }

    @Override
    public void putRequest(TurnTicket t, String text) {
        try {
            try {
                collection().insertOne(new Document("_id", t.turn().id()).append("conversationId", t.conversation().view().id())
                        .append("projectId", t.conversation().view().projectId()).append("portfolioId", t.conversation().portfolioId())
                        .append("sequence", t.turn().sequence()).append("requestHash", t.turn().requestHash()).append("text", text));
            } catch (MongoWriteException ex) {
                if (ex.getError().getCode() != 11000) {
                    throw ex;
                }
                var existing = find(t.conversation().portfolioId(), t.conversation().view().id(), t.turn().id()).orElseThrow(MongoConversationDocumentStore::conflict);
                if (!existing.requestHash().equals(t.turn().requestHash()) || !existing.text().equals(text)
                        || !existing.projectId().equals(t.conversation().view().projectId()) || existing.sequence() != t.turn().sequence()) {
                    throw conflict();
                }
            }
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    @Override
    public void putResponse(TurnTicket t, String rawJson, String responseHash) {
        try {
            var result = collection().updateOne(and(eq("_id", t.turn().id()), eq("conversationId", t.conversation().view().id()),
                            eq("portfolioId", t.conversation().portfolioId()), eq("projectId", t.conversation().view().projectId()),
                            eq("requestHash", t.turn().requestHash()), exists("responseJson", false)),
                    combine(set("responseJson", rawJson), set("responseHash", responseHash)));
            if (result.getModifiedCount() != 1) {
                var previous = find(t.conversation().portfolioId(), t.conversation().view().id(), t.turn().id()).orElseThrow(MongoConversationDocumentStore::conflict);
                if (!responseHash.equals(previous.responseHash()) || !rawJson.equals(previous.responseJson())) {
                    throw conflict();
                }
            }
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    @Override
    public Optional<TurnDocument> find(String portfolioId, String conversationId, String turnId) {
        try {
            return Optional.ofNullable(collection().find(and(eq("_id", turnId), eq("portfolioId", portfolioId), eq("conversationId", conversationId))).first())
                    .map(d -> new TurnDocument(d.getString("_id"), d.getString("conversationId"), d.getString("projectId"), d.getString("portfolioId"),
                            d.getLong("sequence"), d.getString("requestHash"), d.getString("text"), d.getString("responseJson"), d.getString("responseHash")));
        } catch (MongoException ex) {
            throw unavailable();
        }
    }

    private static ConversationException unavailable() {
        return new ConversationException(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_STORAGE_UNAVAILABLE", "Documentos de conversa indisponíveis.");
    }

    private static ConversationException conflict() {
        return new ConversationException(HttpStatus.CONFLICT, "CONVERSATION_DOCUMENT_CONFLICT", "Documento de conversa incompatível.");
    }
}
