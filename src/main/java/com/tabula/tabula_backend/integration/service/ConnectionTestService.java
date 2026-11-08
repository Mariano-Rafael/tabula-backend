package com.tabula.tabula_backend.integration.service;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Deliberately not transactional: database authorization completes before remote HTTP calls. */
@Service
public class ConnectionTestService {
    private final IntegrationService integrations;
    private final IbmSettings settings;
    private final IbmTokenProvider tokens;
    private final IbmV1ReadClient v1;
    private final IbmV2ReadClient v2;
    private final Semaphore permits;
    public ConnectionTestService(IntegrationService integrations, IbmSettings settings, IbmTokenProvider tokens,
                                 IbmV1ReadClient v1, IbmV2ReadClient v2) {
        this.integrations = integrations; this.settings = settings; this.tokens = tokens; this.v1 = v1; this.v2 = v2;
        this.permits = new Semaphore(settings.maxConcurrentProbes());
    }
    public ProbeResult integration(AccountPrincipal actor, String id) {
        integrations.integrationForProbe(actor, id);
        return probe(token -> List.of(check("V1_WORKSPACES", () -> v1.probe(settings.v1Version(), token)),
                check("V2_ASSISTANTS", () -> v2.probe(settings.v2Version(), token))));
    }
    public ProbeResult target(AccountPrincipal actor, String id) {
        var c = integrations.targetForProbe(actor, id).configuration();
        return configuredTarget(c);
    }
    // Internal entry point also used by the explicit local read-only smoke test.
    public ProbeResult configuredTarget(TargetConfig c) {
        return probe(token -> {
            List<Check> checks = new ArrayList<>();
            checks.add(optional("DIALOG_WORKSPACE", c.dialogWorkspaceId(), () -> v1.workspace(c.dialogWorkspaceId(), c.v1Version(), token)));
            checks.add(optional("ENVIRONMENT", c.environmentId(), () -> v2.environment(c.assistantId(), c.environmentId(), c.v2Version(), token)));
            checks.add(optional("DIALOG_SKILL", c.dialogSkillId(), () -> v2.skill(c.assistantId(), c.dialogSkillId(), c.v2Version(), token)));
            checks.add(optional("ACTION_SKILL", c.actionSkillId(), () -> v2.skill(c.assistantId(), c.actionSkillId(), c.v2Version(), token)));
            return checks;
        });
    }
    private ProbeResult probe(java.util.function.Function<String, List<Check>> calls) {
        if (!permits.tryAcquire()) { throw new IntegrationException(HttpStatus.TOO_MANY_REQUESTS, "IBM_PROBE_BUSY", "Limite de testes IBM simultâneos atingido."); }
        try {
            final String token;
            try { token = tokens.token(); }
            catch (IntegrationException ex) { return result(List.of(new Check("AUTHENTICATION", "FAILED", ex.code()))); }
            var checks = new ArrayList<Check>();
            checks.add(new Check("AUTHENTICATION", "AVAILABLE", "OK"));
            checks.addAll(calls.apply(token));
            return result(checks);
        } finally { permits.release(); }
    }
    private Check check(String resource, Runnable call) {
        try { call.run(); return new Check(resource, "AVAILABLE", "OK"); }
        catch (IntegrationException ex) {
            if ("IBM_AUTHENTICATION_FAILED".equals(ex.code())) { tokens.invalidate(); }
            return new Check(resource, "IBM_OPERATION_NOT_SUPPORTED".equals(ex.code()) ? "UNSUPPORTED" : "FAILED", ex.code());
        }
    }
    private Check optional(String resource, String id, Runnable call) {
        return id == null || id.isBlank() ? new Check(resource, "NOT_CONFIGURED", "NOT_CONFIGURED") : check(resource, call);
    }
    private static ProbeResult result(List<Check> checks) {
        return new ProbeResult(Instant.now(), checks.stream().allMatch(c -> "AVAILABLE".equals(c.status()) || "NOT_CONFIGURED".equals(c.status())), List.copyOf(checks));
    }
}
