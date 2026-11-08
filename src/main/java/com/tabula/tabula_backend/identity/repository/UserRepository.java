package com.tabula.tabula_backend.identity.repository;

import com.tabula.tabula_backend.identity.model.AccountUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<AccountUser, String> {
    Optional<AccountUser> findByEmail(String email);

    boolean existsByEmailAndIdNot(String email, String id);

    long countByAdminTrueAndActiveTrue();
}
