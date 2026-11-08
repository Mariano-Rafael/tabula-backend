package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IbmHttpClientTests {
    private static final String BASE = "https://api.us-south.assistant.watson.cloud.ibm.com/instances/test";
    private MockRestServiceServer server;
    private IbmHttpTransport http;
    private IbmSettings settings;

    @BeforeEach
    void setup() {
        settings = new IbmSettings();
        ReflectionTestUtils.setField(settings, "serviceUrl", BASE);
        ReflectionTestUtils.setField(settings, "apiKey", "test-secret+&");
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        http = new IbmHttpTransport(builder.build(), JsonMapper.builder().build(), settings);
    }

    @Test
    void iamUsesEncodedFormAndCachesTokenUntilInvalidated() {
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo("https://iam.cloud.ibm.com/identity/token"))
                    .andExpect(method(HttpMethod.POST)).andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                    .andExpect(content().string(containsString("apikey=test-secret%2B%26")))
                    .andExpect(content().string(containsString("grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey")))
                    .andRespond(withSuccess("{\"access_token\":\"fake-token\",\"expiration\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}", MediaType.APPLICATION_JSON));
        }
        var tokens = new IbmTokenProvider(http);
        assertThat(tokens.token()).isEqualTo("fake-token");
        assertThat(tokens.token()).isEqualTo("fake-token");
        tokens.invalidate();
        assertThat(tokens.token()).isEqualTo("fake-token");
        server.verify();
    }

    @Test
    void versionedReadClientsUseDocumentedPathsAndBearerAuthentication() {
        String[] paths = {"/v1/workspaces?version=2021-11-27&page_limit=1", "/v1/workspaces/workspace?version=2021-11-27",
                "/v2/assistants?version=2024-08-25&page_limit=1",
                "/v2/assistants/assistant/environments/environment?version=2024-08-25",
                "/v2/assistants/assistant/skills/skill?version=2024-08-25"};
        for (String path : paths) {
            server.expect(requestTo(BASE + path)).andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-token"))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }
        var v1 = new IbmV1ReadClient(http);
        var v2 = new IbmV2ReadClient(http);
        v1.probe("2021-11-27", "fake-token");
        v1.workspace("workspace", "2021-11-27", "fake-token");
        v2.probe("2024-08-25", "fake-token");
        v2.environment("assistant", "environment", "2024-08-25", "fake-token");
        v2.skill("assistant", "skill", "2024-08-25", "fake-token");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 401, 403, 404, 429, 500})
    void upstreamErrorsAreSanitizedAndNeverRetried(int status) {
        server.expect(requestTo(BASE + "/v1/workspaces?version=2021-11-27&page_limit=1"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("sensitive-upstream-body test-secret"));
        assertThatThrownBy(() -> new IbmV1ReadClient(http).probe("2021-11-27", "fake-token"))
                .isInstanceOf(IntegrationException.class).hasMessageNotContaining("test-secret")
                .hasMessageNotContaining("sensitive-upstream-body");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.us-south.assistant.watson.cloud.ibm.com", "https://localhost/instances/test",
            "https://api.us-south.assistant.watson.cloud.ibm.com.evil.test", "https://api.us-south.assistant.watson.cloud.ibm.com/instances/test?redirect=x",
            "https://user:pass@api.us-south.assistant.watson.cloud.ibm.com/instances/test", "https://127.0.0.1/instances/test"})
    void unsafeEndpointsAreRejectedBeforeIam(String endpoint) {
        ReflectionTestUtils.setField(settings, "serviceUrl", endpoint);
        assertThatThrownBy(http::authenticate).isInstanceOfSatisfying(IntegrationException.class,
                ex -> assertThat(ex.code()).isEqualTo("IBM_CONFIGURATION_INVALID"));
        server.verify();
    }

    @Test
    void oversizedAndMalformedResponsesAreRejected() {
        server.expect(requestTo(BASE + "/v1/workspaces?version=2021-11-27&page_limit=1"))
                .andRespond(withSuccess("x".repeat(1024 * 1024 + 1), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/workspaces?version=2021-11-27&page_limit=1"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        var v1 = new IbmV1ReadClient(http);
        assertThatThrownBy(() -> v1.probe("2021-11-27", "fake-token")).isInstanceOfSatisfying(IntegrationException.class,
                ex -> assertThat(ex.code()).isEqualTo("IBM_RESPONSE_TOO_LARGE"));
        assertThatThrownBy(() -> v1.probe("2021-11-27", "fake-token")).isInstanceOfSatisfying(IntegrationException.class,
                ex -> assertThat(ex.code()).isEqualTo("IBM_INVALID_RESPONSE"));
        server.verify();
    }

    @Test
    void documentedLitePlanRestrictionHasItsOwnCode() {
        server.expect(requestTo(BASE + "/v2/assistants?version=2024-08-25&page_limit=1"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"API is not supported on lite plan\",\"code\":400}"));
        assertThatThrownBy(() -> new IbmV2ReadClient(http).probe("2024-08-25", "fake-token"))
                .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo("IBM_OPERATION_NOT_SUPPORTED"));
        server.verify();
    }

    @Test
    void exportSupportsLargerPayloadsAndPreservesDecimalPrecision() {
        String body = "{\"decimal\":1234567890.1234567890123456789,\"unknown\":\"" + "x".repeat(1024 * 1024) + "\"}";
        server.expect(requestTo(BASE + "/v1/workspaces/workspace?version=2021-11-27&export=true"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var result = http.getExport("/v1/workspaces/workspace", "2021-11-27", "fake-token", java.util.Map.of("export", "true"));
        assertThat(result.get("decimal")).isEqualTo(new java.math.BigDecimal("1234567890.1234567890123456789"));
        assertThat(((String) result.get("unknown")).length()).isEqualTo(1024 * 1024);
        server.verify();
    }

    @Test
    void timeoutsAndInvalidTokensHaveStableErrors() {
        server.expect(requestTo(BASE + "/v1/workspaces?version=2021-11-27&page_limit=1"))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("sensitive detail"); });
        server.expect(requestTo("https://iam.cloud.ibm.com/identity/token"))
                .andRespond(withSuccess("{\"access_token\":\"fake-token\",\"expiration\":1}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new IbmV1ReadClient(http).probe("2021-11-27", "fake-token"))
                .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo("IBM_TIMEOUT"));
        assertThatThrownBy(() -> new IbmTokenProvider(http).token()).isInstanceOfSatisfying(IntegrationException.class,
                ex -> assertThat(ex.code()).isEqualTo("IBM_INVALID_TOKEN_RESPONSE"));
        server.verify();
    }
}
