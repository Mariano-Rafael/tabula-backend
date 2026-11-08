package com.tabula.tabula_backend.portfolio.service;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.identity.service.AccountDirectoryService;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.*;
import com.tabula.tabula_backend.portfolio.model.*;
import com.tabula.tabula_backend.portfolio.repository.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PortfolioService {
    private final PortfolioRepository portfolios;
    private final PortfolioMemberRepository members;
    private final ProjectRepository projects;
    private final AccountDirectoryService accounts;
    private enum Permission { READ, EDIT_PROJECT, MANAGE, OPERATE }

    public PortfolioService(PortfolioRepository portfolios, PortfolioMemberRepository members,
                            ProjectRepository projects, AccountDirectoryService accounts) {
        this.portfolios = portfolios;
        this.members = members;
        this.projects = projects;
        this.accounts = accounts;
    }

    public PortfolioView create(AccountPrincipal actor, Create input) {
        var identity = accounts.current(actor);
        var portfolio = portfolios.saveAndFlush(new Portfolio(name(input.name()), description(input.description())));
        members.save(new PortfolioMember(portfolio.getId(), identity.id(), PortfolioRole.OWNER));
        return PortfolioView.from(portfolio);
    }

    @Transactional(readOnly = true)
    public PageView<PortfolioView> list(AccountPrincipal actor, boolean includeArchived, int page, int size) {
        var identity = accounts.current(actor);
        return PageView.from(portfolios.findAccessible(identity.id(), identity.admin(), includeArchived,
                paging(page, size, "id")).map(PortfolioView::from));
    }

    @Transactional(readOnly = true)
    public PortfolioView find(AccountPrincipal actor, String id) {
        return PortfolioView.from(access(actor, id, false, Permission.READ));
    }

    public PortfolioView update(AccountPrincipal actor, String id, Update input) {
        var portfolio = access(actor, id, true, Permission.MANAGE);
        requireActive(portfolio);
        portfolio.update(input.name() == null ? portfolio.getName() : name(input.name()),
                input.description() == null ? portfolio.getDescription() : description(input.description()));
        return PortfolioView.from(portfolio);
    }

    public void archive(AccountPrincipal actor, String id) {
        access(actor, id, true, Permission.MANAGE).archive();
    }

    @Transactional(readOnly = true)
    public PageView<MemberView> members(AccountPrincipal actor, String id, int page, int size) {
        access(actor, id, false, Permission.READ);
        return PageView.from(members.findByPortfolioId(id, paging(page, size, "userId")).map(MemberView::from));
    }

    public MemberView addMember(AccountPrincipal actor, String id, AddMember input) {
        requireActive(access(actor, id, true, Permission.MANAGE));
        String userId = input.userId().toString();
        if (!accounts.isActive(userId)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "MEMBER_ACCOUNT_UNAVAILABLE", "Conta inexistente ou desativada.");
        }
        if (members.findByPortfolioIdAndUserId(id, userId).isPresent()) {
            throw error(HttpStatus.CONFLICT, "MEMBER_ALREADY_EXISTS", "Usuário já pertence ao portfólio.");
        }
        return MemberView.from(members.save(new PortfolioMember(id, userId, input.role())));
    }

    public MemberView changeRole(AccountPrincipal actor, String id, String userId, ChangeRole input) {
        requireActive(access(actor, id, true, Permission.MANAGE));
        var member = member(id, userId);
        if (input.role() != PortfolioRole.OWNER) { protectLastOwner(id, member); }
        member.changeRole(input.role());
        return MemberView.from(member);
    }

    public void removeMember(AccountPrincipal actor, String id, String userId) {
        requireActive(access(actor, id, true, Permission.MANAGE));
        var member = member(id, userId);
        protectLastOwner(id, member);
        members.delete(member);
    }

    public ProjectView createProject(AccountPrincipal actor, String portfolioId, Create input) {
        requireActive(access(actor, portfolioId, true, Permission.EDIT_PROJECT));
        return ProjectView.from(projects.save(new Project(portfolioId, name(input.name()), description(input.description()))));
    }

    @Transactional(readOnly = true)
    public PageView<ProjectView> projects(AccountPrincipal actor, String portfolioId, boolean includeArchived, int page, int size) {
        access(actor, portfolioId, false, Permission.READ);
        return PageView.from(projects.findInPortfolio(portfolioId, includeArchived, paging(page, size, "id"))
                .map(ProjectView::from));
    }

    @Transactional(readOnly = true)
    public ProjectView project(AccountPrincipal actor, String id) {
        String portfolioId = projectPortfolio(id);
        access(actor, portfolioId, false, Permission.READ);
        return ProjectView.from(project(id));
    }

    /** Cross-module authorization contract; no repository or persistence entity is exposed. */
    @Transactional(readOnly = true)
    public void requirePortfolioRead(AccountPrincipal actor, String portfolioId) {
        access(actor, portfolioId, false, Permission.READ);
    }

    /** Joins the caller transaction so archival and membership changes cannot race local writes. */
    public void requirePortfolioManagement(AccountPrincipal actor, String portfolioId) {
        requireActive(access(actor, portfolioId, true, Permission.MANAGE));
    }

    public ProjectView requireProjectManagement(AccountPrincipal actor, String id) {
        requireActive(access(actor, projectPortfolio(id), true, Permission.MANAGE));
        var project = project(id);
        if (project.isArchived()) { throw archived(); }
        return ProjectView.from(project);
    }

    public ProjectView requireProjectEditing(AccountPrincipal actor, String id) {
        requireActive(access(actor, projectPortfolio(id), true, Permission.EDIT_PROJECT));
        var project = project(id);
        if (project.isArchived()) { throw archived(); }
        return ProjectView.from(project);
    }

    public ProjectView requireProjectOperation(AccountPrincipal actor, String id, boolean allowArchived) {
        var portfolio = access(actor, projectPortfolio(id), true, Permission.OPERATE);
        var project = project(id);
        if (!allowArchived) {
            requireActive(portfolio);
            if (project.isArchived()) { throw archived(); }
        }
        return ProjectView.from(project);
    }

    public ProjectView updateProject(AccountPrincipal actor, String id, Update input) {
        requireActive(access(actor, projectPortfolio(id), true, Permission.EDIT_PROJECT));
        var project = project(id);
        if (project.isArchived()) { throw archived(); }
        project.update(input.name() == null ? project.getName() : name(input.name()),
                input.description() == null ? project.getDescription() : description(input.description()));
        return ProjectView.from(project);
    }

    public void archiveProject(AccountPrincipal actor, String id) {
        requireActive(access(actor, projectPortfolio(id), true, Permission.EDIT_PROJECT));
        project(id).archive();
    }

    // All writes lock the portfolio before reading memberships. This serializes role changes,
    // archival and project mutations, including concurrent removal of the last owner.
    private Portfolio access(AccountPrincipal actor, String id, boolean lock, Permission permission) {
        var portfolio = (lock ? portfolios.findLocked(id) : portfolios.findById(id)).orElseThrow(PortfolioService::notFound);
        var identity = accounts.current(actor);
        if (identity.admin()) { return portfolio; }
        var role = members.findByPortfolioIdAndUserId(id, identity.id()).orElseThrow(PortfolioService::notFound).getRole();
        boolean permitted = switch (permission) {
            case READ -> true;
            case MANAGE -> role == PortfolioRole.OWNER;
            case EDIT_PROJECT -> role.canEditProjects();
            case OPERATE -> role != PortfolioRole.VIEWER;
        };
        if (!permitted) { throw error(HttpStatus.FORBIDDEN, "PORTFOLIO_PERMISSION_REQUIRED", "Permissão insuficiente no portfólio."); }
        return portfolio;
    }

    private PortfolioMember member(String id, String userId) {
        return members.findByPortfolioIdAndUserId(id, userId).orElseThrow(() ->
                error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Membro não encontrado."));
    }

    private void protectLastOwner(String portfolioId, PortfolioMember member) {
        if (member.getRole() == PortfolioRole.OWNER && members.countByPortfolioIdAndRole(portfolioId, PortfolioRole.OWNER) <= 1) {
            throw error(HttpStatus.CONFLICT, "LAST_PORTFOLIO_OWNER", "O último OWNER do portfólio deve ser mantido.");
        }
    }

    private String projectPortfolio(String id) { return projects.findPortfolioId(id).orElseThrow(PortfolioService::notFound); }
    private Project project(String id) { return projects.findById(id).orElseThrow(PortfolioService::notFound); }
    private static void requireActive(Portfolio p) { if (p.isArchived()) { throw archived(); } }
    private static PortfolioException archived() { return error(HttpStatus.CONFLICT, "RESOURCE_ARCHIVED", "Recurso arquivado não permite alterações."); }
    private static PortfolioException notFound() { return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Recurso não encontrado."); }
    private static PortfolioException error(HttpStatus status, String code, String message) { return new PortfolioException(status, code, message); }

    private static Pageable paging(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) { throw error(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Use page >= 0 e size entre 1 e 100."); }
        return PageRequest.of(page, size, Sort.by(sort));
    }
    private static String name(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 120) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Nome obrigatório com até 120 caracteres.");
        }
        return value.strip();
    }
    private static String description(String value) {
        if (value != null && value.length() > 1000) { throw error(HttpStatus.BAD_REQUEST, "INVALID_DESCRIPTION", "Descrição deve ter até 1000 caracteres."); }
        return value == null ? "" : value.strip();
    }
}
