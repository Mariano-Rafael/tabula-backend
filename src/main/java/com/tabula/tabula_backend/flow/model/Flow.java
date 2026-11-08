package com.tabula.tabula_backend.flow.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name = "flows")
public class Flow {
    @Id @Column(length = 36) private String id;
    @Column(name = "project_id", nullable = false, length = 36) private String projectId;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "flow_type", nullable = false, length = 16) private String type;
    @Column(nullable = false) private boolean archived;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    protected Flow() { }
    public Flow(String projectId, String name, String type) { id = UUID.randomUUID().toString(); this.projectId = projectId; this.name = name; this.type = type; }
    public void rename(String name) { this.name = name; }
    public void archive() { archived = true; }
    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public boolean isArchived() { return archived; }
}
