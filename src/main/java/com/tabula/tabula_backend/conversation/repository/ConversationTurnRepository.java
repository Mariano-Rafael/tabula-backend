package com.tabula.tabula_backend.conversation.repository;

import com.tabula.tabula_backend.conversation.model.ConversationTurn;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConversationTurnRepository extends JpaRepository<ConversationTurn, String> {
    Optional<ConversationTurn> findByConversationIdAndRequestKey(String conversationId, String requestKey);
    Page<ConversationTurn> findByConversationId(String conversationId, Pageable pageable);
    @Query("select t.conversationId from ConversationTurn t where t.id = :id") Optional<String> findConversationId(String id);
}
