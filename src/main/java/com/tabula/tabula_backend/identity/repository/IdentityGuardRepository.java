package com.tabula.tabula_backend.identity.repository;

import com.tabula.tabula_backend.identity.model.IdentityGuard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface IdentityGuardRepository extends JpaRepository<IdentityGuard, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from IdentityGuard g where g.id = 1")
    IdentityGuard lockAccountChanges();
}
