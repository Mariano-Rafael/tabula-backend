package com.tabula.tabula_backend.integration.repository;

import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.*;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;

@Repository
public class SkillWriteLedger {
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    public SkillWriteLedger(JdbcTemplate sql, PlatformTransactionManager manager) { this.sql = sql; this.tx = new TransactionTemplate(manager); }
    public Optional<Row> existing(String projectId, String key) {
        return query("SELECT * FROM ibm_skill_writes WHERE project_id=? AND request_key=?", projectId, key).stream().findFirst();
    }
    public Row get(String id) {
        return query("SELECT * FROM ibm_skill_writes WHERE id=?", id).stream().findFirst().orElseThrow(() ->
                new IntegrationException(HttpStatus.NOT_FOUND, "IBM_WRITE_NOT_FOUND", "Operação não encontrada."));
    }
    public com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView<View> list(String projectId, int page, int size) {
        long total = sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_writes WHERE project_id=?", Long.class, projectId);
        var rows = query("SELECT * FROM ibm_skill_writes WHERE project_id=? ORDER BY created_at DESC, id LIMIT ? OFFSET ?", projectId, size, (long) page * size);
        return new com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView<>(rows.stream().map(Row::view).toList(), page, size, total, (int) Math.ceil((double) total / size));
    }
    public void insert(Row r, String key) {
        var v = r.view();
        sql.update("INSERT INTO ibm_skill_writes (id, project_id, target_id, version_id, created_by, flow_type, binding_json, resource_hash, request_key, request_hash, baseline_hash, candidate_hash, state, created_at, lease_until) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                v.id(), v.projectId(), v.targetId(), v.versionId(), v.createdBy(), v.type(), r.bindingJson(), r.resourceHash(), key, r.requestHash(), v.baselineHash(), v.candidateHash(), v.state(), v.createdAt(), r.leaseUntil());
    }
    public void ready(String id) { sql.update("UPDATE ibm_skill_writes SET state='READY' WHERE id=? AND state='PREPARING'", id); }
    public boolean claim(String id) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var r = query("SELECT * FROM ibm_skill_writes WHERE id=? FOR UPDATE", id).getFirst();
            if (!"READY".equals(r.view().state())) return false;
            // Unique key serializes writes even when separate projects bind the same assistant/skill.
            sql.update("INSERT INTO ibm_skill_write_locks(resource_hash, operation_id) VALUES (?,?)", r.resourceHash(), id);
            sql.update("UPDATE ibm_skill_writes SET state='CHECKING', lease_until=? WHERE id=?", deadline(), id);
            return true;
        }));
    }
    public boolean sending(String id) {
        return sql.update("UPDATE ibm_skill_writes SET state='SENDING', lease_until=? WHERE id=? AND state='CHECKING'", deadline(), id) == 1;
    }
    public void retryPreflight(String id) {
        tx.executeWithoutResult(status -> {
            int changed = sql.update("UPDATE ibm_skill_writes SET state='READY' WHERE id=? AND state='CHECKING'", id);
            if (changed == 1) sql.update("DELETE FROM ibm_skill_write_locks WHERE operation_id=?", id);
        });
    }
    public void dispatched(String id) {
        sql.update("UPDATE ibm_skill_writes SET state='PROCESSING', lease_until=? WHERE id=? AND state='SENDING'", deadline(), id);
    }
    public void finish(String id, String state, String code) {
        tx.executeWithoutResult(status -> {
            int changed = sql.update("UPDATE ibm_skill_writes SET state=?, error_code=? WHERE id=? AND state IN ('PREPARING','CHECKING','SENDING','PROCESSING','UNKNOWN')", state, code, id);
            if (changed == 1 && !Set.of("UNKNOWN", "PROCESSING").contains(state)) sql.update("DELETE FROM ibm_skill_write_locks WHERE operation_id=?", id);
        });
    }
    public void expireUnsent(String id) {
        tx.executeWithoutResult(status -> {
            int changed = sql.update("UPDATE ibm_skill_writes SET state='FAILED', error_code='IBM_WRITE_NOT_DISPATCHED' WHERE id=? AND state IN ('PREPARING','CHECKING') AND lease_until<?", id, System.currentTimeMillis());
            if (changed == 1) sql.update("DELETE FROM ibm_skill_write_locks WHERE operation_id=?", id);
        });
    }
    private List<Row> query(String query, Object... args) {
        return sql.query(query, (rs, n) -> new Row(new View(rs.getString("id"), rs.getString("project_id"), rs.getString("target_id"), rs.getString("version_id"),
                rs.getString("flow_type"), rs.getString("state"), rs.getString("baseline_hash"), rs.getString("candidate_hash"), rs.getString("created_by"), rs.getString("created_at"), rs.getString("error_code")),
                rs.getString("binding_json"), rs.getString("resource_hash"), rs.getString("request_hash"), rs.getLong("lease_until")), args);
    }
    public static long deadline() { return System.currentTimeMillis() + 120_000; }
}
