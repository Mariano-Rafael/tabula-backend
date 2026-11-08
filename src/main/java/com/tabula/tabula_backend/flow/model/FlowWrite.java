package com.tabula.tabula_backend.flow.model;

import jakarta.persistence.*;

@Entity @Table(name = "flow_write_outbox")
public class FlowWrite {
    @Id @Column(name = "version_id", length = 36) private String versionId;
    @Column(nullable = false, length = 16) private String state;
    @Column(name = "lease_until", nullable = false) private long leaseUntil;
    protected FlowWrite() { }
    public FlowWrite(String versionId) { this.versionId = versionId; state = "PENDING"; leaseUntil = System.currentTimeMillis() + 120_000; }
    public void finish(boolean success) { state = success ? "DONE" : "FAILED"; }
    public long getLeaseUntil() { return leaseUntil; }
}
