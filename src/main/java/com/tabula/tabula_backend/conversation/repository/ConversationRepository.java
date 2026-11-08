package com.tabula.tabula_backend.conversation.repository;

import com.tabula.tabula_backend.conversation.model.Conversation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    Optional<Conversation> findByProjectIdAndOpenKey(String projectId, String openKey);
    Page<Conversation> findByProjectId(String projectId, Pageable pageable);
    @Query("select c.projectId from Conversation c where c.id = :id") Optional<String> findProjectId(String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id") Optional<Conversation> findLocked(String id);
}
