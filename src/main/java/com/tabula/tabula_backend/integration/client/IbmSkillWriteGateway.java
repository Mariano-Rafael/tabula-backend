package com.tabula.tabula_backend.integration.client;

import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Binding;
import com.tabula.tabula_backend.integration.service.IntegrationException;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Updates only one skill's workspace. No import of sibling skills, assistant state or deployment.
 */
@Component
public class IbmSkillWriteGateway {
    private final IbmHttpTransport http;
    private final IbmTokenProvider tokens;
    private final IbmClientFactory clients;

    public IbmSkillWriteGateway(IbmHttpTransport http, IbmTokenProvider tokens) {
        this(http, tokens, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public IbmSkillWriteGateway(IbmHttpTransport http, IbmTokenProvider tokens, IbmClientFactory clients) {
        this.http = http;
        this.tokens = tokens;
        this.clients = clients;
    }

    public Map<String, Object> update(Binding binding, Map<String, Object> workspace) {
        var c = client(binding);
        return call(c.tokens(), () -> c.http().post(path(binding), binding.version(), c.tokens().token(), Map.of("workspace", workspace)));
    }

    public Map<String, Object> status(Binding binding) {
        var c = client(binding);
        return call(c.tokens(), () -> c.http().get(path(binding), binding.version(), c.tokens().token(), false));
    }

    private String path(Binding b) {
        return "/v2/assistants/" + IbmEndpointPolicy.identifier(b.assistantId()) + "/skills/" + IbmEndpointPolicy.identifier(b.skillId());
    }

    private IbmClientFactory.Clients client(Binding b) { return clients == null ? new IbmClientFactory.Clients(http, tokens) : clients.clients(b.connection()); }
    private Map<String, Object> call(IbmTokenProvider tokens, java.util.function.Supplier<Map<String, Object>> request) {
        try {
            return request.get();
        } catch (IntegrationException ex) {
            if ("IBM_AUTHENTICATION_FAILED".equals(ex.code())) {
                tokens.invalidate();
            }
            throw ex;
        }
    }
}
