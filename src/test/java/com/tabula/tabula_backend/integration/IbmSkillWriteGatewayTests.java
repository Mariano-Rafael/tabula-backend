package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Binding;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IbmSkillWriteGatewayTests {
    private static final String BASE = "https://api.us-south.assistant.watson.cloud.ibm.com/instances/test";
    private MockRestServiceServer server;
    private IbmSkillWriteGateway gateway;
    private IbmTokenProvider tokens;
    @BeforeEach void setup() {
        var settings = new IbmSettings(); ReflectionTestUtils.setField(settings, "serviceUrl", BASE);
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(IbmTokenProvider.class); when(tokens.token()).thenReturn("fake-token");
        gateway = new IbmSkillWriteGateway(new IbmHttpTransport(builder.build(), JsonMapper.builder().build(), settings), tokens);
    }
    @Test void updatesOnlyRequestedSkillWorkspaceAndReadsAsyncStatus() {
        String url = BASE + "/v2/assistants/assistant/skills/dialog?version=2024-08-25";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-token"))
                .andExpect(content().json("{\"workspace\":{\"dialog_nodes\":[]}}", true))
                .andRespond(withSuccess("{\"status\":\"Processing\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"Available\"}", MediaType.APPLICATION_JSON));
        var b = new Binding("assistant", "dialog", "2024-08-25");
        assertThat(gateway.update(b, Map.of("dialog_nodes", List.of())).get("status")).isEqualTo("Processing");
        assertThat(gateway.status(b).get("status")).isEqualTo("Available"); server.verify();
    }
    @Test void rejectionDoesNotRetryAndInvalidatesAuthentication() {
        server.expect(requestTo(BASE + "/v2/assistants/assistant/skills/action?version=2024-08-25"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> gateway.update(new Binding("assistant", "action", "2024-08-25"), Map.of("actions", List.of())))
                .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo("IBM_AUTHENTICATION_FAILED"));
        verify(tokens).invalidate(); server.verify();
    }
}
