package com.tabula.tabula_backend.integration.model;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "ibm_targets")
public class IbmTarget {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;
    @Column(name = "integration_id", nullable = false, length = 36)
    private String integrationId;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(name = "environment_type", nullable = false, length = 20)
    private String environmentType;
    @Column(name = "assistant_id", length = 128)
    private String assistantId;
    @Column(name = "environment_id", length = 128)
    private String environmentId;
    @Column(name = "dialog_workspace_id", length = 128)
    private String dialogWorkspaceId;
    @Column(name = "dialog_skill_id", length = 128)
    private String dialogSkillId;
    @Column(name = "action_skill_id", length = 128)
    private String actionSkillId;
    @Column(name = "v1_version", nullable = false, length = 10)
    private String v1Version;
    @Column(name = "v2_version", nullable = false, length = 10)
    private String v2Version;
    @Column(nullable = false)
    private boolean active;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected IbmTarget() {
    }

    public IbmTarget(String projectId, String integrationId) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.integrationId = integrationId;
    }

    public void configure(String name, String environmentType, String assistantId, String environmentId,
                          String dialogWorkspaceId, String dialogSkillId, String actionSkillId, String v1Version, String v2Version, boolean active) {
        this.name = name;
        this.environmentType = environmentType;
        this.assistantId = assistantId;
        this.environmentId = environmentId;
        this.dialogWorkspaceId = dialogWorkspaceId;
        this.dialogSkillId = dialogSkillId;
        this.actionSkillId = actionSkillId;
        this.v1Version = v1Version;
        this.v2Version = v2Version;
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getIntegrationId() {
        return integrationId;
    }

    public String getName() {
        return name;
    }

    public String getEnvironmentType() {
        return environmentType;
    }

    public String getAssistantId() {
        return assistantId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public String getDialogWorkspaceId() {
        return dialogWorkspaceId;
    }

    public String getDialogSkillId() {
        return dialogSkillId;
    }

    public String getActionSkillId() {
        return actionSkillId;
    }

    public String getV1Version() {
        return v1Version;
    }

    public String getV2Version() {
        return v2Version;
    }

    public boolean isActive() {
        return active;
    }
}
