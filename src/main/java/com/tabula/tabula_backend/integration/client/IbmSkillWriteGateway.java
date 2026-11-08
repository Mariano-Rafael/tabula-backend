package com.tabula.tabula_backend.integration.client;

import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Binding;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Updates only one skill's workspace. No import of sibling skills, assistant state or deployment. */
@Component
public class IbmSkillWriteGateway {
    private final IbmHttpTransport http;
    private final IbmTokenProvider tokens;
    public IbmSkillWriteGateway(IbmHttpTransport http, IbmTokenProvider tokens) { this.http = http; this.tokens = tokens; }
    public Map<String, Object> update(Binding binding, Map<String, Object> workspace) {
        return call(() -> http.post(path(binding), binding.version(), tokens.token(), Map.of("workspace", workspace)));
    }
    public Map<String, Object> status(Binding binding) {
        return call(() -> http.get(path(binding), binding.version(), tokens.token(), false));
    }
    private String path(Binding b) {
        return "/v2/assistants/" + IbmEndpointPolicy.identifier(b.assistantId()) + "/skills/" + IbmEndpointPolicy.identifier(b.skillId());
    }
    private Map<String, Object> call(java.util.function.Supplier<Map<String, Object>> request) {
        try { return request.get(); }
        catch (IntegrationException ex) { if ("IBM_AUTHENTICATION_FAILED".equals(ex.code())) tokens.invalidate(); throw ex; }
    }
}
