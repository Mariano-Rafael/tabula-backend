package com.tabula.tabula_backend.integration.service;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.identity.service.AccountDirectoryService;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.model.*;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class IntegrationService {
    private final IbmIntegrationRepository integrations;
    private final IbmTargetRepository targets;
    private final PortfolioService portfolios;
    private final AccountDirectoryService accounts;

    public IntegrationService(IbmIntegrationRepository integrations, IbmTargetRepository targets,
                              PortfolioService portfolios, AccountDirectoryService accounts) {
        this.integrations = integrations; this.targets = targets; this.portfolios = portfolios; this.accounts = accounts;
    }

    public IntegrationView create(AccountPrincipal actor, String portfolioId, CreateIntegration input) {
        if (!accounts.current(actor).admin()) {
            throw error(HttpStatus.FORBIDDEN, "GLOBAL_ADMIN_REQUIRED", "Somente o administrador global pode vincular a credencial configurada.");
        }
        portfolios.requirePortfolioManagement(actor, portfolioId);
        if (integrations.findByPortfolioId(portfolioId).isPresent()) {
            throw error(HttpStatus.CONFLICT, "INTEGRATION_ALREADY_EXISTS", "Já existe uma integração configurada neste portfólio.");
        }
        return IntegrationView.from(integrations.save(new IbmIntegration(portfolioId, name(input.name()))));
    }

    @Transactional(readOnly = true)
    public List<IntegrationView> list(AccountPrincipal actor, String portfolioId) {
        portfolios.requirePortfolioRead(actor, portfolioId);
        return integrations.findByPortfolioId(portfolioId).stream().map(IntegrationView::from).toList();
    }

    @Transactional(readOnly = true)
    public IntegrationView find(AccountPrincipal actor, String id) {
        var integration = integration(id);
        portfolios.requirePortfolioRead(actor, integration.getPortfolioId());
        return IntegrationView.from(integration);
    }

    public IntegrationView update(AccountPrincipal actor, String id, UpdateIntegration input) {
        var initial = integration(id);
        portfolios.requirePortfolioManagement(actor, initial.getPortfolioId());
        // The optimistic version also protects the entity loaded before the portfolio lock.
        initial.update(input.name() == null ? initial.getName() : name(input.name()),
                input.active() == null ? initial.isActive() : input.active());
        return IntegrationView.from(initial);
    }

    public TargetView createTarget(AccountPrincipal actor, String projectId, CreateTarget input) {
        var project = portfolios.requireProjectManagement(actor, projectId);
        var integration = integration(input.integrationId().toString());
        if (!integration.getPortfolioId().equals(project.portfolioId())) {
            portfolios.requirePortfolioRead(actor, integration.getPortfolioId());
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "CROSS_PORTFOLIO_TARGET", "Integração e projeto devem pertencer ao mesmo portfólio.");
        }
        requireActive(integration);
        var target = new IbmTarget(projectId, integration.getId());
        configure(target, input.configuration(), true);
        return TargetView.from(targets.save(target));
    }

    @Transactional(readOnly = true)
    public PageView<TargetView> targets(AccountPrincipal actor, String projectId, int page, int size) {
        portfolios.project(actor, projectId);
        if (page < 0 || size < 1 || size > 100) { throw error(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Use page >= 0 e size entre 1 e 100."); }
        return PageView.from(targets.findByProjectId(projectId, PageRequest.of(page, size, Sort.by("id"))).map(TargetView::from));
    }

    @Transactional(readOnly = true)
    public TargetView target(AccountPrincipal actor, String id) {
        var target = target(id);
        portfolios.project(actor, target.getProjectId());
        return TargetView.from(target);
    }

    public TargetView updateTarget(AccountPrincipal actor, String id, TargetConfig input) {
        var target = target(id);
        portfolios.requireProjectManagement(actor, target.getProjectId());
        configure(target, input, target.isActive());
        return TargetView.from(target);
    }

    public void deactivateTarget(AccountPrincipal actor, String id) {
        var target = target(id);
        portfolios.requireProjectManagement(actor, target.getProjectId());
        var c = TargetView.from(target).configuration();
        configure(target, c, false);
    }

    public IntegrationView integrationForProbe(AccountPrincipal actor, String id) {
        var integration = integration(id);
        portfolios.requirePortfolioManagement(actor, integration.getPortfolioId());
        requireActive(integration);
        return IntegrationView.from(integration);
    }

    public TargetView targetForProbe(AccountPrincipal actor, String id) {
        var target = target(id);
        portfolios.requireProjectManagement(actor, target.getProjectId());
        requireActive(integration(target.getIntegrationId()));
        if (!target.isActive()) { throw error(HttpStatus.CONFLICT, "TARGET_DISABLED", "Destino desativado."); }
        return TargetView.from(target);
    }

    @Transactional(readOnly = true)
    public List<Capability> capabilities(AccountPrincipal actor, String id) {
        var t = target(actor, id).configuration();
        return List.of(new Capability("READ_DIALOG_WORKSPACE", true, t.dialogWorkspaceId() != null, "NOT_VERIFIED"),
                new Capability("READ_ENVIRONMENT", true, t.environmentId() != null, "NOT_VERIFIED"),
                new Capability("READ_DIALOG_SKILL", true, t.dialogSkillId() != null, "NOT_VERIFIED"),
                new Capability("READ_ACTION_SKILL", true, t.actionSkillId() != null, "NOT_VERIFIED"),
                new Capability("SEND_MESSAGE", false, false, "NOT_IMPLEMENTED"),
                new Capability("WRITE_FLOW", false, false, "NOT_IMPLEMENTED"));
    }

    public TargetView targetForExport(AccountPrincipal actor, String id) {
        var target = target(id);
        portfolios.requireProjectEditing(actor, target.getProjectId());
        requireActive(integration(target.getIntegrationId()));
        if (!target.isActive()) { throw error(HttpStatus.CONFLICT, "TARGET_DISABLED", "Destino desativado."); }
        return TargetView.from(target);
    }

    private IbmIntegration integration(String id) { return integrations.findById(id).orElseThrow(IntegrationService::notFound); }
    private IbmTarget target(String id) { return targets.findById(id).orElseThrow(IntegrationService::notFound); }
    private static void requireActive(IbmIntegration i) {
        if (!i.isActive()) { throw error(HttpStatus.CONFLICT, "INTEGRATION_DISABLED", "Integração desativada."); }
    }
    private static void configure(IbmTarget target, TargetConfig c, boolean active) {
        String assistant = identifier(c.assistantId());
        String environment = identifier(c.environmentId());
        String workspace = identifier(c.dialogWorkspaceId());
        String dialog = identifier(c.dialogSkillId());
        String action = identifier(c.actionSkillId());
        if (workspace == null && environment == null && dialog == null && action == null) {
            throw error(HttpStatus.BAD_REQUEST, "TARGET_RESOURCE_REQUIRED", "Informe um workspace, ambiente ou skill.");
        }
        if ((environment != null || dialog != null || action != null) && assistant == null) {
            throw error(HttpStatus.BAD_REQUEST, "ASSISTANT_REQUIRED", "Assistant obrigatório para ambientes e skills.");
        }
        if (c.environmentType() == null) { throw error(HttpStatus.BAD_REQUEST, "ENVIRONMENT_TYPE_REQUIRED", "Tipo do ambiente obrigatório."); }
        target.configure(name(c.name()), c.environmentType().name(), assistant, environment, workspace, dialog, action,
                version(c.v1Version()), version(c.v2Version()), active);
    }
    private static String identifier(String value) {
        if (value == null || value.isBlank()) { return null; }
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) { throw error(HttpStatus.BAD_REQUEST, "INVALID_IBM_IDENTIFIER", "Identificador IBM inválido."); }
        return value;
    }
    private static String version(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) { throw new DateTimeParseException("Invalid", "", 0); }
            LocalDate.parse(value);
            return value;
        } catch (DateTimeParseException ex) { throw error(HttpStatus.BAD_REQUEST, "INVALID_API_VERSION", "Versão da API deve ser uma data válida YYYY-MM-DD."); }
    }
    private static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 120) { throw error(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Nome obrigatório com até 120 caracteres."); }
        return value.strip();
    }
    private static IntegrationException notFound() { return error(HttpStatus.NOT_FOUND, "INTEGRATION_RESOURCE_NOT_FOUND", "Recurso não encontrado."); }
    private static IntegrationException error(HttpStatus status, String code, String message) { return new IntegrationException(status, code, message); }
}
