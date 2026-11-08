package com.tabula.tabula_backend.integration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;

/**
 * Never serialize or log this object. Values are resolved only from trusted server configuration.
 */
@Component
public class IbmSettings {
    public static IbmSettings connection(String serviceUrl, String apiKey) {
        var settings = new IbmSettings(); settings.serviceUrl = serviceUrl; settings.apiKey = apiKey; return settings;
    }
    @Value("${ibm.watson.service-url:${IBM_SERVICE_URL:}}")
    private String serviceUrl;
    @Value("${ibm.watson.api-key:${IBM_API_KEY:}}")
    private String apiKey;
    @Value("${ibm.watson.v1.version:2021-11-27}")
    private String v1Version;
    @Value("${ibm.watson.v2.version:2024-08-25}")
    private String v2Version;
    @Value("${ibm.watson.connect-timeout-seconds:5}")
    private int connectTimeoutSeconds;
    @Value("${ibm.watson.read-timeout-seconds:20}")
    private int readTimeoutSeconds;
    @Value("${ibm.watson.max-concurrent-probes:2}")
    private int maxConcurrentProbes;
    @Value("${IBM_ASSISTANT_ID:}")
    private String assistantId;
    @Value("${IBM_DRAFT_ENV_ID:}")
    private String draftEnvironmentId;
    @Value("${IBM_LIVE_ENV_ID:}")
    private String liveEnvironmentId;
    @Value("${IBM_DIALOG_WORKSPACE_ID:}")
    private String dialogWorkspaceId;
    @Value("${IBM_DIALOG_SKILL_ID:}")
    private String dialogSkillId;
    @Value("${IBM_ACTION_SKILL_ID:}")
    private String actionSkillId;

    public String serviceUrl() {
        return serviceUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String v1Version() {
        return v1Version;
    }

    public String v2Version() {
        return v2Version;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int readTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public int maxConcurrentProbes() {
        return maxConcurrentProbes;
    }

    public TargetConfig targetDefaults(EnvironmentType type) {
        return new TargetConfig("IBM " + type.name(), type, optional(assistantId),
                optional(type == EnvironmentType.DRAFT ? draftEnvironmentId : liveEnvironmentId),
                optional(dialogWorkspaceId), optional(dialogSkillId), optional(actionSkillId), v1Version, v2Version);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
