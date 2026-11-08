package com.tabula.tabula_backend.conversation;

import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
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

class IbmConversationGatewayTests {
    private static final String BASE = "https://api.us-south.assistant.watson.cloud.ibm.com/instances/test";
    private MockRestServiceServer server;
    private IbmConversationGateway gateway;
    private IbmTokenProvider tokens;
    @BeforeEach
    void setup() {
        var settings = new IbmSettings(); ReflectionTestUtils.setField(settings, "serviceUrl", BASE);
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(IbmTokenProvider.class); when(tokens.token()).thenReturn("fake-token");
        gateway = new IbmConversationGateway(new IbmHttpTransport(builder.build(), JsonMapper.builder().build(), settings), tokens);
    }
    @Test
    void v1SendsExplicitContextAndNormalizesLegacyText() {
        var binding = new IbmConversationGateway.Binding("V1", "workspace", null, null, "2021-11-27");
        server.expect(requestTo(BASE + "/v1/workspaces/workspace/message?version=2021-11-27"))
                .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-token"))
                .andExpect(jsonPath("$.input.text").value("Hello")).andExpect(jsonPath("$.context.counter").value(1))
                .andExpect(jsonPath("$.user_id").value("conversation"))
                .andRespond(withSuccess("{\"output\":{\"text\":[\"Hello back\"]},\"context\":{\"counter\":2}}", MediaType.APPLICATION_JSON));
        var reply = gateway.message(binding, null, "conversation", "Hello", Map.of("counter", 1));
        assertThat(reply.output()).containsExactly(Map.of("response_type", "text", "text", "Hello back"));
        assertThat(((Number) reply.context().get("counter")).intValue()).isEqualTo(2);
        server.verify();
    }
    @Test
    void v2UsesEnvironmentScopedSessionAndDoesNotInjectContext() {
        var b = new IbmConversationGateway.Binding("V2", null, "assistant", "draft", "2024-08-25");
        String path = BASE + "/v2/assistants/assistant/environments/draft/sessions";
        server.expect(requestTo(path + "?version=2024-08-25")).andExpect(method(HttpMethod.POST)).andExpect(content().json("{}"))
                .andRespond(withSuccess("{\"session_id\":\"session\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(path + "/session/message?version=2024-08-25")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.input.message_type").value("text")).andExpect(jsonPath("$.context").doesNotExist())
                .andRespond(withSuccess("{\"output\":{\"generic\":[{\"response_type\":\"text\",\"text\":\"OK\"}]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(path + "/session?version=2024-08-25")).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        assertThat(gateway.open(b)).isEqualTo("session");
        assertThat(gateway.message(b, "session", "conversation", "Hello", Map.of("untrusted", true)).output()).hasSize(1);
        gateway.close(b, "session"); server.verify();
    }
    @Test
    void missingRemoteSessionIsAnIdempotentClose() {
        var b = new IbmConversationGateway.Binding("V2", null, "assistant", "draft", "2024-08-25");
        server.expect(requestTo(BASE + "/v2/assistants/assistant/environments/draft/sessions/session?version=2024-08-25"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        gateway.close(b, "session"); server.verify();
    }
    @Test
    void malformedResponseCannotAdvanceContext() {
        server.expect(requestTo(BASE + "/v1/workspaces/workspace/message?version=2021-11-27"))
                .andRespond(withSuccess("{\"output\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.message(new IbmConversationGateway.Binding("V1", "workspace", null, null, "2021-11-27"), null, "c", "Hello", Map.of()))
                .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo("IBM_INVALID_MESSAGE_RESPONSE"));
        server.verify();
        assertThatThrownBy(() -> IbmConversationGateway.decode(Map.of("output", Map.of("generic", "invalid")), false))
                .isInstanceOf(IntegrationException.class);
    }
    @Test
    void authenticationFailureInvalidatesTokenWithoutRetryingMessage() {
        server.expect(requestTo(BASE + "/v1/workspaces/workspace/message?version=2021-11-27"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> gateway.message(new IbmConversationGateway.Binding("V1", "workspace", null, null, "2021-11-27"), null, "c", "Hello", Map.of()))
                .isInstanceOf(IntegrationException.class);
        verify(tokens).invalidate(); server.verify();
    }
    @Test
    void dialogSkillIdIsNeverUsedAsWorkspaceId() {
        var config = new TargetConfig("Target", EnvironmentType.DRAFT, "assistant", "environment", null, "dialog", "action", "2021-11-27", "2024-08-25");
        assertThatThrownBy(() -> IbmConversationGateway.bind("V1", config)).isInstanceOfSatisfying(IntegrationException.class,
                ex -> assertThat(ex.code()).isEqualTo("IBM_WORKSPACE_REQUIRED"));
        assertThat(IbmConversationGateway.bind("V2", config).environmentId()).isEqualTo("environment");
        server.verify();
    }
}
